package com.oneproxyclick.app.bridge

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/** JVM tests for the raw HTTP layer of the local control API (no Android APIs involved). */
class LocalControlServerTest {

    private val seen = mutableListOf<LocalControlServer.ApiRequest>()
    private lateinit var server: LocalControlServer
    private var port = 0

    @Before
    fun setUp() {
        server = LocalControlServer { req ->
            synchronized(seen) { seen += req }
            when (req.path) {
                "/boom" -> throw IllegalStateException("kaput \"quoted\"")
                else -> LocalControlServer.ApiResponse(200, """{"ok":true,"echo":${LocalControlServer.jsonString(req.body)}}""")
            }
        }
        port = ServerSocket(0).use { it.localPort }
        server.start(port)
    }

    @After
    fun tearDown() = server.stop()

    private fun raw(request: String): String = Socket().use { s ->
        s.connect(InetSocketAddress("127.0.0.1", port), 2000)
        s.soTimeout = 5000
        s.getOutputStream().apply { write(request.toByteArray(Charsets.UTF_8)); flush() }
        val out = ByteArrayOutputStream()
        s.getInputStream().copyTo(out)
        out.toString("UTF-8")
    }

    private fun status(response: String) = response.substringBefore("\r\n").split(' ')[1].toInt()

    @Test
    fun bindsOnlyToLoopback() {
        assertTrue(server.isRunning)
        assertEquals(port, server.boundPort)
        assertEquals("127.0.0.1", LocalControlServer.LOOPBACK.hostAddress)
    }

    @Test
    fun parsesPostWithJsonBodyAndHeaders() {
        val body = """{"regex": ".*api\\.ejemplo\\.com.*", "ñ": "ü"}"""
        val len = body.toByteArray(Charsets.UTF_8).size
        val resp = raw(
            "POST /filter?x=1 HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nAuthorization: Bearer abc\r\n" +
                "Content-Type: application/json\r\nContent-Length: $len\r\n\r\n$body",
        )
        assertEquals(200, status(resp))
        assertTrue(resp.contains("Content-Type: application/json"))
        assertTrue(resp.contains("Connection: close"))
        val req = synchronized(seen) { seen.single() }
        assertEquals("POST", req.method)
        assertEquals("/filter", req.path)
        assertEquals("x=1", req.query)
        assertEquals("Bearer abc", req.headers["authorization"])
        assertEquals("127.0.0.1:$port", req.headers["host"])
        assertEquals(body, req.body)
    }

    @Test
    fun getWithoutBody() {
        val resp = raw("GET /status HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n")
        assertEquals(200, status(resp))
        assertEquals("", synchronized(seen) { seen.single() }.body)
    }

    @Test
    fun handlerExceptionBecomes500WithValidJson() {
        val resp = raw("GET /boom HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(500, status(resp))
        assertTrue(resp.contains("""kaput \"quoted\""""))
    }

    @Test
    fun malformedRequestIs400() {
        assertEquals(400, status(raw("NONSENSE\r\n\r\n")))
        assertEquals(400, status(raw("POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n")))
        assertEquals(400, status(raw("POST /x HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n")))
    }

    @Test
    fun oversizedBodyIs413() {
        assertEquals(413, status(raw("POST /x HTTP/1.1\r\nContent-Length: 999999\r\n\r\n")))
    }

    @Test
    fun restartOnNewPortAndStop() {
        val other = ServerSocket(0).use { it.localPort }
        server.start(other)
        assertEquals(other, server.boundPort)
        server.stop()
        assertTrue(!server.isRunning)
    }

    @Test
    fun jsonStringEscapes() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", LocalControlServer.jsonString("a\"b\\c\nd\u0001"))
    }
}
