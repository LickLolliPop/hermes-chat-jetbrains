package com.hermes.agent.jetbrains.client

import com.hermes.agent.jetbrains.model.HermesModelList
import com.hermes.agent.jetbrains.model.HermesModelOption
import com.hermes.agent.jetbrains.model.HermesSessionSummary
import com.hermes.agent.jetbrains.model.HermesStatus
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Thin REST client over the Hermes dashboard's HTTP API.
 */
class HermesRestClient(
    private val endpointProvider: () -> URI,
    private val tokenProvider: () -> String?,
    // Fired on a 401 so the caller can invalidate its cached auto-token.
    // Wired by HermesClient to invalidateAutoToken(); tests pass a no-op.
    private val onAuthFailure: (() -> Unit)? = null,
) {

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .version(HttpClient.Version.HTTP_1_1)
        .proxy(ProxySelector.of(null))
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
            builder.header("X-Hermes-Session-Token", token)
        }
        return builder
    }

    fun getOrNull(path: String, retryOnAuth: Boolean = false): String? {
        val first = sendGet(path) ?: return null
        if (first.statusCode() in 200..299) return first.body()
        if (first.statusCode() == 401) {
            // Token rotated (dashboard restart, etc.). Invalidate so the next
            // call (or the retry below) uses a fresh token.
            onAuthFailure?.invoke()
            if (retryOnAuth) {
                val second = sendGet(path) ?: return null
                if (second.statusCode() in 200..299) return second.body()
            }
        }
        return null
    }

    private fun sendGet(path: String): HttpResponse<String>? {
        return try {
            httpClient.send(
                authedRequest(path).GET().build(),
                HttpResponse.BodyHandlers.ofString()
            )
        } catch (_: Exception) {
            null
        }
    }

    fun getStatus(): HermesStatus? {
        // /api/status is in PUBLIC_API_PATHS — never 401s on the OAuth-gated
        // dashboard, but the legacy loopback-bind dashboard still requires
        // the session token for it. We retry once on 401.
        val body = getOrNull("/api/status", retryOnAuth = true) ?: return null
        return parseStatus(body)
    }

    fun listModels(): HermesModelList {
        // /api/model/options is gated. Stale autoToken → 401 → empty list
        // → footer stuck on previous model. Retry self-heals without
        // needing the user to click "Retry".
        val body = getOrNull("/api/model/options", retryOnAuth = true)
            ?: return HermesModelList(emptyList(), null)
        return parseModelOptions(body)
    }

    fun listRecentSessions(limit: Int = 20): List<HermesSessionSummary> {
        val body = getOrNull("/api/sessions?limit=$limit") ?: return emptyList()
        return parseSessionList(body)
    }

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

    private fun parseStatus(json: String): HermesStatus {
        val version = extractString(json, "version") ?: "unknown"
        val authRequired = extractBool(json, "auth_required") ?: false
        val embeddedChat = extractBool(json, "embedded_chat") ?: true
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

    private fun parseModelOptions(json: String): HermesModelList {
        // The dashboard's /api/model/options payload ends with two extra
        // top-level fields: `"model":"anthropic/claude-opus-4.8"` and
        // `"provider":"anthropic"`. They designate the *currently active*
        // model so the IDE toolwindow can pre-select it in the picker
        // instead of leaving whatever order the array happened to ship in.
        //
        // Regex caveat: the same `"model"` key doesn't appear inside any
        // provider object (only the plural `"models"` does), so a simple
        // regex looking for `"model":"..."` is safe here. We still anchor
        // to a non-payload-tail exclusion via extractStringLast so future
        // schema additions don't accidentally match a nested field.
        val currentModelId = extractStringLast(json, "model")
        val providerShape = extractArray(json, listOf("providers"))
        if (providerShape.isNotEmpty()) {
            val out = mutableListOf<HermesModelOption>()
            for (provJson in providerShape) {
                val providerSlug = extractString(provJson, "slug") ?: "unknown"
                val providerName = extractString(provJson, "name") ?: providerSlug
                val modelsArr = extractArray(provJson, listOf("models"))
                if (modelsArr.isEmpty()) {
                    // Provider has no models (e.g. MINIMAX_API_KEY not set).
                    // Skip silently — only providers with at least one model
                    // contribute to the picker.
                    continue
                }
                for (modelJson in modelsArr) {
                    val id = modelJson.trim().trim('"').trim()
                    if (id.isBlank()) continue
                    out.add(HermesModelOption(id = id, label = "$id — $providerName", provider = providerSlug))
                }
            }
            return HermesModelList(out, currentModelId)
        }
        val objects = extractArray(json, listOf("options", "models", "data"))
        if (objects.isEmpty()) return HermesModelList(emptyList(), currentModelId)
        return HermesModelList(
            objects.mapNotNull { obj ->
                val id = extractString(obj, "id") ?: return@mapNotNull null
                val label = extractString(obj, "label") ?: extractString(obj, "name") ?: id
                val provider = extractString(obj, "provider") ?: "unknown"
                HermesModelOption(id = id, label = label, provider = provider)
            },
            currentModelId,
        )
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
     * Like [extractString] but returns the LAST occurrence. Used for keys
     * that the dashboard emits at payload-tail (e.g. `model` + `provider`
     * appended after the `providers[]` array in `/api/model/options`).
     * Falls back to null when the key isn't present.
     */
    private fun extractStringLast(json: String, key: String): String? {
        val regex = Regex("\"$key\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        return regex.findAll(json).lastOrNull()?.groupValues?.get(1)?.unescapeJson()
    }

    private fun extractArray(json: String, candidateKeys: List<String>): List<String> {
        for (key in candidateKeys) {
            val keyIdx = json.indexOf("\"$key\"")
            if (keyIdx < 0) continue
            val arrStart = json.indexOf('[', keyIdx)
            if (arrStart < 0) continue
            val arrEnd = matchClosingBracket(json, arrStart)
            if (arrEnd <= arrStart) continue
            val inner = json.substring(arrStart + 1, arrEnd)
            // Each element may be a `{...}` object (legacy shape, used by
            // session lists and older /api/model/options responses) OR a
            // bare string like `"anthropic/claude-opus-4.8"` (current
            // v0.17.0+ providers[].models shape). splitTopLevelElements
            // handles both.
            return splitTopLevelElements(inner)
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

    private fun splitTopLevelElements(inner: String): List<String> {
        // Splits a JSON array body into top-level elements. Each element
        // is either a `{...}` object (returned verbatim, braces included)
        // OR a bare string literal like `"anthropic/claude-opus-4.8"`
        // (returned with the surrounding quotes — callers like
        // parseModelOptions strip them before use).
        //
        // Naive but sufficient for Hermes dashboard output: well-formed
        // JSON, no trailing comma, no comments. Strings are tracked
        // char-by-char with a backslash-escape flag, so model ids
        // containing `/` (e.g. `anthropic/claude-opus-4.8`) parse
        // without false splits.
        val out = mutableListOf<String>()
        var depth = 0
        var inString = false
        var escape = false
        var elementStart = -1
        for (i in inner.indices) {
            val c = inner[i]
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') {
                if (depth == 0 && !inString) {
                    // entering a bare-string element
                    elementStart = i
                }
                inString = !inString
                if (depth == 0 && !inString) {
                    // closed a bare-string element
                    out.add(inner.substring(elementStart, i + 1))
                    elementStart = -1
                }
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> {
                    if (depth == 0) elementStart = i
                    depth++
                }
                '}' -> {
                    depth--
                    if (depth == 0 && elementStart >= 0) {
                        out.add(inner.substring(elementStart, i + 1))
                        elementStart = -1
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
