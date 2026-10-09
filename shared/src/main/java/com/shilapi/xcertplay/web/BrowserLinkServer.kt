package com.shilapi.xcertplay.web

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The phone side of the browser link (protocol v1): a small HTTP/1.1 server for the TiPlay page.
 *
 * - `GET /hello` names the app and protocol.
 * - `GET /video` streams [WebVideoRecords] from [hub] as a close-delimited body (no length, no chunking).
 * - `POST /control` applies touch, viewport and key events in order and answers with the link status.
 * - `POST /bye` ends a session.
 * - `GET /` and `GET /play` redirect to `/play/`; `GET /play/` and `GET /play/<file>` serve the bundled page from
 *   [assets] (protocol §7.2), without a pairing code, for browsers that cannot use the public HTTPS page.
 *
 * The page usually comes from a public HTTPS origin, so every response carries CORS headers and preflights are
 * answered, including Private Network Access. Only an IPv4 literal or `localhost` is accepted as `Host`
 * (DNS-rebinding defence); any origin is accepted because the pairing code is the access control
 * ([BrowserPairingGate]).
 *
 * A server starts once; [stop] closes every connection.
 */
class BrowserLinkServer(
    private val hub: WebVideoHub,
    private val callbacks: Callbacks,
    private val bindAddress: String = "0.0.0.0",
    private val port: Int = DEFAULT_PORT,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    /**
     * The bundled page's files by name (`index.html`, `app.js`, …; never a path), or null when the phone serves no
     * page. Called on server threads with a validated name; null or an exception answers `404`.
     */
    private val assets: ((String) -> ByteArray?)? = null,
) {
    /** What the server needs from the app. Called on server threads; implementations must not block. */
    interface Callbacks {
        /** The pairing code, or null while pairing is off (every authenticated request is then refused). */
        fun code(): String?
        fun status(): BrowserLinkStatus
        /** Both touch slots in order, or an empty list to release every finger; false when no CarPlay session took it. */
        fun onTouch(contacts: List<AirPlayContact>): Boolean
        /** A valid viewport report. */
        fun onViewport(viewport: BrowserViewport)
        /** The driver pressed "Apply and reconnect": renegotiate CarPlay to the last viewport. */
        fun onFit()
        /** `home`, `back` or `siri`. */
        fun onKey(name: String)
        /** A flat stats snapshot from the page: numbers, booleans and short strings. */
        fun onBrowserStats(stats: Map<String, Any?>)
        /** Diagnostics for the session log. The server never puts codes, session ids or addresses in them. */
        fun log(message: String)
    }

    private class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val keepAlive: Boolean,
    )

    private class Response(
        val status: Int,
        val type: String? = null,
        val body: ByteArray = ByteArray(0),
        val headers: List<Pair<String, String>> = emptyList(),
    )

    private class BadRequest(message: String) : Exception(message)

    private class VideoStream(val session: String, val socket: Socket, val subscription: WebVideoSubscription) {
        @Volatile var writingSince: Long? = null
        val finished = CountDownLatch(1)
        fun stalled(now: Long) = writingSince?.let { now - it > BrowserPairingGate.STALE_MILLIS } == true
    }

    private val running = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<Socket>()
    private val videos = ConcurrentHashMap.newKeySet<VideoStream>()
    private val preflightLogged = AtomicBoolean(false)
    private val performance = VideoPerformance()
    private val gate = BrowserPairingGate(callbacks::code)
    private val touch = BrowserTouchSlots(callbacks::onTouch)
    private val lock = Any()
    /** Held while a `/control` batch is admitted and applied, so two overlapping batches never interleave. */
    private val applying = Any()
    private var video: VideoStream? = null
    private var appliedSession: String? = null
    private var appliedSequence: Long? = null
    @Volatile private var server: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    private var watchdog: ScheduledThreadPoolExecutor? = null

    /** The bound port, or -1 before [start]. */
    val localPort: Int get() = server?.localPort ?: -1

    /** Binds and starts serving; the result holds the bound port, or the bind failure (for example, port in use). */
    fun start(): Result<Int> {
        if (!started.compareAndSet(false, true)) return Result.failure(IllegalStateException("already started"))
        val bound = ServerSocket()
        try {
            bound.reuseAddress = true
            bound.bind(InetSocketAddress(InetAddress.getByName(bindAddress), port), BACKLOG)
        } catch (error: IOException) {
            // The socket already holds a descriptor; a failed bind does not release it.
            closeQuietly(bound)
            callbacks.log("Browser link: cannot listen on port $port (${error.javaClass.simpleName})")
            return Result.failure(error)
        }
        server = bound
        pool = ThreadPoolExecutor(MAX_CONNECTIONS, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), threads("browser-link"))
            .apply { allowCoreThreadTimeOut(true) }
        watchdog = ScheduledThreadPoolExecutor(1, threads("browser-link-watchdog")).apply {
            scheduleWithFixedDelay(::tick, WATCHDOG_MILLIS, WATCHDOG_MILLIS, TimeUnit.MILLISECONDS)
        }
        running.set(true)
        Thread({ accept(bound) }, "browser-link-accept").apply { isDaemon = true; start() }
        callbacks.log("Browser link: listening port=${bound.localPort}")
        return Result.success(bound.localPort)
    }

    /** Ends video streams with `stopped`, closes every connection and releases the fingers. Idempotent. */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        closeQuietly(server)
        watchdog?.shutdownNow()
        videos.forEach { it.subscription.end("stopped") }
        // Give each writer a moment to send its end record; a stalled one is cut off below.
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MILLIS)
        videos.forEach { it.finished.await(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS) }
        connections.forEach(::closeQuietly)
        pool?.shutdownNow()
        synchronized(lock) { gate.current?.let(touch::drop) }
        callbacks.log("Browser link: stopped")
    }

    private fun accept(bound: ServerSocket) {
        while (running.get()) {
            val socket = try {
                bound.accept()
            } catch (error: IOException) {
                if (!running.get()) break
                callbacks.log("Browser link: accept failed (${error.javaClass.simpleName})")
                try {
                    Thread.sleep(ACCEPT_RETRY_MILLIS)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }
            if (connections.size >= MAX_CONNECTIONS) {
                performance.count("busy")
                rejectBusy(socket)
                continue
            }
            connections += socket
            try {
                pool!!.execute { serve(socket) }
            } catch (_: RejectedExecutionException) {
                connections -= socket
                closeQuietly(socket)
            }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = IDLE_TIMEOUT_MILLIS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (running.get()) {
                val request = try {
                    readRequest(input) ?: return
                } catch (_: BadRequest) {
                    performance.count("badRequests")
                    write(output, null, refuse(400, "bad-request"), keepAlive = false)
                    lingeringClose(socket, input)
                    return
                }
                if (!handle(request, socket, output)) return
            }
        } catch (_: SocketTimeoutException) {
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        } catch (error: RuntimeException) {
            // An uncaught exception on a pool thread would end the app; drop this connection instead.
            callbacks.log("Browser link: request failed (${error.javaClass.simpleName})")
        } finally {
            connections -= socket
            closeQuietly(socket)
        }
    }

    /** Answers one request; false when the connection must close. */
    private fun handle(request: Request, socket: Socket, output: OutputStream): Boolean {
        val host = request.headers["host"]
        if (!isIpv4Host(host) && !isLocalhost(host)) return respond(output, request, refuse(421, "host"))
        if (request.method == "OPTIONS") return respond(output, request, preflight(request))
        page(request)?.let {
            // A browser loads the page over several connections and keeps them open. Idle, they would count against
            // MAX_CONNECTIONS, and the page's own /video, /control or /bye would then be refused as busy.
            write(output, request, it, keepAlive = false)
            return false
        }
        val method = ROUTES[request.path] ?: return respond(output, request, refuse(404, "not-found"))
        if (request.method != method) {
            return respond(output, request, refuse(405, "method", listOf("Allow" to "$method, OPTIONS")))
        }
        return when (request.path) {
            "/hello" -> respond(output, request, json(200, HELLO))
            "/video" -> streamVideo(request, socket, output)
            "/control" -> respond(output, request, control(request))
            else -> respond(output, request, bye(request))
        }
    }

    private fun preflight(request: Request): Response {
        if (preflightLogged.compareAndSet(false, true)) {
            val names = request.headers.keys.filter { HEADER_NAME_PATTERN.matches(it) }.take(24)
            callbacks.log("Browser link: first preflight header names=${names.joinToString(",")}")
        }
        val headers = mutableListOf(
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "Content-Type",
            "Access-Control-Max-Age" to "600",
        )
        if (request.headers["access-control-request-private-network"].equals("true", ignoreCase = true)) {
            headers += "Access-Control-Allow-Private-Network" to "true"
        }
        return Response(204, headers = headers)
    }

    /** The bundled page (protocol §7.2), or null when [request] is not for it. No pairing code: the files are public. */
    private fun page(request: Request): Response? {
        val path = request.path
        if (path != "/" && path != "/play" && !path.startsWith(PAGE_PREFIX)) return null
        if (request.method != "GET") return refuse(405, "method", listOf("Allow" to "GET, OPTIONS"))
        val loader = assets ?: return refuse(404, "not-found")
        if (path == "/" || path == "/play") return Response(302, headers = listOf("Location" to PAGE_PREFIX))
        // One plain file name: no separators, no dot segments, no escapes, so nothing can leave the page directory.
        val name = path.substring(PAGE_PREFIX.length).ifEmpty { "index.html" }
        if (!PAGE_FILE_PATTERN.matches(name) || ".." in name) return refuse(404, "not-found")
        val type = PAGE_TYPES[name.substringAfterLast('.')] ?: return refuse(404, "not-found")
        val body = try {
            loader(name)
        } catch (_: Exception) {
            null
        } ?: return refuse(404, "not-found")
        performance.count("pageFiles")
        return Response(200, type, body, listOf("Cache-Control" to "no-cache", "Content-Security-Policy" to PAGE_POLICY))
    }

    private fun streamVideo(request: Request, socket: Socket, output: OutputStream): Boolean {
        val session = request.query["s"]?.takeIf(BrowserPairingGate::isSessionId)
            ?: return respond(output, request, refuse(400, "bad-request"))
        var refusal: Response? = null
        val stream = synchronized(lock) {
            refusal = admitLocked(request.query["c"], session)
            if (refusal != null) return@synchronized null
            VideoStream(session, socket, hub.subscribe()).also {
                video = it
                videos += it
            }
        } ?: return respond(output, request, refusal!!)
        val codecs = request.query["codecs"].orEmpty().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in ".,-" }.take(64)
        var reason = "closed"
        var records = 0L
        try {
            socket.sendBufferSize = VIDEO_SEND_BUFFER
            output.write(head(200, request, listOf("Content-Type" to "application/octet-stream", "Connection" to "close")))
            callbacks.log("Browser link: video stream opened codecs=$codecs")
            var buffer = ByteArray(INITIAL_RECORD_BUFFER)
            var lastWrite = nowMillis()
            while (true) {
                val record = stream.subscription.take(maxOf(1L, HEARTBEAT_MILLIS - (nowMillis() - lastWrite)))
                    ?: if (nowMillis() - lastWrite >= HEARTBEAT_MILLIS) WebVideoRecords.HEARTBEAT else continue
                val size = WebVideoRecords.HEADER_SIZE + record.payloadLength
                if (buffer.size < size) buffer = ByteArray(maxOf(size, buffer.size * 2))
                // One gathered write per record: the payload is copied once, into this buffer.
                WebVideoRecords.write(record, buffer)
                val writeStart = System.nanoTime()
                stream.writingSince = nowMillis()
                output.write(buffer, 0, size)
                stream.writingSince = null
                performance.timing("write", System.nanoTime() - writeStart)
                if (record.kind == WebVideoRecords.KIND_FRAME) performance.timing("queue", hub.now() - record.queuedAtNanos)
                performance.count(RECORD_NAMES[record.kind] ?: "records")
                records++
                lastWrite = nowMillis()
                if (record.kind == WebVideoRecords.KIND_END) {
                    reason = String(record.payload, Charsets.UTF_8)
                    break
                }
            }
        } catch (_: IOException) {
            reason = "write failed"
        } finally {
            synchronized(lock) { if (video === stream) video = null }
            videos -= stream
            stream.subscription.close()
            callbacks.log("Browser link: video stream ended reason=$reason records=$records")
            stream.finished.countDown()
        }
        return false
    }

    private fun control(request: Request): Response {
        val body = jsonBody(request) ?: return refuse(400, "bad-request")
        val session = (body["s"] as? String)?.takeIf(BrowserPairingGate::isSessionId) ?: return refuse(400, "bad-request")
        val sequence = body["q"].wholeNumber() ?: return refuse(400, "bad-request")
        val events = body["e"] as? List<*> ?: return refuse(400, "bad-request")
        // A batch whose request outlived the page's timeout can still be applying when the next one arrives;
        // applying both in one step keeps a later touch-up from landing before an earlier touch-down.
        val fresh = synchronized(applying) {
            val fresh = synchronized(lock) {
                admitLocked(body["c"] as? String, session)?.let { return it }
                val fresh = appliedSequence.let { it == null || sequence > it }
                if (fresh) appliedSequence = sequence
                fresh
            }
            // A repeated or late batch is answered but not applied again.
            if (fresh) events.forEach { applyEvent(session, it) }
            fresh
        }
        performance.count(if (fresh) "control" else "controlRepeated")
        return json(200, WebJson.write(status(synchronized(lock) { appliedSequence })))
    }

    private fun bye(request: Request): Response {
        val body = jsonBody(request) ?: return refuse(400, "bad-request")
        val session = (body["s"] as? String)?.takeIf(BrowserPairingGate::isSessionId) ?: return refuse(400, "bad-request")
        synchronized(lock) {
            when (gate.leave(body["c"] as? String, session, nowMillis())) {
                is BrowserPairingGate.Decision.Accepted -> {
                    touch.drop(session)
                    video?.takeIf { it.session == session }?.subscription?.end("bye")
                }
                BrowserPairingGate.Decision.Unauthorized -> return refuse(401, "code")
                BrowserPairingGate.Decision.Throttled -> return refuse(429, "throttled")
                BrowserPairingGate.Decision.Replaced -> return refuse(409, "replaced")
            }
        }
        callbacks.log("Browser link: session ended by the page")
        return Response(204)
    }

    /** Checks the code and session; null when the request may proceed. A takeover ends the old session's video and touches. */
    private fun admitLocked(code: String?, session: String): Response? {
        val now = nowMillis()
        val current = gate.current
        val liveVideo = video?.let { it.session == current && !it.stalled(now) } == true
        val lockouts = gate.lockouts
        return when (val decision = gate.admit(code, session, now, liveVideo)) {
            is BrowserPairingGate.Decision.Accepted -> {
                decision.replaced?.let { previous ->
                    touch.drop(previous)
                    video?.takeIf { it.session == previous }?.subscription?.end("replaced")
                    callbacks.log("Browser link: a new page session replaced the previous one")
                }
                touch.claim(session)
                if (appliedSession != session) {
                    appliedSession = session
                    appliedSequence = null
                }
                null
            }
            BrowserPairingGate.Decision.Unauthorized -> {
                if (gate.lockouts != lockouts) callbacks.log("Browser link: pairing locked after repeated wrong codes")
                refuse(401, "code")
            }
            BrowserPairingGate.Decision.Throttled -> refuse(429, "throttled")
            BrowserPairingGate.Decision.Replaced -> refuse(409, "replaced")
        }
    }

    private fun applyEvent(session: String, event: Any?) {
        val fields = event as? Map<*, *> ?: return
        when (fields["k"]) {
            "t" -> {
                val contacts = contacts(fields["p"])
                if (contacts == null || !touch.touch(session, contacts, nowMillis())) performance.count("touchRejected")
            }
            "vp" -> {
                val width = fields["w"].wholeInt()
                val height = fields["h"].wholeInt()
                if (width == null || height == null || !BrowserViewport.isValid(width, height)) {
                    performance.count("viewportRejected")
                    return
                }
                callbacks.onViewport(
                    BrowserViewport(width, height, fields["cw"].finite(), fields["ch"].finite(), fields["dpr"].finite()),
                )
            }
            "kf" -> hub.requestKeyFrame()
            "dec" -> fields["ep"].wholeInt()?.let(hub::browserDecoded)
            "fit" -> callbacks.onFit()
            "key" -> (fields["n"] as? String)?.takeIf { it in KEYS }?.let(callbacks::onKey)
            "st" -> {
                val stats = stats(fields["v"])
                if (stats == null) performance.count("statsRejected") else callbacks.onBrowserStats(stats)
            }
        }
    }

    /** `[[slot, x, y, down], …]` as contacts with `id` = slot, or null when malformed. */
    private fun contacts(value: Any?): List<AirPlayContact>? {
        val points = value as? List<*> ?: return null
        if (points.size > 2) return null
        return points.map { point ->
            val fields = point as? List<*> ?: return null
            if (fields.size != 4) return null
            val slot = fields[0].wholeInt() ?: return null
            val x = (fields[1] as? Number)?.toDouble() ?: return null
            val y = (fields[2] as? Number)?.toDouble() ?: return null
            val down = when (fields[3]) {
                1L, true -> true
                0L, false -> false
                else -> return null
            }
            AirPlayContact(slot, x, y, down)
        }
    }

    private fun stats(value: Any?): Map<String, Any?>? {
        val stats = value as? Map<*, *> ?: return null
        val flat = stats.entries.associate { (name, item) ->
            if (name !is String || name.length > 48) return null
            if (item != null && item !is Number && item !is Boolean && !(item is String && item.length <= 64)) return null
            name to item
        }
        return flat.takeIf { WebJson.write(it).length <= MAX_STATS }
    }

    private fun status(sequence: Long?): Map<String, Any?> {
        val status = callbacks.status()
        return linkedMapOf(
            "q" to sequence,
            "state" to status.state.wire,
            "codec" to status.codec,
            "display" to status.display?.let { mapOf("w" to it.width, "h" to it.height) },
            "viewport" to status.viewport?.let { mapOf("w" to it.width, "h" to it.height) },
            "fit" to status.fit,
            "touch" to if (status.touchAvailable) "ok" else "unavailable",
            "phone" to status.phone + mapOf("video" to hub.snapshot(), "link" to performance.snapshot()),
        )
    }

    private fun tick() {
        try {
            val now = nowMillis()
            touch.expire(now)
            videos.filter { it.stalled(now) }.forEach {
                callbacks.log("Browser link: video stream stalled; closing it")
                closeQuietly(it.socket)
            }
        } catch (error: Exception) {
            callbacks.log("Browser link: watchdog failed (${error.javaClass.simpleName})")
        }
    }

    private fun readRequest(input: InputStream): Request? {
        val head = readHead(input) ?: return null
        val lines = head.trimEnd().split('\n').map { it.removeSuffix("\r") }
        val parts = lines.first().split(' ')
        if (parts.size != 3) throw BadRequest("request line")
        val (method, target, version) = parts
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) throw BadRequest("method")
        if (version != "HTTP/1.1" && version != "HTTP/1.0") throw BadRequest("version")
        if (!target.startsWith("/")) throw BadRequest("target")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0 || line[0] == ' ' || line[0] == '\t') throw BadRequest("header")
            val name = line.substring(0, colon).lowercase()
            if (!name.all { it in 'a'..'z' || it in '0'..'9' || it in TOKEN_CHARACTERS }) throw BadRequest("header name")
            val value = line.substring(colon + 1).trim()
            val previous = headers[name]
            if (previous != null && (name == "host" || name == "content-length")) throw BadRequest("duplicate header")
            headers[name] = if (previous == null) value else "$previous, $value"
        }
        if ("transfer-encoding" in headers) throw BadRequest("chunked body")
        val length = headers["content-length"]?.let {
            if (it.length > 9 || !it.isAsciiNumber()) throw BadRequest("length")
            it.toInt()
        }
        if (method == "POST" && length == null) throw BadRequest("length required")
        if ((length ?: 0) > MAX_BODY) throw BadRequest("body too large")
        val body = ByteArray(length ?: 0)
        var read = 0
        while (read < body.size) {
            val count = input.read(body, read, body.size - read)
            if (count < 0) throw EOFException("body ended early")
            read += count
        }
        val question = target.indexOf('?')
        val path = if (question < 0) target else target.substring(0, question)
        val query = if (question < 0) emptyMap() else target.substring(question + 1).split('&')
            .filter { it.isNotEmpty() }
            .associate {
                val equals = it.indexOf('=')
                if (equals < 0) decode(it) to "" else decode(it.substring(0, equals)) to decode(it.substring(equals + 1))
            }
        val connection = headers["connection"].orEmpty().split(',').map { it.trim().lowercase() }
        return Request(method, path, query, headers, body, keepAlive = version == "HTTP/1.1" && "close" !in connection)
    }

    /** The request line and headers, or null when the client closed between requests. */
    private fun readHead(input: InputStream): String? {
        val buffer = ByteArray(MAX_HEAD)
        var size = 0
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (size == 0) return null
                throw EOFException("request ended early")
            }
            // Blank lines before a request line are allowed (RFC 9112 §2.2).
            if (size == 0 && (next == '\r'.code || next == '\n'.code)) continue
            if (size == MAX_HEAD) throw BadRequest("request head too large")
            buffer[size++] = next.toByte()
            if (next == '\n'.code && endsWithBlankLine(buffer, size)) return String(buffer, 0, size, Charsets.ISO_8859_1)
        }
    }

    private fun endsWithBlankLine(buffer: ByteArray, size: Int): Boolean =
        (size >= 2 && buffer[size - 2] == '\n'.code.toByte()) ||
            (size >= 3 && buffer[size - 2] == '\r'.code.toByte() && buffer[size - 3] == '\n'.code.toByte())

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw BadRequest("query")
    }

    private fun jsonBody(request: Request): Map<*, *>? = try {
        WebJson.parse(String(request.body, Charsets.UTF_8)) as? Map<*, *>
    } catch (_: WebJson.SyntaxException) {
        null
    }

    private fun respond(output: OutputStream, request: Request, response: Response): Boolean {
        write(output, request, response, request.keepAlive)
        return request.keepAlive
    }

    private fun write(output: OutputStream, request: Request?, response: Response, keepAlive: Boolean) {
        val headers = response.headers.toMutableList()
        response.type?.let { headers += "Content-Type" to it }
        headers += "Content-Length" to response.body.size.toString()
        if (!keepAlive) headers += "Connection" to "close"
        output.write(head(response.status, request, headers) + response.body)
    }

    private fun head(status: Int, request: Request?, headers: List<Pair<String, String>>): ByteArray {
        val text = StringBuilder("HTTP/1.1 ").append(status).append(' ').append(REASONS[status]).append("\r\n")
        // Echo only a well-formed Origin; a header value cannot carry CR or LF, but keep it to visible ASCII anyway.
        request?.headers?.get("origin")?.takeIf { origin -> origin.isNotEmpty() && origin.all { it in '!'..'~' } }?.let {
            text.append("Access-Control-Allow-Origin: ").append(it).append("\r\nVary: Origin\r\n")
        }
        // The page's files may be cached but must be revalidated; everything else is never stored.
        if (headers.none { it.first.equals("Cache-Control", ignoreCase = true) }) text.append("Cache-Control: no-store\r\n")
        text.append("X-Content-Type-Options: nosniff\r\n")
        headers.forEach { (name, value) -> text.append(name).append(": ").append(value).append("\r\n") }
        return text.append("\r\n").toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun json(status: Int, body: String) = Response(status, "application/json", body.toByteArray(Charsets.UTF_8))

    private fun refuse(status: Int, word: String, headers: List<Pair<String, String>> = emptyList()) =
        Response(status, "application/json", """{"error":"$word"}""".toByteArray(Charsets.UTF_8), headers)

    /**
     * Closes after an error without reading the rest of the request: half-close, then discard what the client is
     * still sending for a moment, so the close does not reset the connection before the client has read the answer.
     */
    private fun lingeringClose(socket: Socket, input: InputStream) {
        try {
            socket.shutdownOutput()
            socket.soTimeout = LINGER_MILLIS
            val discard = ByteArray(4096)
            var total = 0
            while (total < MAX_BODY) {
                val count = input.read(discard)
                if (count < 0) break
                total += count
            }
        } catch (_: IOException) {
        }
    }

    /** Answers a connection over the limit without reading it; the request is unread, so allow any origin. */
    private fun rejectBusy(socket: Socket) {
        try {
            val body = """{"error":"busy"}"""
            socket.getOutputStream().write(
                ("HTTP/1.1 503 Service Unavailable\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-store\r\n" +
                    "X-Content-Type-Options: nosniff\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n" +
                    "Connection: close\r\n\r\n$body").toByteArray(Charsets.ISO_8859_1),
            )
            socket.shutdownOutput()
        } catch (_: IOException) {
        } finally {
            closeQuietly(socket)
        }
    }

    private fun threads(name: String): (Runnable) -> Thread {
        val count = AtomicInteger()
        return { runnable -> Thread(runnable, "$name-${count.incrementAndGet()}").apply { isDaemon = true } }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        const val MAX_CONNECTIONS = 8
        const val MAX_HEAD = 8 * 1024
        const val MAX_BODY = 64 * 1024
        const val IDLE_TIMEOUT_MILLIS = 15_000
        const val HEARTBEAT_MILLIS = 1_000L
        const val VIDEO_SEND_BUFFER = 64 * 1024
        private const val BACKLOG = 16
        private const val WATCHDOG_MILLIS = 500L
        private const val STOP_GRACE_MILLIS = 250L
        private const val ACCEPT_RETRY_MILLIS = 100L
        private const val LINGER_MILLIS = 500
        private const val INITIAL_RECORD_BUFFER = 64 * 1024
        private const val MAX_STATS = 2 * 1024
        private const val HELLO = """{"app":"TiPlay","protocol":1}"""
        private const val TOKEN_CHARACTERS = "!#$%&'*+-.^_`|~"
        private val HEADER_NAME_PATTERN = Regex("[a-z0-9-]{1,48}")
        private val KEYS = setOf("home", "back", "siri")
        private val ROUTES = mapOf("/hello" to "GET", "/video" to "GET", "/control" to "POST", "/bye" to "POST")
        private const val PAGE_PREFIX = "/play/"
        private val PAGE_FILE_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
        /** The page's file types; any other file is not served, so a stray file in the bundle stays private. */
        internal val PAGE_TYPES = mapOf(
            "html" to "text/html; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "svg" to "image/svg+xml",
            "json" to "application/json",
            "webmanifest" to "application/manifest+json",
        )
        /**
         * The served page's policy: only its own files run. `connect-src http:` lets it reach a phone address the
         * driver typed (CSP cannot express IP ranges); `media-src blob:` is for the MSE fallback's object URL.
         */
        internal const val PAGE_POLICY = "default-src 'self'; connect-src 'self' http:; media-src 'self' blob:; " +
            "img-src 'self' data:; style-src 'self'; script-src 'self'; worker-src 'self'; object-src 'none'; " +
            "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        private val RECORD_NAMES = mapOf(
            WebVideoRecords.KIND_CONFIG to "configRecords",
            WebVideoRecords.KIND_FRAME to "frameRecords",
            WebVideoRecords.KIND_HEARTBEAT to "heartbeats",
            WebVideoRecords.KIND_END to "endRecords",
        )
        private val REASONS = mapOf(
            200 to "OK", 204 to "No Content", 302 to "Found", 400 to "Bad Request", 401 to "Unauthorized", 404 to "Not Found",
            405 to "Method Not Allowed", 409 to "Conflict", 421 to "Misdirected Request", 429 to "Too Many Requests",
            503 to "Service Unavailable",
        )

        /** An IPv4 literal (no leading zeros), optionally with a port. */
        internal fun isIpv4Host(host: String?): Boolean {
            if (host.isNullOrEmpty()) return false
            val colon = host.indexOf(':')
            if (colon >= 0 && !isPort(host.substring(colon + 1))) return false
            val octets = (if (colon < 0) host else host.substring(0, colon)).split('.')
            return octets.size == 4 && octets.all {
                it.length <= 3 && it.isAsciiNumber() && (it == "0" || it[0] != '0') && it.toInt() <= 255
            }
        }

        /** `localhost`, optionally with a port: a browser on the phone itself. No DNS answer can rebind that name. */
        internal fun isLocalhost(host: String?): Boolean {
            if (host == null) return false
            val colon = host.indexOf(':')
            val name = if (colon < 0) host else host.substring(0, colon)
            return name.equals("localhost", ignoreCase = true) && (colon < 0 || isPort(host.substring(colon + 1)))
        }

        private fun isPort(port: String) = port.length <= 5 && port.isAsciiNumber() && port.toInt() in 1..65535
    }
}

private fun String.isAsciiNumber() = isNotEmpty() && all { it in '0'..'9' }

private fun Any?.wholeInt(): Int? = wholeNumber()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

private fun Any?.wholeNumber(): Long? = when (this) {
    is Long -> this
    is Double -> if (isFinite() && this == Math.floor(this) && Math.abs(this) < 9.0e15) toLong() else null
    else -> null
}

private fun Any?.finite(): Double = (this as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: 0.0

private fun closeQuietly(closeable: Closeable?) {
    try {
        closeable?.close()
    } catch (_: IOException) {
    }
}
