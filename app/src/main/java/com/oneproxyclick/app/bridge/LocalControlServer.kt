package com.oneproxyclick.app.bridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal HTTP/1.1 listener bound **only** to 127.0.0.1 (never 0.0.0.0) for the local control API.
 *
 * Deliberately tiny and dependency-free (java.net only, no Android APIs) so it can be unit-tested
 * on the JVM: one accept thread, a small worker pool, one request per connection
 * (`Connection: close`), bounded header/body sizes and socket timeouts. Routing, auth and JSON
 * live in the [handler].
 */
class LocalControlServer(private val handler: (ApiRequest) -> ApiResponse) {

    data class ApiRequest(
        val method: String,
        val path: String,
        val query: String,
        /** Lower-case header names. */
        val headers: Map<String, String>,
        val body: String,
    )

    data class ApiResponse(val status: Int, val json: String, val extraHeaders: Map<String, String> = emptyMap())

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile var boundPort: Int? = null
        private set

    private val workers: ExecutorService = Executors.newFixedThreadPool(4, daemonFactory("opc-api-worker"))

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Binds 127.0.0.1:[port] (restarting if the port changed). Throws if the port is unavailable. */
    @Synchronized
    fun start(port: Int) {
        if (isRunning && boundPort == port) return
        stop()
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(LOOPBACK, port), 16)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
        serverSocket = socket
        boundPort = socket.localPort
        Thread({ acceptLoop(socket) }, "opc-api-accept").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: SocketException) {
                break // closed by stop()
            } catch (_: IOException) {
                continue
            }
            try {
                workers.execute { serve(client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            val response = try {
                val request = readRequest(BufferedInputStream(socket.getInputStream()))
                if (request == null) {
                    ApiResponse(400, """{"ok":false,"error":"bad_request","message":"Malformed HTTP request"}""")
                } else {
                    try {
                        handler(request)
                    } catch (e: Exception) {
                        ApiResponse(500, """{"ok":false,"error":"internal","message":${jsonString(e.message ?: e.javaClass.simpleName)}}""")
                    }
                }
            } catch (e: PayloadTooLarge) {
                ApiResponse(413, """{"ok":false,"error":"too_large","message":"Request too large"}""")
            } catch (_: IOException) {
                return // client went away / timeout
            }
            runCatching { write(socket, response) }
        }
    }

    // ---- HTTP parsing ----------------------------------------------------------------------

    private class PayloadTooLarge : IOException()

    /** Returns null for a malformed request. */
    private fun readRequest(input: InputStream): ApiRequest? {
        val head = readHead(input) ?: return null
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull()?.split(' ') ?: return null
        if (requestLine.size < 3 || !requestLine[2].startsWith("HTTP/")) return null
        val method = requestLine[0].uppercase()
        val target = requestLine[1]
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) return null
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        if (headers["transfer-encoding"] != null) return null // chunked bodies not supported (curl/python send Content-Length)
        val length = headers["content-length"]?.let { it.toIntOrNull() ?: return null } ?: 0
        if (length < 0) return null
        if (length > MAX_BODY) throw PayloadTooLarge()
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return ApiRequest(
            method = method,
            path = target.substringBefore('?').ifEmpty { "/" },
            query = target.substringAfter('?', ""),
            headers = headers,
            body = String(body, Charsets.UTF_8),
        )
    }

    /** Reads up to and including CRLFCRLF; null on EOF before a full head. */
    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var matched = 0 // progress through \r\n\r\n
        while (true) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            if (out.size() > MAX_HEAD) throw PayloadTooLarge()
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) {
                val bytes = out.toByteArray()
                return String(bytes, 0, bytes.size - 4, Charsets.ISO_8859_1)
            }
        }
    }

    private fun write(socket: Socket, response: ApiResponse) {
        val body = (response.json + "\n").toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(reason(response.status)).append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            response.extraHeaders.forEach { (k, v) -> append(k).append(": ").append(v).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        socket.getOutputStream().apply {
            write(head.toByteArray(Charsets.ISO_8859_1))
            write(body)
            flush()
        }
    }

    companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress("localhost", byteArrayOf(127, 0, 0, 1))
        const val DEFAULT_PORT = 8081
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 64 * 1024
        private const val READ_TIMEOUT_MS = 10_000

        fun reason(code: Int) = when (code) {
            200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Status"
        }

        /** JSON string literal without depending on org.json (keeps this class JVM-testable). */
        fun jsonString(s: String): String = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }

        private fun daemonFactory(name: String): ThreadFactory {
            val n = AtomicInteger()
            return ThreadFactory { r -> Thread(r, "$name-${n.incrementAndGet()}").apply { isDaemon = true } }
        }
    }
}
