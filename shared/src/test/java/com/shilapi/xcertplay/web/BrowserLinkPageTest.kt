package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/** Protocol §7.2: the phone serves the bundled copy of `site/play/` to browsers that cannot use the HTTPS page. */
class BrowserLinkPageTest {
    private val files = mapOf(
        "index.html" to "<!doctype html><title>TiPlay</title>",
        "app.js" to "export const app = 1;",
        "style.css" to "body{margin:0}",
        "icon.svg" to "<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
        "data.json" to "{}",
        "site.webmanifest" to "{\"name\":\"TiPlay\"}",
        "notes.txt" to "not part of the page",
    ).mapValues { it.value.toByteArray(Charsets.UTF_8) }
    private val loads = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

    private val callbacks = object : BrowserLinkServer.Callbacks {
        override fun code() = "123456"
        override fun status() = BrowserLinkStatus(BrowserLinkStatus.State.IDLE)
        override fun onTouch(contacts: List<AirPlayContact>) = false
        override fun onViewport(viewport: BrowserViewport) = Unit
        override fun onFit() = Unit
        override fun onKey(name: String) = Unit
        override fun onBrowserStats(stats: Map<String, Any?>) = Unit
        override fun log(message: String) { logs += message }
    }

    private val servers = mutableListOf<BrowserLinkServer>()

    @After fun stop() = servers.forEach(BrowserLinkServer::stop)

    private fun serve(assets: ((String) -> ByteArray?)?): Int {
        val server = BrowserLinkServer(WebVideoHub(Executor { it.run() }), callbacks, "127.0.0.1", 0, assets = assets)
        servers += server
        return server.start().getOrThrow()
    }

