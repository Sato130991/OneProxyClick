package com.oneproxyclick.app.bridge

import com.oneproxyclick.app.OneProxyClickApp
import com.oneproxyclick.app.bridge.LocalControlServer.ApiRequest
import com.oneproxyclick.app.bridge.LocalControlServer.ApiResponse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Local control API (127.0.0.1 only):
 *
 * | Method | Path        | Body                          |
 * |--------|-------------|-------------------------------|
 * | GET    | /status     | –                             |
 * | POST   | /start      | `{"port": 8080}` (optional)   |
 * | POST   | /stop       | –                             |
 * | POST   | /intercept  | `{"mode": "on|off|toggle"}`   |
 * | POST   | /filter     | `{"regex": "..."}` ("" = all) |
 *
 * Security layers (127.0.0.1 is reachable by every app *and* by web pages in the phone's browser):
 * 1. `Authorization: Bearer <token>` when "Require token" is on (constant-time compare).
 * 2. Requests carrying an `Origin` header are rejected → a web page can't drive the API (CSRF).
 * 3. `Host` must be 127.0.0.1/localhost → blocks DNS-rebinding attacks.
 */
class ControlApi(private val app: OneProxyClickApp) {

    fun handle(request: ApiRequest): ApiResponse = runBlocking {
        val settings = app.graph.engineSettings.settings.first()
        val port = settings.controlPort

        // 3. Host check (anti DNS rebinding)
        val host = request.headers["host"]?.lowercase()
        val allowedHosts = setOf("127.0.0.1:$port", "localhost:$port", "127.0.0.1", "localhost")
        if (host == null || host !in allowedHosts) {
            return@runBlocking error(403, "bad_host", "Host header must be 127.0.0.1:$port")
        }
        // 2. Browser requests (CSRF) — curl/python/KaliTerm never send Origin
        if (request.headers.containsKey("origin")) {
            return@runBlocking error(403, "browser_forbidden", "Requests from web pages are not allowed")
        }
        // 1. Token
        if (settings.requireToken) {
            val auth = request.headers["authorization"].orEmpty()
            val token = auth.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
            if (!BridgeToken.matches(app, token)) {
                return@runBlocking ApiResponse(
                    401,
                    errorJson("unauthorized", "Missing or wrong 'Authorization: Bearer <token>' (Settings > KaliTerm Bridge)"),
                    mapOf("WWW-Authenticate" to "Bearer realm=\"OneProxyClick\""),
                )
            }
        }

        val body = if (request.body.isBlank()) JSONObject() else try {
            JSONObject(request.body)
        } catch (_: Exception) {
            return@runBlocking error(400, "bad_json", "Body must be a JSON object")
        }

        val routes = mapOf("/status" to "GET", "/start" to "POST", "/stop" to "POST", "/intercept" to "POST", "/filter" to "POST")
        val path = request.path.trimEnd('/').ifEmpty { "/" }
        if (path == "/") return@runBlocking ApiResponse(200, help(port).toString())
        val expected = routes[path] ?: return@runBlocking error(404, "not_found", "Unknown endpoint $path")
        if (request.method != expected) {
            return@runBlocking ApiResponse(405, errorJson("method_not_allowed", "Use $expected $path"), mapOf("Allow" to expected))
        }

        val outcome = when (path) {
            "/status" -> return@runBlocking ApiResponse(200, JSONObject().put("ok", true).put("status", ProxyController.status()).toString())
            "/start" -> {
                val requested = if (body.has("port")) body.optInt("port", -1) else null
                ProxyController.start(app, requested)
            }
            "/stop" -> ProxyController.stop(app)
            "/intercept" -> ProxyController.intercept(body.optString("mode", "toggle"))
            "/filter" -> {
                if (!body.has("regex")) return@runBlocking error(400, "missing_regex", "Body must contain {\"regex\": \"...\"}")
                ProxyController.filter(body.optString("regex", ""))
            }
            else -> return@runBlocking error(404, "not_found", "Unknown endpoint $path")
        }
        ApiResponse(
            outcome.httpStatus,
            JSONObject()
                .put("ok", outcome.ok)
                .put("code", outcome.code)
                .put("message", outcome.message)
                .put("status", ProxyController.status())
                .toString(),
        )
    }

    private fun error(status: Int, code: String, message: String) = ApiResponse(status, errorJson(code, message))

    private fun errorJson(code: String, message: String) =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun help(port: Int) = JSONObject().apply {
        put("ok", true)
        put("service", "OneProxyClick control API")
        put("base", "http://127.0.0.1:$port")
        put(
            "endpoints",
            JSONObject()
                .put("GET /status", "tunnel, proxy port, intercept, host filter")
                .put("POST /start", "{\"port\": 8080} optional")
                .put("POST /stop", "stop tunnel + proxy, release held requests")
                .put("POST /intercept", "{\"mode\": \"on|off|toggle\"}")
                .put("POST /filter", "{\"regex\": \"...\"} (\"\" = all hosts)"),
        )
    }
}
