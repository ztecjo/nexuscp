package com.ztec.cplay.browser

import android.util.Base64
import android.util.Log
import com.ztec.cplay.LogCarDiagnose
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Minimal HTTP + one WebSocket viewer for the browser mirror. */
internal class MirrorHttpServer(
    private val port: Int,
    private val pin: String,
    private val assetReader: (String) -> ByteArray,
    private val stateProvider: () -> MirrorState,
    private val onViewerChanged: (Boolean) -> Unit,
    private val onPointer: (List<MirrorPointer>) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private val acceptPool = Executors.newCachedThreadPool { r ->
        Thread(r, "nexus-mirror-accept").apply { isDaemon = true }
    }
    private var serverSocket: ServerSocket? = null
    private val viewer = AtomicReference<ViewerSession?>(null)
    private val pendingJpeg = AtomicReference<ByteArray?>(null)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val ss = ServerSocket(port).also { serverSocket = it }
        acceptPool.execute {
            while (running.get()) {
                try {
                    val socket = ss.accept()
                    acceptPool.execute { handleSocket(socket) }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }
        acceptPool.execute { framePumpLoop() }
    }

    fun offerFrame(jpeg: ByteArray) {
        pendingJpeg.set(jpeg)
        viewer.get()?.signalFrame()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        viewer.getAndSet(null)?.closeQuietly()
        onViewerChanged(false)
        acceptPool.shutdownNow()
    }

    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readHttpRequest(input) ?: run {
                socket.close(); return
            }
            val path = request.path.substringBefore('?')
            val query = parseQuery(request.path.substringAfter('?', ""))
            when {
                request.method == "GET" && path == "/mirror" -> {
                    upgradeWebSocket(socket, input, output, request, query)
                }
                request.method == "GET" && (path == "/" || path == "/index.html") -> {
                    writeAsset(output, "index.html", "text/html; charset=utf-8")
                }
                request.method == "GET" && path == "/client.js" -> {
                    writeAsset(output, "client.js", "application/javascript; charset=utf-8")
                }
                request.method == "GET" && path == "/client.css" -> {
                    writeAsset(output, "client.css", "text/css; charset=utf-8")
                }
                else -> writeText(output, 404, "text/plain", "not found")
            }
        } catch (error: Exception) {
            Log.d(LogCarDiagnose.TAG, "mirror socket: ${error.message}")
            runCatching { socket.close() }
        }
    }

    private fun writeAsset(output: BufferedOutputStream, name: String, contentType: String) {
        val body = runCatching { assetReader(name) }.getOrElse {
            writeText(output, 404, "text/plain", "missing $name"); return
        }
        writeBytes(output, 200, contentType, body)
    }

    private fun writeText(output: BufferedOutputStream, code: Int, type: String, body: String) {
        writeBytes(output, code, type, body.toByteArray(Charsets.UTF_8))
    }

    private fun writeBytes(output: BufferedOutputStream, code: Int, type: String, body: ByteArray) {
        val reason = if (code == 200) "OK" else "ERR"
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
        output.close()
    }

    private fun upgradeWebSocket(
        socket: Socket,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        request: HttpRequest,
        query: Map<String, String>,
    ) {
        val provided = query["pin"].orEmpty()
        if (provided != pin) {
            writeText(output, 401, "text/plain", "bad pin")
            return
        }
        val key = request.headers["sec-websocket-key"]
        if (key.isNullOrBlank()) {
            writeText(output, 400, "text/plain", "missing websocket key")
            return
        }
        val existing = viewer.get()
        if (existing != null && existing.isOpen) {
            writeText(output, 409, "application/json", """{"op":"occupied","reason":"viewer already connected"}""")
            return
        }
        val accept = websocketAccept(key)
        val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        output.write(handshake.toByteArray(Charsets.US_ASCII))
        output.flush()
        socket.soTimeout = 0
        val session = ViewerSession(socket, input, output)
        viewer.getAndSet(session)?.closeQuietly()
        onViewerChanged(true)
        session.sendText(stateJson())
        try {
            session.readLoop(
                onText = ::handleClientMessage,
                onClosed = {
                    if (viewer.compareAndSet(session, null)) onViewerChanged(false)
                },
            )
        } finally {
            if (viewer.compareAndSet(session, null)) onViewerChanged(false)
            session.closeQuietly()
        }
    }

    private fun handleClientMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("op")) {
            "heartbeat" -> viewer.get()?.sendText(stateJson())
            "frame-ack" -> viewer.get()?.markAcked()
            "pointer" -> {
                val points = json.optJSONArray("points") ?: JSONArray()
                val mapped = buildList {
                    for (i in 0 until points.length()) {
                        val p = points.optJSONObject(i) ?: continue
                        add(
                            MirrorPointer(
                                slot = p.optInt("slot", 0),
                                nx = p.optDouble("nx", 0.0),
                                ny = p.optDouble("ny", 0.0),
                                pressed = p.optBoolean("pressed", false),
                            ),
                        )
                    }
                }
                onPointer(mapped)
            }
        }
    }

    private fun stateJson(): String {
        val s = stateProvider()
        return JSONObject()
            .put("op", "state")
            .put("w", s.width)
            .put("h", s.height)
            .put("live", s.live)
            .put("phase", s.phase)
            .toString()
    }

    private fun framePumpLoop() {
        while (running.get()) {
            try {
                Thread.sleep(20)
                val session = viewer.get() ?: continue
                if (!session.awaitingAck) {
                    val jpeg = pendingJpeg.getAndSet(null) ?: continue
                    session.sendBinary(jpeg)
                }
            } catch (_: InterruptedException) {
                break
            } catch (error: Exception) {
                Log.d(LogCarDiagnose.TAG, "mirror pump: ${error.message}")
            }
        }
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private fun readHttpRequest(input: BufferedInputStream): HttpRequest? {
        val line = readAsciiLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val h = readAsciiLine(input) ?: break
            if (h.isEmpty()) break
            val idx = h.indexOf(':')
            if (idx > 0) {
                headers[h.substring(0, idx).trim().lowercase()] = h.substring(idx + 1).trim()
            }
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers)
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString(Charsets.US_ASCII.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
        }
        return out.toString(Charsets.US_ASCII.name())
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i < 0) return@mapNotNull null
            pair.substring(0, i) to pair.substring(i + 1)
        }.toMap()
    }

    private fun websocketAccept(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
    }

    private class ViewerSession(
        private val socket: Socket,
        private val input: BufferedInputStream,
        private val output: BufferedOutputStream,
    ) {
        @Volatile
        var isOpen: Boolean = true
            private set

        @Volatile
        var awaitingAck: Boolean = false
            private set

        private val writeLock = Any()
        private val frameSignal = Object()

        fun signalFrame() {
            synchronized(frameSignal) { frameSignal.notifyAll() }
        }

        fun markAcked() {
            awaitingAck = false
        }

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(Charsets.UTF_8))

        fun sendBinary(payload: ByteArray) {
            awaitingAck = true
            sendFrame(0x2, payload)
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            if (!isOpen) return
            synchronized(writeLock) {
                try {
                    val header = ByteArrayOutputStream()
                    header.write(0x80 or opcode)
                    when {
                        payload.size < 126 -> header.write(payload.size)
                        payload.size <= 0xFFFF -> {
                            header.write(126)
                            header.write((payload.size ushr 8) and 0xff)
                            header.write(payload.size and 0xff)
                        }
                        else -> {
                            header.write(127)
                            val len = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(payload.size.toLong()).array()
                            header.write(len)
                        }
                    }
                    output.write(header.toByteArray())
                    output.write(payload)
                    output.flush()
                } catch (_: IOException) {
                    isOpen = false
                }
            }
        }

        fun readLoop(onText: (String) -> Unit, onClosed: () -> Unit) {
            try {
                while (isOpen) {
                    val b1 = input.read()
                    if (b1 < 0) break
                    val b2 = input.read()
                    if (b2 < 0) break
                    val opcode = b1 and 0x0f
                    val masked = (b2 and 0x80) != 0
                    var len = (b2 and 0x7f).toLong()
                    when (len) {
                        126L -> {
                            val b = readExact(2)
                            len = ((b[0].toInt() and 0xff) shl 8 or (b[1].toInt() and 0xff)).toLong()
                        }
                        127L -> {
                            val b = readExact(8)
                            len = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).long
                        }
                    }
                    val mask = if (masked) readExact(4) else null
                    val payload = if (len > 0) readExact(len.toInt()) else ByteArray(0)
                    if (mask != null) {
                        for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                    when (opcode) {
                        0x1 -> onText(String(payload, Charsets.UTF_8))
                        0x8 -> break
                        0x9 -> sendFrame(0xA, payload) // pong
                        0xA -> Unit
                    }
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            } finally {
                isOpen = false
                onClosed()
            }
        }

        private fun readExact(n: Int): ByteArray {
            val buf = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw IOException("eof")
                off += r
            }
            return buf
        }

        fun closeQuietly() {
            isOpen = false
            runCatching { socket.close() }
        }
    }
}