    private fun serveFiles(): Int = serve { name -> loads += name; files[name] }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
        val text get() = String(body, Charsets.UTF_8)
    }

    private class Client(port: Int) : Closeable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 5_000 }
        private val input = BufferedInputStream(socket.getInputStream())

        fun send(text: String) = socket.getOutputStream().write(text.toByteArray(Charsets.ISO_8859_1))

        fun response(): Response {
            val head = ByteArrayOutputStream()
            while (!head.toString(Charsets.ISO_8859_1.name()).endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) throw IOException("closed before a response")
                head.write(next)
            }
            val lines = head.toString(Charsets.ISO_8859_1.name()).trimEnd().split("\r\n")
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            val length = headers.getValue("content-length").toInt()
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count < 0) throw IOException("body ended early")
                read += count
            }
            return Response(lines[0].split(' ')[1].toInt(), headers, body)
        }

        /** True when the phone closed the connection (end of stream) instead of keeping it open for another request. */
        fun closedByPhone(): Boolean = input.read() < 0

        override fun close() = socket.close()
    }

    private fun get(port: Int, path: String, host: String = "100.109.220.253:8080"): Response = Client(port).use {
        it.send("GET $path HTTP/1.1\r\nHost: $host\r\n\r\n")
        it.response()
    }

    @Test fun theRootAndTheBareDirectoryRedirectToThePage() {
        val port = serveFiles()
        for (path in listOf("/", "/play", "/?from=car", "/play?x=1")) {
            val response = get(port, path)
            assertEquals(path, 302, response.status)
            assertEquals(path, "/play/", response.headers["location"])
            assertEquals("0", response.headers["content-length"])
        }
        assertTrue("A redirect loads no file", loads.isEmpty())
    }

    @Test fun pageFilesAreServedWithoutAPairingCodeWithTypeRevalidationAndPolicy() {
        val port = serveFiles()
        val expected = mapOf(
            "/play/" to ("index.html" to "text/html; charset=utf-8"),
            "/play/index.html" to ("index.html" to "text/html; charset=utf-8"),
            "/play/app.js" to ("app.js" to "text/javascript; charset=utf-8"),
            "/play/app.js?v=2" to ("app.js" to "text/javascript; charset=utf-8"),
            "/play/style.css" to ("style.css" to "text/css; charset=utf-8"),
            "/play/icon.svg" to ("icon.svg" to "image/svg+xml"),
            "/play/data.json" to ("data.json" to "application/json"),
            "/play/site.webmanifest" to ("site.webmanifest" to "application/manifest+json"),
        )
        for ((path, file) in expected) {
            val response = get(port, path)
            assertEquals(path, 200, response.status)
            assertArrayEquals(path, files.getValue(file.first), response.body)
            assertEquals(path, file.second, response.headers["content-type"])
            assertEquals(path, "no-cache", response.headers["cache-control"])
            assertEquals(path, "nosniff", response.headers["x-content-type-options"])
            assertEquals(path, BrowserLinkServer.PAGE_POLICY, response.headers["content-security-policy"])
        }
        val policy = BrowserLinkServer.PAGE_POLICY
        assertTrue(policy, "script-src 'self';" in policy && "media-src 'self' blob:;" in policy && "connect-src 'self' http:;" in policy)
        assertTrue(policy, "'unsafe-inline'" !in policy && "'unsafe-eval'" !in policy)
    }

    @Test fun traversalUnknownTypesAndMissingFilesGet404WithoutReachingTheLoader() {
        val port = serveFiles()
        for (path in listOf(
            "/play/../secret.js", "/play/..%2fsecret.js", "/play/%2e%2e", "/play/..", "/play/.hidden.js",
            "/play/sub/app.js", "/play//app.js", "/play/a\\b.js", "/play/notes.txt", "/play/app", "/play/app.js.",
            "/play/a..b.js", "/play/${"a".repeat(70)}.js", "/playground", "/play.js",
        )) {
            val response = get(port, path)
            assertEquals(path, 404, response.status)
            assertEquals(path, """{"error":"not-found"}""", response.text)
            assertEquals(path, "no-store", response.headers["cache-control"])
        }
        assertTrue("Only valid page names reach the loader: $loads", loads.isEmpty())

        assertEquals(404, get(port, "/play/missing.js").status)
        assertEquals(listOf("missing.js"), loads)
    }

    @Test fun onlyGetIsAllowedOnThePage() {
        val port = serveFiles()
        val response = Client(port).use {
            it.send("POST /play/index.html HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n")
            it.response()
        }
        assertEquals(405, response.status)
        assertEquals("GET, OPTIONS", response.headers["allow"])
        assertTrue(loads.isEmpty())
    }

    @Test fun withoutBundledFilesThereIsNoPage() {
        val port = serve(null)
        assertEquals(404, get(port, "/").status)
        assertEquals(404, get(port, "/play/").status)
        assertEquals(404, get(port, "/play/app.js").status)
        assertEquals(200, get(port, "/hello").status)
    }

    @Test fun aFailingLoaderAnswers404AndKeepsServing() {
        val port = serve { throw IOException("asset store closed") }
        assertEquals(404, get(port, "/play/app.js").status)
        assertEquals(200, get(port, "/hello").status)
    }

    @Test fun thePageKeepsTheHostCheck() {
        val port = serveFiles()
        assertEquals(421, get(port, "/play/", host = "tiplay.example").status)
        assertEquals(200, get(port, "/play/", host = "localhost:8080").status)
        val hello = get(port, "/hello")
        assertEquals("The protocol routes keep no-store", "no-store", hello.headers["cache-control"])
        assertNull(hello.headers["content-security-policy"])
    }

    /**
     * Chromium loads the page over up to six connections and keeps them open, and its uncredentialed /hello, /video,
     * /control and /bye use other connections. Kept open, the page's connections took the 8 connection slots, so the
     * decoder worker's module or the page's /bye was refused as busy (seen in tests/web-e2e/phone-run.mjs).
     */
    @Test fun aPageFileClosesItsConnectionSoTheProtocolRoutesStillGetTheirOwn() {
        val port = serveFiles()
        val pageClients = (1..6).map { Client(port) }
        val linkClients = mutableListOf<Client>()
        try {
            for ((index, client) in pageClients.withIndex()) {
                val path = if (index == 0) "/" else "/play/app.js"
                client.send("GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                val response = client.response()
                assertEquals(path, if (index == 0) 302 else 200, response.status)
                assertEquals(path, "close", response.headers["connection"])
                assertTrue("$path: the phone closes the connection after the page's file", client.closedByPhone())
            }
            repeat(BrowserLinkServer.MAX_CONNECTIONS) {
                val client = Client(port).also(linkClients::add)
                client.send("GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                assertEquals("Connection ${it + 1} after the page loaded", 200, client.response().status)
            }
            linkClients.first().send("GET /hello HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
            assertEquals("The protocol routes keep their connection alive", 200, linkClients.first().response().status)
        } finally {
            (pageClients + linkClients).forEach(Client::close)
        }
    }

    /** Escapes, NUL, non-ASCII bytes, other target forms and oversized heads never reach the loader. */
    @Test fun escapedNulNonAsciiAndOversizedTargetsNeverReachTheLoader() {
        val port = serveFiles()
        for (path in listOf(
            "/play/%2e%2e%2fsecret.js", "/play/%2E%2E/app.js", "/play/%252e%252e%252fsecret.js", "/play/..%252fapp.js",
            "/play/%5c..%5capp.js", "/play/%69ndex.html", "/play/app.js%00.html", "/play/app.js\u0000.html",
            "/play/\u00c3\u00a9.js", "/play/./app.js", "/play/app.js/", "/play/app.js;x", "/play/app.js%20",
            "/PLAY/app.js", "/play/${"a".repeat(7_000)}.js",
        )) {
            val response = get(port, path)
            assertEquals(path.take(48), 404, response.status)
            assertEquals(path.take(48), "no-store", response.headers["cache-control"])
        }
        for (target in listOf("http://127.0.0.1/play/app.js", "play/app.js", "*", "/play/${"a".repeat(9_000)}.js")) {
            val response = Client(port).use {
                it.send("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")
                it.response()
            }
            assertEquals(target.take(48), 400, response.status)
        }
        assertTrue("Only valid page names reach the loader: $loads", loads.isEmpty())
    }

    /** Every file of the real page has a served type, so a new page file cannot silently answer 404 from the phone. */
    @Test fun everyFileOfTheRealPageIsServed() {
        val page = File("../site/play")
        assertTrue(page.absolutePath, page.isDirectory)
        val names = page.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()
        assertTrue(names.toString(), "index.html" in names && "sw.js" in names && "decoder-worker.js" in names)
        val port = serve { name -> File(page, name).takeIf { it.isFile }?.readBytes() }
        for (name in names) {
            val response = get(port, "/play/$name")
            assertEquals(name, 200, response.status)
            assertArrayEquals(name, File(page, name).readBytes(), response.body)
        }
        assertEquals("text/javascript; charset=utf-8", get(port, "/play/sw.js").headers["content-type"])
        assertEquals(File(page, "index.html").readText(), get(port, "/play/").text)
    }
}
