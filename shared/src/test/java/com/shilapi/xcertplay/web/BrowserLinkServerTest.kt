package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserLinkServerTest {
    private val code = "123456"
    private val session = "page-session-0001"
    private val other = "page-session-0002"
    private val origin = "https://tiplay.example"

    private val hub = WebVideoHub(Executor { it.run() })
    private val owner = Any()
    private val keyFrameRequests = AtomicInteger()
    private val reports = CopyOnWriteArrayList<String>()
    private val touches = CopyOnWriteArrayList<List<AirPlayContact>>()
    private val viewports = CopyOnWriteArrayList<BrowserViewport>()
    private val keys = CopyOnWriteArrayList<String>()
    private val stats = CopyOnWriteArrayList<Map<String, Any?>>()
    private val fits = AtomicInteger()
    private val logs = CopyOnWriteArrayList<String>()
    @Volatile private var pairingCode: String? = code
    @Volatile private var beforeViewport: () -> Unit = {}
    @Volatile private var status = BrowserLinkStatus(
        BrowserLinkStatus.State.STREAMING,
        codec = "avc1.640028",
        display = BrowserSize(1182, 920),
        viewport = BrowserSize(1182, 920),
        touchAvailable = true,
        phone = mapOf("rx" to 60),
    )

    private val callbacks = object : BrowserLinkServer.Callbacks {
        override fun code() = pairingCode
        override fun status() = status
        override fun onTouch(contacts: List<AirPlayContact>): Boolean = touches.add(contacts)
        override fun onViewport(viewport: BrowserViewport) {
            beforeViewport()
            viewports += viewport
        }
        override fun onFit() { fits.incrementAndGet() }
        override fun onKey(name: String) { keys += name }
        override fun onBrowserStats(stats: Map<String, Any?>) { this@BrowserLinkServerTest.stats += stats }
        override fun log(message: String) { logs += message }
    }

    private val server = BrowserLinkServer(hub, callbacks, "127.0.0.1", 0)
    private var port = 0

    private val avcc = byteArrayOf(1, 0x64, 0x00, 0x28, -1, -31, 0, 4, 0x67, 0x64, 0x00, 0x28, 1, 0, 2, 0x68, -18)
    private val parameterSets = byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x28, 0, 0, 0, 1, 0x68, -18)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)
    private val delta = byteArrayOf(0, 0, 0, 1, 0x41, 4, 5)

    @Before fun start() {
        port = server.start().getOrThrow()
    }

    @After fun stop() {
        server.stop()
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
        val json get() = WebJson.parse(text) as Map<*, *>
    }

    private inner class Client : Closeable {
        val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val input = BufferedInputStream(socket.getInputStream())

        fun send(text: String) = socket.getOutputStream().write(text.toByteArray(Charsets.UTF_8))

        fun get(path: String, headers: String = defaultHeaders()) = send("GET $path HTTP/1.1\r\n$headers\r\n")

        fun post(path: String, body: String, headers: String = defaultHeaders()) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            send("POST $path HTTP/1.1\r\n${headers}Content-Type: text/plain;charset=UTF-8\r\nContent-Length: ${bytes.size}\r\n\r\n$body")
        }

        /** Status and headers; the body when it has a Content-Length. */
        fun response(): Response {
            val head = ByteArrayOutputStream()
            while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) throw IOException("closed before a response")
                head.write(next)
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).trimEnd().split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val body = headers["content-length"]?.let { bytes(it.toInt()) } ?: ByteArray(0)
            return Response(lines[0].split(' ')[1].toInt(), headers, body)
        }

        fun bytes(count: Int): ByteArray {
            val result = ByteArray(count)
            var read = 0
            while (read < count) {
                val n = input.read(result, read, count - read)
                if (n < 0) throw IOException("closed after $read of $count bytes")
                read += n
            }
            return result
        }

        fun record(): Pair<WebVideoRecords.Header, ByteArray> {
            val header = WebVideoRecords.readHeader(bytes(WebVideoRecords.HEADER_SIZE))
            return header to bytes(header.payloadLength)
        }

        fun closedByServer(): Boolean = try {
            input.read() < 0
        } catch (_: IOException) {
            true
        }

        override fun close() = socket.close()
    }

    private fun defaultHeaders() = "Host: 127.0.0.1:$port\r\nOrigin: $origin\r\n"

    private fun get(path: String, headers: String = defaultHeaders()) = Client().use { it.get(path, headers); it.response() }

    private fun post(path: String, body: String, headers: String = defaultHeaders()) =
        Client().use { it.post(path, body, headers); it.response() }

    private fun control(q: Int, events: String = "", s: String = session, c: String = code) =
        post("/control", """{"c":"$c","s":"$s","q":$q,"e":[$events]}""")

    private fun videoPath(s: String = session, c: String = code) = "/video?c=$c&s=$s&codecs=avc1%2Chvc1"

    private fun beginStream() {
        hub.beginStream(owner, 1182, 920, 60, { keyFrameRequests.incrementAndGet() }, { reports += it })
        hub.configure(owner, VideoCodec.H264, avcc)
    }

    private fun openVideo(s: String = session): Client {
        val client = Client()
        client.get(videoPath(s))
        val response = client.response()
        assertEquals(200, response.status)
        return client
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            if (System.nanoTime() > deadline) throw AssertionError(what)
            Thread.sleep(10)
        }
    }

    @Test fun helloNamesTheAppWithCorsAndHygieneHeaders() {
        val response = get("/hello")
        assertEquals(200, response.status)
        assertEquals("""{"app":"TiPlay","protocol":1}""", response.text)
        assertEquals("application/json", response.headers["content-type"])
        assertEquals(origin, response.headers["access-control-allow-origin"])
        assertEquals("Origin", response.headers["vary"])
        assertEquals("no-store", response.headers["cache-control"])
        assertEquals("nosniff", response.headers["x-content-type-options"])

        val withoutOrigin = get("/hello", "Host: 127.0.0.1:$port\r\n")
        assertEquals(200, withoutOrigin.status)
        assertNull(withoutOrigin.headers["access-control-allow-origin"])
        assertEquals("no-store", withoutOrigin.headers["cache-control"])
    }

    @Test fun preflightAllowsSimpleRequestsAndPrivateNetworkAccess() {
        val response = Client().use {
            it.send(
                "OPTIONS /video?c=1 HTTP/1.1\r\n${defaultHeaders()}Access-Control-Request-Method: GET\r\n" +
                    "Access-Control-Request-Private-Network: true\r\n\r\n",
            )
            it.response()
        }
        assertEquals(204, response.status)
        assertEquals("GET, POST, OPTIONS", response.headers["access-control-allow-methods"])
        assertEquals("Content-Type", response.headers["access-control-allow-headers"])
        assertEquals("600", response.headers["access-control-max-age"])
        assertEquals("true", response.headers["access-control-allow-private-network"])
        assertEquals("0", response.headers["content-length"])
        assertEquals(origin, response.headers["access-control-allow-origin"])

        val plain = Client().use {
            it.send("OPTIONS /control HTTP/1.1\r\n${defaultHeaders()}Access-Control-Request-Method: POST\r\n\r\n")
            it.response()
        }
        assertEquals(204, plain.status)
        assertNull(plain.headers["access-control-allow-private-network"])
        val preflightLogs = logs.filter { it.contains("preflight") }
        assertEquals(
            listOf("Browser link: first preflight header names=host,origin,access-control-request-method,access-control-request-private-network"),
            preflightLogs,
        )
    }

    @Test fun hostMustBeAnIpv4LiteralOrLocalhost() {
        for (host in listOf(
            "Host: example.com\r\n", "Host: localhost.example.com\r\n", "Host: [::1]:8080\r\n", "Host: 010.0.0.1\r\n", "",
        )) {
            val response = get("/hello", "${host}Origin: $origin\r\n")
            assertEquals(host, 421, response.status)
            assertEquals("""{"error":"host"}""", response.text)
            assertEquals(origin, response.headers["access-control-allow-origin"])
        }
        assertEquals(200, get("/hello", "Host: 100.109.220.253:8080\r\n").status)
        assertEquals(200, get("/hello", "Host: 127.0.0.1\r\n").status)
        // Protocol §7.2: a browser on the phone itself opens the page at localhost.
        assertEquals(200, get("/hello", "Host: localhost:8080\r\n").status)
        assertEquals(200, get("/hello", "Host: localhost\r\n").status)
        assertFalse(BrowserLinkServer.isLocalhost("localhost:"))
        assertFalse(BrowserLinkServer.isLocalhost("localhost:0"))
        assertFalse(BrowserLinkServer.isLocalhost("localhostx"))
        assertFalse(BrowserLinkServer.isLocalhost("my.localhost"))
        assertTrue(BrowserLinkServer.isIpv4Host("0.0.0.0:1"))
        assertFalse(BrowserLinkServer.isIpv4Host("256.0.0.1"))
        assertFalse(BrowserLinkServer.isIpv4Host("1.2.3"))
        assertFalse(BrowserLinkServer.isIpv4Host("1.2.3.4:0"))
        assertFalse(BrowserLinkServer.isIpv4Host("1.2.3.4:65536"))
        assertFalse(BrowserLinkServer.isIpv4Host("1.2.3.4:"))
        assertFalse(BrowserLinkServer.isIpv4Host("1.2.3.٤"))
    }

    @Test fun unknownPathsAndWrongMethodsAreRefused() {
        val missing = get("/nope")
        assertEquals(404, missing.status)
        assertEquals("""{"error":"not-found"}""", missing.text)
        val method = post("/hello", "")
        assertEquals(405, method.status)
        assertEquals("""{"error":"method"}""", method.text)
        assertEquals("GET, OPTIONS", method.headers["allow"])
        assertEquals(405, get("/control").status)
        assertEquals(405, get("/bye").status)
        assertEquals(405, post("/video", "").status)
    }

    @Test fun malformedRequestsGet400AndAClosedConnection() {
        for (request in listOf(
            "GARBAGE\r\n\r\n",
            "GET /hello HTTP/2\r\n${defaultHeaders()}\r\n",
            "GET http://127.0.0.1/hello HTTP/1.1\r\n${defaultHeaders()}\r\n",
            "POST /control HTTP/1.1\r\n${defaultHeaders()}\r\n",
            "POST /control HTTP/1.1\r\n${defaultHeaders()}Transfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            "POST /control HTTP/1.1\r\n${defaultHeaders()}Content-Length: 70000\r\n\r\n",
            "GET /hello HTTP/1.1\r\n${defaultHeaders()}X-Long: ${"a".repeat(9_000)}\r\n\r\n",
            "GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\nHost: 127.0.0.1\r\n\r\n",
            "GET /hello HTTP/1.1\r\n${defaultHeaders()} folded\r\n\r\n",
            "GET /video?c=%zz HTTP/1.1\r\n${defaultHeaders()}\r\n",
        )) {
            Client().use {
                it.send(request)
                val response = it.response()
                assertEquals(request.take(40), 400, response.status)
                assertEquals("""{"error":"bad-request"}""", response.text)
                assertEquals("close", response.headers["connection"])
                assertTrue(it.closedByServer())
            }
        }
    }

    @Test fun malformedBodiesGet400OnAKeptAliveConnection() {
        Client().use {
            for (body in listOf("not json", "[]", """{"c":"$code","s":"short","q":1,"e":[]}""",
                """{"c":"$code","s":"$session","e":[]}""", """{"c":"$code","s":"$session","q":1}""",
                """{"c":"$code","s":"$session","q":1.5,"e":[]}""")) {
                it.post("/control", body)
                val response = it.response()
                assertEquals(body, 400, response.status)
                assertEquals("""{"error":"bad-request"}""", response.text)
            }
            it.get("/video?c=$code&s=bad")
            assertEquals(400, it.response().status)
            it.post("/bye", "{}")
            assertEquals(400, it.response().status)
        }
    }

    @Test fun wrongCodesAreRefusedAndRepeatedOnesThrottleEveryone() {
        assertEquals(401, control(1, c = "000000").status)
        pairingCode = null
        val off = control(1)
        assertEquals(401, off.status)
        assertEquals("""{"error":"code"}""", off.text)
        pairingCode = code
        repeat(4) { assertEquals(401, control(1, c = "999999").status) }
        assertTrue(logs.any { it == "Browser link: pairing locked after repeated wrong codes" })
        val throttled = control(1)
        assertEquals(429, throttled.status)
        assertEquals("""{"error":"throttled"}""", throttled.text)
        assertEquals(429, get(videoPath()).status)
        assertEquals(429, post("/bye", """{"c":"$code","s":"$session"}""").status)
    }

    @Test fun controlAppliesEventsInOrderOnceAndAnswersWithTheStatus() {
        val events = """{"k":"t","p":[[1,0.25,0.5,1]]},{"k":"vp","w":1182,"h":920,"cw":773,"ch":601,"dpr":1.53},""" +
            """{"k":"key","n":"home"},{"k":"st","v":{"fps":59.9,"decoder":"hw"}},{"k":"fit"},{"k":"kf"}"""
        val response = control(1, events)
        assertEquals(200, response.status)
        assertEquals("application/json", response.headers["content-type"])
        val body = response.json
        assertEquals(1L, body["q"])
        assertEquals("streaming", body["state"])
        assertEquals("avc1.640028", body["codec"])
        assertEquals(mapOf("w" to 1182L, "h" to 920L), body["display"])
        assertEquals(mapOf("w" to 1182L, "h" to 920L), body["viewport"])
        assertEquals(false, body["fit"])
        assertEquals("ok", body["touch"])
        val phone = body["phone"] as Map<*, *>
        assertEquals(60L, phone["rx"])
        assertTrue(phone["video"] is Map<*, *>)
        assertTrue(phone["link"] is Map<*, *>)

        assertEquals(listOf(listOf(AirPlayContact(0, 0.0, 0.0, false), AirPlayContact(1, 0.25, 0.5, true))), touches)
        assertEquals(listOf(BrowserViewport(1182, 920, 773.0, 601.0, 1.53)), viewports)
        assertEquals(listOf("home"), keys)
        assertEquals(listOf(mapOf("fps" to 59.9, "decoder" to "hw")), stats)
        assertEquals(1, fits.get())

        // The same or an older sequence number is answered but not applied again.
        assertEquals(1L, control(1, events).json["q"])
        assertEquals(1L, control(0, events).json["q"])
        assertEquals(1, touches.size)
        assertEquals(1, fits.get())
        assertEquals(2L, control(2, """{"k":"t","p":[]}""").json["q"])
        assertEquals(2, touches.size)
    }

    @Test fun anOverlappingLaterBatchWaitsForTheEarlierOne() {
        // The page's 3 s timeout can send batch 2 while batch 1 is still being applied on another connection.
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        beforeViewport = { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        Client().use { first ->
            first.post("/control", """{"c":"$code","s":"$session","q":1,"e":[""" +
                """{"k":"vp","w":1182,"h":920,"cw":773,"ch":601,"dpr":1.53},{"k":"t","p":[[0,0.5,0.5,1]]}]}""")
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Client().use { second ->
                second.post("/control", """{"c":"$code","s":"$session","q":2,"e":[{"k":"t","p":[[0,0.5,0.5,0]]}]}""")
                Thread.sleep(200) // time for batch 2 to overtake if it could
                release.countDown()
                assertEquals(200, first.response().status)
                assertEquals(200, second.response().status)
            }
        }
        assertEquals("The finger lifts after it went down", listOf(true, false), touches.map { it[0].down })
    }

    @Test fun invalidEventsAreSkippedWithoutFailingTheBatch() {
        val events = listOf(
            """{"k":"t","p":[[0,0.1,0.1,1],[1,0.2,0.2,1],[0,0.3,0.3,1]]}""",
            """{"k":"t","p":[[2,0.1,0.1,1]]}""",
            """{"k":"t","p":[[0,1.5,0.1,1]]}""",
            """{"k":"t","p":[[0,0.1,0.1,2]]}""",
            """{"k":"t","p":"x"}""",
            """{"k":"vp","w":100,"h":920}""",
            """{"k":"vp","w":4000,"h":900}""",
            """{"k":"vp","w":1182}""",
            """{"k":"key","n":"power"}""",
            """{"k":"st","v":{"nested":{"a":1}}}""",
            """{"k":"st","v":{"long":"${"x".repeat(65)}"}}""",
            """{"k":"st","v":{${(1..60).joinToString(",") { "\"field$it\":\"${"y".repeat(30)}\"" }}}}""",
            """{"k":"unknown"}""", "7", "null",
            """{"k":"t","p":[[0,0.5,0.5,true]]}""",
        ).joinToString(",")
        assertEquals(200, control(1, events).status)
        assertEquals(listOf(listOf(AirPlayContact(0, 0.5, 0.5, true), AirPlayContact(1, 0.0, 0.0, false))), touches)
        assertTrue(viewports.isEmpty())
        assertTrue(keys.isEmpty())
        assertTrue(stats.isEmpty())
    }

    @Test fun fitIsOfferedWhileASessionRunsOnAnotherCanvas() {
        status = status.copy(viewport = BrowserSize(1280, 720))
        assertEquals(true, control(1).json["fit"])
        status = status.copy(state = BrowserLinkStatus.State.IDLE, touchAvailable = false)
        val idle = control(2).json
        assertEquals(false, idle["fit"])
        assertEquals("idle", idle["state"])
        assertEquals("unavailable", idle["touch"])
    }

    @Test fun keepAliveServesSeveralRequestsOnOneSocket() {
        Client().use {
            it.get("/hello")
            assertEquals(200, it.response().status)
            it.post("/control", """{"c":"$code","s":"$session","q":1,"e":[]}""")
            assertEquals(200, it.response().status)
            it.get("/nope")
            assertEquals(404, it.response().status)
            it.send("GET /hello HTTP/1.1\r\n${defaultHeaders()}Connection: close\r\n\r\n")
            val last = it.response()
            assertEquals("close", last.headers["connection"])
            assertTrue(it.closedByServer())
        }
    }

    @Test fun videoStreamsConfigKeyFrameFramesHeartbeatAndEnd() {
        beginStream()
        Client().use { video ->
            video.get(videoPath())
            val head = video.response()
            assertEquals(200, head.status)
            assertEquals("application/octet-stream", head.headers["content-type"])
            assertEquals("close", head.headers["connection"])
            assertNull(head.headers["content-length"])
            assertNull(head.headers["transfer-encoding"])
            assertEquals(origin, head.headers["access-control-allow-origin"])
            assertEquals("Subscribing forces a key-frame request", 1, keyFrameRequests.get())

            val (configHeader, config) = video.record()
            assertEquals(WebVideoRecords.Header(config.size, 1, 0, 1, 1, 0, 1, 0), configHeader)
            assertTrue(String(config).startsWith("""{"codec":"avc1.640028","width":1182,"height":920,"fps":60"""))

            hub.frame(owner, delta, 1_000_000_000, System.nanoTime()) // skipped: the stream starts at a key frame
            hub.frame(owner, idr, 1_016_666_667, System.nanoTime())
            hub.frame(owner, delta, 1_033_333_333, System.nanoTime())
            val (keyHeader, key) = video.record()
            assertEquals(WebVideoRecords.Header(parameterSets.size + idr.size, 2, 5, 1, 1, 1, 1, 16_666), keyHeader)
            assertArrayEquals(parameterSets + idr, key)
            val (deltaHeader, deltaPayload) = video.record()
            assertEquals(WebVideoRecords.Header(delta.size, 2, 0, 1, 1, 2, 1, 33_333), deltaHeader)
            assertArrayEquals(delta, deltaPayload)

            val idleStart = System.nanoTime()
            val (heartbeat, _) = video.record()
            assertEquals(WebVideoRecords.KIND_HEARTBEAT, heartbeat.kind)
            assertTrue(System.nanoTime() - idleStart > 800_000_000)

            server.stop()
            val (end, reason) = video.record()
            assertEquals(WebVideoRecords.KIND_END, end.kind)
            assertEquals("stopped", String(reason))
            assertTrue(video.closedByServer())
        }
        assertTrue(logs.contains("Browser link: video stream opened codecs=avc1,hvc1"))
        assertTrue(logs.contains("Browser link: video stream ended reason=stopped records=5"))
    }

    @Test fun aSecondVideoFromTheSameSessionReplacesTheFirst() {
        beginStream()
        openVideo().use { first ->
            assertEquals(WebVideoRecords.KIND_CONFIG, first.record().first.kind)
            openVideo().use { second ->
                val (end, reason) = first.record()
                assertEquals(WebVideoRecords.KIND_END, end.kind)
                assertEquals("replaced", String(reason))
                assertTrue(first.closedByServer())
                assertEquals(WebVideoRecords.KIND_CONFIG, second.record().first.kind)
                hub.frame(owner, idr, 0, System.nanoTime())
                assertEquals(WebVideoRecords.KIND_FRAME, second.record().first.kind)
            }
        }
    }

    @Test fun theLatestSessionWinsAndTheReplacedOneIsRefused() {
        beginStream()
        assertEquals(200, control(5, """{"k":"t","p":[[0,0.5,0.5,1]]}""").status)
        openVideo(session).use { video ->
            video.record()
            assertEquals("The new session's sequence numbers start fresh", 1L, control(1, s = other).json["q"])
            val (end, reason) = video.record()
            assertEquals(WebVideoRecords.KIND_END, end.kind)
            assertEquals("replaced", String(reason))
            assertTrue(video.closedByServer())
        }
        assertEquals("The replaced session's fingers are released", emptyList<AirPlayContact>(), touches.last())
        val refused = control(6, s = session)
        assertEquals(409, refused.status)
        assertEquals("""{"error":"replaced"}""", refused.text)
        assertEquals(409, get(videoPath(session)).status)
        assertEquals(409, post("/bye", """{"c":"$code","s":"$session"}""").status)
        assertEquals(2L, control(2, s = other).json["q"])
        assertTrue(logs.contains("Browser link: a new page session replaced the previous one"))
    }

    @Test fun decodedReportsTheFirstRenderedFrameOncePerEpoch() {
        beginStream()
        assertEquals(200, control(1, """{"k":"dec","ep":1},{"k":"dec","ep":1}""").status)
        assertEquals(200, control(2, """{"k":"dec","ep":1},{"k":"dec","ep":7}""").status)
        assertEquals(listOf("first frame rendered"), reports)
    }

    @Test fun pageKeyFrameRequestsAreRateLimited() {
        beginStream()
        control(1, """{"k":"kf"},{"k":"kf"}""")
        control(2, """{"k":"kf"}""")
        assertEquals(1, keyFrameRequests.get())
    }

    @Test fun byeReleasesTouchesAndEndsTheVideo() {
        beginStream()
        control(1, """{"k":"t","p":[[0,0.5,0.5,1]]}""")
        openVideo().use { video ->
            video.record()
            val bye = post("/bye", """{"c":"$code","s":"$session"}""")
            assertEquals(204, bye.status)
            assertEquals(origin, bye.headers["access-control-allow-origin"])
            assertEquals(emptyList<AirPlayContact>(), touches.last())
            val (end, reason) = video.record()
            assertEquals(WebVideoRecords.KIND_END, end.kind)
            assertEquals("bye", String(reason))
        }
        assertEquals("A session may come back after its bye", 200, control(2).status)
    }

    @Test fun heldFingersAreReleasedWhenThePageGoesQuiet() {
        control(1, """{"k":"t","p":[[0,0.5,0.5,1]]}""")
        eventually("fingers released") { touches.size == 2 && touches.last().isEmpty() }
    }

    @Test fun theNinthConnectionGetsBusy() {
        val open = List(BrowserLinkServer.MAX_CONNECTIONS) { Client() }
        try {
            open.forEach { it.get("/hello"); assertEquals(200, it.response().status) }
            Client().use {
                val busy = it.response()
                assertEquals(503, busy.status)
                assertEquals("""{"error":"busy"}""", busy.text)
                assertTrue(it.closedByServer())
            }
            open.first().close()
            eventually("a slot frees up") { runCatching { get("/hello").status == 200 }.getOrDefault(false) }
        } finally {
            open.forEach { it.close() }
        }
    }

    @Test fun stopIsPromptClosesEverythingAndIsIdempotent() {
        beginStream()
        val idle = Client()
        idle.get("/hello")
        idle.response()
        val video = openVideo()
        video.record()
        val started = System.nanoTime()
        server.stop()
        assertTrue("stop took too long", System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(1_000))
        assertTrue(idle.closedByServer())
        assertEquals(WebVideoRecords.KIND_END, video.record().first.kind)
        assertTrue(video.closedByServer())
        server.stop()
        // Rebinding proves the listener is gone. A connect probe can self-connect on an ephemeral port and pass by luck.
        ServerSocket().use {
            it.reuseAddress = true
            it.bind(InetSocketAddress("127.0.0.1", port))
        }
        idle.close()
        video.close()
        assertEquals(1, logs.count { it == "Browser link: stopped" })
    }

    @Test fun aBindFailureIsReportedInsteadOfThrown() {
        val second = BrowserLinkServer(hub, callbacks, "127.0.0.1", port)
        val result = second.start()
        assertTrue(result.exceptionOrNull() is BindException)
        assertTrue(logs.any { it.startsWith("Browser link: cannot listen on port $port") })
        second.stop()
        assertTrue(server.start().isFailure)
    }

    @Test fun aFailedBindKeepsNoDescriptor() {
        // The app may retry while the port is taken; each attempt used to leave its socket open until GC.
        val descriptors = File("/proc/self/fd")
        assumeTrue("needs /proc/self/fd", descriptors.isDirectory)
        val before = descriptors.list()!!.size
        repeat(20) { assertTrue(BrowserLinkServer(hub, callbacks, "127.0.0.1", port).start().isFailure) }
        val after = descriptors.list()!!.size
        assertTrue("descriptors grew from $before to $after", after - before < 5)
    }

    @Test fun diagnosticsNeverCarryTheCodeTheSessionOrAddresses() {
        beginStream()
        control(1, """{"k":"t","p":[[0,0.5,0.5,1]]}""")
        openVideo().use { it.record() }
        control(2, s = other)
        control(3, c = "000000")
        post("/bye", """{"c":"$code","s":"$other"}""")
        Client().use { it.send("OPTIONS /video HTTP/1.1\r\n${defaultHeaders()}\r\n"); it.response() }
        server.stop()
        assertTrue(logs.size > 5)
        for (line in logs) {
            assertFalse(line, line.contains(code) || line.contains(session) || line.contains(other))
            assertFalse(line, line.contains("127.0.0.1") || line.contains(origin))
        }
    }
}
