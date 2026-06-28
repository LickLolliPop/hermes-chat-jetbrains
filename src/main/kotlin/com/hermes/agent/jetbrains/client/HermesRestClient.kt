package com.hermes.agent.jetbrains.client

import com.hermes.agent.jetbrains.model.HermesModelOption
import com.hermes.agent.jetbrains.model.HermesSessionSummary
import com.hermes.agent.jetbrains.model.HermesStatus
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Thin REST client over the Hermes dashboard's HTTP API.
 *
 * Auth model (loopback-only — see web_server.py L183, L244):
 * - All authenticated routes take `Authorization: Bearer <token>`.
 * - The token is either injected via the `HERMES_DASHBOARD_SESSION_TOKEN`
 *   env var when starting the dashboard, or auto-generated per-process
 *   and printed to the dashboard's stdout (we read it via [SessionTokenProvider]).
 *
 * Why java.net.http and not OkHttp: IntelliJ Platform already ships the
 * JDK HttpClient, so this layer has zero added dependencies. OkHttp is
 * reserved for the WebSocket upgrade (see [HermesPtySocket]) because JDK
 * HttpClient's WebSocket API is preview and unstable.
 *
 * All methods are blocking — callers are expected to invoke them off the
 * EDT (ApplicationManager.getApplication().executeOnPooledThread or a
 * coroutine on Dispatchers.IO).
 */
