package com.hermes.agent.jetbrains.client

import com.intellij.openapi.diagnostic.logger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Fetches the dashboard's ephemeral session token by scraping the SPA HTML.
 *
 * Background: `hermes dashboard` on a loopback bind generates a fresh
 * `secrets.token_urlsafe(32)` token on every startup and bakes it into
 * the served `index.html` as `window.__HERMES_SESSION_TOKEN__="..."`.
 * Without this token, every `/api/...` endpoint (except the public
 * ones like `/api/status` and `/api/auth/...`) returns 401. There's no documented "give me a token" endpoint — the
 * HTML scrape is the only loopback-friendly mechanism.
 *
 * Security note: this is safe because we only ever talk to a loopback
 * endpoint (the user's own machine). The token never leaves the IDE
 * process and is held in memory only.
 *
 * Failure modes:
 * - Dashboard not running       → IOException → returns null
 * - HTML doesn't contain token  → returns null (caller treats as auth off)
 * - Network timeout (2s)        → returns null
 *
 * This class is stateless and thread-safe. The caller (HermesClient) is
 * responsible for caching the result and re-fetching on dashboard restart
 * (detected by reaching the /api/status endpoint and getting a 401, or
 * by the user clicking "Test connection" in settings).
 */
class DashboardTokenFetcher(
    private val endpointProvider: () -> URI,
) {
    private val log = logger<DashboardTokenFetcher>()

    /**
     * Last fetch error message, or null on success / no attempt yet.
     * Surfaced to the UI so the user sees *why* the model picker is
     * empty (e.g. "Connection refused", "HTTP 500") instead of just
     * a silent "(loading…)" that never updates.
     *
     * Public so HermesClient can read it for the footer's retry button.
     */
    @Volatile var lastError: String? = null
        private set

    // Dedicated HttpClient — never goes through a system proxy, same
    // reason as HermesRestClient: loopback is the same machine.
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .version(HttpClient.Version.HTTP_1_1)
        .build()

    /**
     * Returns the session token string, or null if it can't be obtained.
     * Never throws — fetch failures are non-fatal: the rest of the IDE
     * shell should still render, just with "unreachable" status.
     */
    fun fetchToken(): String? {
        return try {
            val base = endpointProvider().toString().trimEnd('/')
            // Hit the SPA root. We don't need auth for this — it's the
            // unauthenticated HTML that *contains* the token.
            val req = HttpRequest.newBuilder()
                .uri(URI.create("$base/"))
                .timeout(Duration.ofSeconds(2))
                .header("Accept", "text/html")
                .header("User-Agent", "HermesChat-IntelliJ/0.1.0")
                .GET()
                .build()
            val resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200..299) {
                // WARN (not DEBUG): this is the *reason* the model picker
                // is empty and stays empty. An operator reading idea.log
                // should be able to see it without raising log levels.
                lastError = "HTTP ${resp.statusCode()} from dashboard root"
                log.warn("Token fetch: dashboard returned HTTP ${resp.statusCode()}")
                return null
            }
            val token = extractTokenFromHtml(resp.body())
            if (token == null) {
                lastError = "Dashboard HTML missing __HERMES_SESSION_TOKEN__"
                log.warn("Token fetch: ${lastError}")
                return null
            }
            lastError = null
            token
        } catch (t: Throwable) {
            // Connect refused, timeout, malformed URI — all mean "no token
            // available right now". The dashboard is probably not running.
            lastError = "${t.javaClass.simpleName}: ${t.message ?: "(no message)"}"
            log.warn("Token fetch failed: ${lastError}")
            null
        }
    }

    /**
     * Pulls `__HERMES_SESSION_TOKEN__="..."` (or unquoted) out of the SPA
     * HTML. The dashboard injects it as a global before mounting React,
     * so the substring is always present in the served index.html.
     */
    private fun extractTokenFromHtml(html: String): String? {
        // Tolerate both quoted forms:
        //   window.__HERMES_SESSION_TOKEN__="abc...";
        //   window.__HERMES_SESSION_TOKEN__='abc...';
        val doubleQuoted = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*"([^"]+)"""")
        doubleQuoted.find(html)?.let { return it.groupValues[1] }
        val singleQuoted = Regex("""__HERMES_SESSION_TOKEN__\s*=\s*'([^']+)'""")
        singleQuoted.find(html)?.let { return it.groupValues[1] }
        return null
    }
}
