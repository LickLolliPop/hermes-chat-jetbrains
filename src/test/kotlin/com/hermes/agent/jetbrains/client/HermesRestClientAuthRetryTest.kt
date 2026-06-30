package com.hermes.agent.jetbrains.client

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for BUG 2 (footer model stuck after dashboard restart).
 *
 * Root cause we are pinning here: the dashboard generates a fresh
 * `secrets.token_urlsafe(32)` on every process start. The plugin caches the
 * one it scraped from index.html as `autoToken`. After a restart, the
 * dashboard's `/api/...` calls reject the stale token with 401 until the
 * plugin re-scrapes.
 *
 * The fix has two halves:
 *  1. [HermesRestClient.getOrNull] detects 401, fires `onAuthFailure`, and
 *     retries the request once. This self-heals for any token-rotation we
 *     did NOT trigger ourselves (WSL-side `hermes dashboard` restart,
 *     `hermes update` mid-session, port-takeover by another profile, ...).
 *  2. [com.hermes.agent.jetbrains.ui.HermesChatToolWindowFactory.onRestartClicked]
 *     proactively calls [HermesClient.invalidateAutoToken] before killing
 *     the dashboard, so the FIRST post-restart refresh doesn't waste a
 *     roundtrip on a guaranteed 401.
 *
 * These tests exercise (1) directly via [HermesRestClient] — the integration
 * surface that matters. (2) is exercised by [HermesChatPanelBackoffTest] /
 * related UI tests via the `client.invalidateAutoToken()` call site, but we
 * don't need a UI test for the client's invalidation behavior itself.
 */
class HermesRestClientAuthRetryTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    /**
     * Build a client pointing at the test server, with an injectable token
     * provider and an `onAuthFailure` counter we can assert against.
     */
    private fun newClient(
        token: () -> String?,
        onAuthFailure: () -> Unit = {},
    ): HermesRestClient {
        return HermesRestClient(
            endpointProvider = { URI.create("http://127.0.0.1:$port") },
            tokenProvider = { token() },
            onAuthFailure = onAuthFailure,
        )
    }

    @Test
    fun `401 followed by 200 triggers onAuthFailure and returns the second body`() {
        // The "token rotated" sequence: first call sees stale token → 401;
        // onAuthFailure invalidates; second call uses fresh token → 200.
        val hits = AtomicInteger(0)
        val authFired = AtomicInteger(0)
        var currentToken = "stale-token"

        server.createContext("/api/model/options", HttpHandler { exchange: HttpExchange ->
            hits.incrementAndGet()
            val presented = exchange.requestHeaders.getFirst("X-Hermes-Session-Token")
            if (presented == "stale-token") {
                respondJson(exchange, 401, """{"detail":"Unauthorized"}""")
            } else {
                // Mirror the dashboard's /api/model/options shape: a
                // `providers` array and a tail `"model":"..."` for the
                // currently active model. parser is what we're proving
                // works end-to-end, so use a payload it can actually
                // parse.
                respondJson(
                    exchange, 200,
                    """{"providers":[{"slug":"anthropic","name":"Anthropic","models":["claude-opus-4.8"]}],"model":"claude-opus-4.8"}"""
                )
            }
        })

        val client = newClient(
            token = { currentToken },
            onAuthFailure = {
                authFired.incrementAndGet()
                currentToken = "fresh-token"
            },
        )

        val list = client.listModels()
        assertEquals(2, hits.get(), "first call + retry = 2 hits")
        assertEquals(1, authFired.get(), "onAuthFailure fires exactly once on 401")
        assertEquals(1, list.options.size, "options parsed from the retried body")
        assertEquals("claude-opus-4.8", list.options[0].id)
        assertEquals("claude-opus-4.8", list.currentModelId)
    }

    @Test
    fun `200 on the first attempt does NOT fire onAuthFailure`() {
        val authFired = AtomicInteger(0)
        server.createContext("/api/model/options", HttpHandler { exchange ->
            respondJson(
                exchange, 200,
                """{"providers":[{"slug":"anthropic","name":"Anthropic","models":["a"]}],"model":"a"}"""
            )
        })

        val client = newClient(token = { "good" }, onAuthFailure = { authFired.incrementAndGet() })
        val list = client.listModels()
        assertEquals(1, list.options.size)
        assertEquals(0, authFired.get(), "no 401 → no callback → no wasted invalidate")
    }

    @Test
    fun `401 with no retry returns null and fires the callback for callers that want it`() {
        // getOrNull default (retryOnAuth=false) on a 401 just yields null —
        // but the callback still fires so observers can react. This keeps
        // the door open for /api/sessions / /api/status variants that
        // don't want a retry but do want telemetry on token rotation.
        val authFired = AtomicInteger(0)
        server.createContext("/api/sessions", HttpHandler { exchange ->
            respondJson(exchange, 401, """{"detail":"Unauthorized"}""")
        })

        val client = newClient(token = { "stale" }, onAuthFailure = { authFired.incrementAndGet() })
        val sessions = client.listRecentSessions()
        assertTrue(sessions.isEmpty(), "401 body is not JSON-array-of-sessions → empty")
        assertEquals(1, authFired.get(), "callback still fires for non-retrying paths")
    }

    @Test
    fun `401 followed by another 401 returns null without infinite retry`() {
        // Important: the retry path is "once". A second 401 means the
        // newly-scraped token is ALSO stale (or auth is disabled entirely
        // and we're scraping from a stale SPA cache) — don't loop.
        val hits = AtomicInteger(0)
        val authFired = AtomicInteger(0)

        server.createContext("/api/model/options", HttpHandler { exchange ->
            hits.incrementAndGet()
            respondJson(exchange, 401, """{"detail":"Unauthorized"}""")
        })

        val client = newClient(
            token = { "still-stale" },
            onAuthFailure = { authFired.incrementAndGet() },
        )
        val list = client.listModels()
        assertEquals(0, list.options.size, "two consecutive 401s → empty list, no panic")
        assertEquals(2, hits.get(), "exactly 2 attempts — no retry loop")
        assertEquals(1, authFired.get(), "callback fires on the first 401, not the second")
    }

    @Test
    fun `500 does NOT trigger onAuthFailure (token is not the problem)`() {
        // Don't paper over real server errors with a token re-scrape. The
        // retry path is keyed strictly on 401.
        val authFired = AtomicInteger(0)
        server.createContext("/api/model/options", HttpHandler { exchange ->
            respondJson(exchange, 500, """{"detail":"boom"}""")
        })

        val client = newClient(token = { "anything" }, onAuthFailure = { authFired.incrementAndGet() })
        val list = client.listModels()
        assertEquals(0, list.options.size)
        assertEquals(0, authFired.get(), "500 is server-side, not a token rotation")
    }

    @Test
    fun `network exception does NOT trigger onAuthFailure`() {
        // Connect refused (no server on this port). The send swallows
        // the exception, getOrNull returns null, no callback. (We already
        // stopped the server in another test before this one would run,
        // but the contract is what matters: network error ≠ token error.)
        // Use an unbound port instead of stopping the test server, so
        // the rest of this test's setup is unaffected.
        val unboundPort = 1 // port 1 is reserved; nothing listens there
        val authFired = AtomicInteger(0)
        val client = HermesRestClient(
            endpointProvider = { URI.create("http://127.0.0.1:$unboundPort") },
            tokenProvider = { "anything" },
            onAuthFailure = { authFired.incrementAndGet() },
        )
        val list = client.listModels()
        assertEquals(0, list.options.size)
        assertEquals(0, authFired.get())
    }

    @Test
    fun `getStatus also retries on 401`() {
        // /api/status is public on the OAuth-gated dashboard, but on the
        // legacy loopback-bind dashboard it goes through the same session
        // token middleware. We retry there too as cheap insurance.
        val hits = AtomicInteger(0)
        var currentToken = "stale"

        server.createContext("/api/status", HttpHandler { exchange ->
            hits.incrementAndGet()
            if (exchange.requestHeaders.getFirst("X-Hermes-Session-Token") == "stale") {
                respondJson(exchange, 401, """{"detail":"Unauthorized"}""")
            } else {
                respondJson(
                    exchange, 200,
                    """{"version":"0.1.0","gateway_state":"running"}"""
                )
            }
        })

        val client = newClient(
            token = { currentToken },
            onAuthFailure = { currentToken = "fresh" },
        )
        val status = client.getStatus()
        assertNotNull(status, "second attempt with fresh token should succeed")
        assertEquals("0.1.0", status!!.version)
        assertEquals(2, hits.get())
    }

    // ----- helpers -----------------------------------------------------

    private fun respondJson(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}