class HermesRestClient(
    private val endpointProvider: () -> URI,
    private val tokenProvider: () -> String?,
) {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1) // uvicorn+FastAPI speak 1.1 by default
        .build()

    private fun authedRequest(path: String): HttpRequest.Builder {
        val base = endpointProvider().toString().trimEnd('/')
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("$base$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("User-Agent", "HermesChat-IntelliJ/0.1.0")
        val token = tokenProvider()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    /**
     * Probe a single endpoint and return the parsed JSON body string, or
     * null if the dashboard isn't reachable. Never throws — the IDE shell
     * should treat unreachable as a normal state, not a crash.
     */
    fun getOrNull(path: String): String? {
        return try {
            val resp = httpClient.send(
                authedRequest(path).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() in 200..299) resp.body() else null
        } catch (_: Exception) {
            // Connect refused, DNS failure, timeout — all are "not running yet".
            null
        }
    }

    fun getStatus(): HermesStatus? {
        val body = getOrNull("/api/status") ?: return null
        // Lightweight JSON parse — we only need a handful of fields, so
        // pulling in kotlinx.serialization for this would be overkill.
        // If parsing fails we return a partial status with UNKNOWN gateway.
        return parseStatus(body)
    }

    fun listModels(): List<HermesModelOption> {
        val body = getOrNull("/api/model/options") ?: return emptyList()
        return parseModelOptions(body)
    }

    fun listRecentSessions(limit: Int = 20): List<HermesSessionSummary> {
        val body = getOrNull("/api/sessions?limit=$limit") ?: return emptyList()
        return parseSessionList(body)
    }

    /**
     * Hit `/api/status` with a short timeout to render the green/grey dot
     * in the toolwindow header. Cheap enough to call on every focus event.
     */
    fun isReachable(): Boolean {
        return try {
            val req = HttpRequest.newBuilder()
                .uri(endpointProvider().resolve("/api/status"))
                .timeout(Duration.ofSeconds(2))
                .header("Accept", "application/json")
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding())
            resp.statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Update the active model slot. Returns true on 2xx.
     *
     * @param slot "primary" / "auxiliary" — the dashboard supports multiple
     *             model slots and we let the user choose which one the IDE
     *             chat surface binds to. v0.1.0 ships with primary only.
     */
    fun setActiveModel(modelId: String, slot: String = "primary"): Boolean {
        val payload = """{"slot":"$slot","model":"${escapeJson(modelId)}"}"""
        return try {
            val resp = httpClient.send(
                authedRequest("/api/model/set")
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload))
                    .build(),
                HttpResponse.BodyHandlers.discarding()
            )
            resp.statusCode() in 200..299
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Manual JSON parsing — avoids a serialization dep for ~5 fields.
    // We are tolerant of schema additions: unknown fields are ignored.
    // ------------------------------------------------------------------

    private fun parseStatus(json: String): HermesStatus {
        val version = extractString(json, "version") ?: "unknown"
        val authRequired = extractBool(json, "auth_required") ?: false
        val embeddedChat = extractBool(json, "embedded_chat") ?: true
        // The dashboard exposes gateway state under a few different keys
        // depending on version. Try them in order of likelihood.
        val gatewayRaw = extractString(json, "gateway_state")
            ?: extractString(json, "gateway")
            ?: extractString(json, "status")
        return HermesStatus(
            version = version,
            gateway = HermesStatus.GatewayState.parse(gatewayRaw),
            authRequired = authRequired,
            embeddedChatEnabled = embeddedChat,
        )
    }

    private fun parseModelOptions(json: String): List<HermesModelOption> {
        // Acceptable shapes: {"options":[{"id":..,"label":..,"provider":..}]}
        // or a bare array of {"id":..,"label":..}.
        val objects = extractArray(json, listOf("options", "models", "data"))
        if (objects.isEmpty()) return emptyList()
        return objects.mapNotNull { obj ->
            val id = extractString(obj, "id") ?: return@mapNotNull null
            val label = extractString(obj, "label") ?: extractString(obj, "name") ?: id
            val provider = extractString(obj, "provider") ?: "unknown"
            HermesModelOption(id = id, label = label, provider = provider)
        }
    }

    private fun parseSessionList(json: String): List<HermesSessionSummary> {
        val objects = extractArray(json, listOf("sessions", "data", "items"))
        return objects.mapNotNull { obj ->
            val id = extractString(obj, "id") ?: extractString(obj, "session_id") ?: return@mapNotNull null
            val title = extractString(obj, "title") ?: extractString(obj, "name") ?: "Untitled"
            val updated = extractLong(obj, "updated_at") ?: extractLong(obj, "created_at") ?: 0L
            val preview = extractString(obj, "preview") ?: extractString(obj, "last_message") ?: ""
            HermesSessionSummary(id = id, title = title, updatedAtMillis = updated, preview = preview)
        }
    }

    private fun extractString(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return regex.find(json)?.groupValues?.get(1)?.unescapeJson()
    }

    private fun extractBool(json: String, key: String): Boolean? {
        val regex = Regex("\"$key\"\\s*:\\s*(true|false)")
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractLong(json: String, key: String): Long? {
        val regex = Regex("\"$key\"\\s*:\\s*(-?\\d+)")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Extract an array of JSON object strings by trying each candidate key.
     * Returns the raw substrings — not parsed objects — so callers can
     * apply the same per-field extractors uniformly.
     */
    private fun extractArray(json: String, candidateKeys: List<String>): List<String> {
        for (key in candidateKeys) {
            val keyIdx = json.indexOf("\"$key\"")
            if (keyIdx < 0) continue
            val arrStart = json.indexOf('[', keyIdx)
            if (arrStart < 0) continue
            val arrEnd = matchClosingBracket(json, arrStart)
            if (arrEnd <= arrStart) continue
            val inner = json.substring(arrStart + 1, arrEnd)
            // Split into top-level object substrings. Naive but sufficient
            // because the dashboard's JSON is well-formed and we don't care
            // about nested arrays inside each row for the fields we use.
            return splitTopLevelObjects(inner)
        }
        return emptyList()
    }

    private fun matchClosingBracket(s: String, openIdx: Int): Int {
        var depth = 0
        var inString = false
        var escape = false
        for (i in openIdx until s.length) {
            val c = s[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            when (c) {
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
        }
        return -1
    }

    private fun splitTopLevelObjects(inner: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var start = -1
        for (i in inner.indices) {
            val c = inner[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString }
            if (inString) continue
            when (c) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        out.add(inner.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return out
    }

    private fun String.unescapeJson(): String = this
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t")

    private fun escapeJson(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}