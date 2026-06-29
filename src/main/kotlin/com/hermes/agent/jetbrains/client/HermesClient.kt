package com.hermes.agent.jetbrains.client

import com.hermes.agent.jetbrains.model.HermesModelList
import com.hermes.agent.jetbrains.model.HermesSessionSummary
import com.hermes.agent.jetbrains.model.HermesStatus
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.concurrency.AppExecutorUtil
import java.net.URI
import java.util.concurrent.atomic.AtomicReference

/**
 * Application-level facade that the UI layer talks to.
 *
 * Why a singleton service:
 * - The REST client is stateless and thread-safe, but the *endpoint* and
 *   *token* are user-tunable and need to react to settings changes. Hiding
 *   this behind a service means the UI binds to `HermesClient.get()` and
 *   never holds a stale client.
 * - Future: when we add the WebSocket PTY bridge for code-attached chat,
 *   the same service owns its lifecycle.
 *
 * The client never blocks the EDT — every network call returns immediately
 * and posts results back via [thenOnPool] (an ExecutorService that
 * marshals back to the EDT for UI updates).
 */
@State(
    name = "HermesChatClient",
    storages = [Storage("hermes-chat.xml")],
)
@Service(Service.Level.APP)
class HermesClient : PersistentStateComponent<HermesClient.State> {

    data class State(
        var endpoint: String = "http://127.0.0.1:9119",
        var sessionToken: String = "",
        var defaultModelId: String = "",
    )

    private val stateRef = AtomicReference(State())
    private val tokenFetcher by lazy {
        DashboardTokenFetcher(endpointProvider = { URI.create(currentState().endpoint.trim().ifEmpty { "http://127.0.0.1:9119" }) })
    }
    // Cached auto-fetched token. Null means "not fetched yet" or "fetch
    // failed". Cleared on settings update so a new endpoint re-triggers
    // discovery.
    @Volatile private var autoToken: String? = null
    @Volatile private var autoTokenAttempted: Boolean = false

    private val restClient by lazy {
        HermesRestClient(
            endpointProvider = { URI.create(currentState().endpoint.trim().ifEmpty { "http://127.0.0.1:9119" }) },
            tokenProvider = { resolveToken() },
        )
    }

    // -- PersistentStateComponent ----------------------------------------
    override fun getState(): State = stateRef.get()
    override fun loadState(state: State) { stateRef.set(state) }

    private fun currentState(): State = stateRef.get()

    /**
     * Returns the token to use for the next request or JCEF URL.
     */
    fun resolveToken(): String? {
        val manual = currentState().sessionToken.takeIf { it.isNotBlank() }
        if (manual != null) return manual
        return autoToken
    }

    fun updateSettings(endpoint: String? = null, token: String? = null, model: String? = null) {
        val current = stateRef.get()
        stateRef.set(current.copy(
            endpoint = endpoint?.takeIf { it.isNotBlank() } ?: current.endpoint,
            sessionToken = token ?: current.sessionToken,
            defaultModelId = model ?: current.defaultModelId,
        ))
        // Endpoint change invalidates the cached auto-token. The next
        // resolveToken() call will re-fetch against the new endpoint.
        if (endpoint != null) {
            autoToken = null
            autoTokenAttempted = false
        }
    }

    // -- Async API (preferred from UI code) -------------------------------

    /** Returns null on the EDT-callback if the dashboard isn't reachable. */
    fun fetchStatusAsync(uiCallback: (HermesStatus?) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            val s = restClient.getStatus()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                uiCallback(s)
            }
        }
    }

    fun fetchModelsAsync(uiCallback: (HermesModelList) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            val list = restClient.listModels()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                uiCallback(list)
            }
        }
    }

    fun fetchSessionsAsync(uiCallback: (List<HermesSessionSummary>) -> Unit) {
        AppExecutorUtil.getAppExecutorService().execute {
            val list = restClient.listRecentSessions()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                uiCallback(list)
            }
        }
    }

    /** Cheap connectivity probe — used for the green/grey header dot. */
    fun isReachable(): Boolean = restClient.isReachable()

    /** Best-effort model switch. UI should re-fetch status after this. */
    fun setActiveModel(modelId: String): Boolean = restClient.setActiveModel(modelId)

    /** For the settings "Test connection" button. */
    fun testConnection(): HermesStatus? = restClient.getStatus()

    /**
     * Returns true if an auto-fetched token is currently cached. Useful
     * for the UI's debug log line so we can see whether the token made
     * it from index.html to the HTTP layer on the first probe.
     */
    fun hasAutoToken(): Boolean = autoToken != null

    /**
     * Make sure we have a session token cached. Safe to call repeatedly:
     * if the user has manually set a token in settings, this is a no-op.
     * If we've already auto-fetched, this is a no-op. Otherwise it scrapes
     * the dashboard's index.html for the ephemeral token.
     *
     * Best invoked from the panel's status-probe cycle (every 8s) so a
     * freshly-started dashboard becomes usable without a manual refresh.
     * Returns true if a token is now available (manual or auto).
     */
    fun ensureToken(): Boolean {
        if (currentState().sessionToken.isNotBlank()) return true
        if (autoToken != null) return true
        if (autoTokenAttempted) return false
        autoTokenAttempted = true
        val fetched = try {
            tokenFetcher.fetchToken()
        } catch (t: Throwable) {
            // tokenFetcher.fetchToken() already swallows its own exceptions
            // and returns null, so reaching this catch is unexpected. Log
            // loudly so a sandbox failure shows up in idea.log at WARN
            // (previous debug-level message was invisible by default).
            com.intellij.openapi.diagnostic.logger<HermesClient>().warn(
                "tokenFetcher.fetchToken() threw unexpectedly", t
            )
            null
        }
        if (fetched != null) {
            autoToken = fetched
            com.intellij.openapi.diagnostic.logger<HermesClient>().info(
                "Auto-fetched dashboard session token (${fetched.length} chars)"
            )
        } else {
            // Sandbox-diagnostics: distinguish "fetched" returning null
            // (HTML missing __HERMES_SESSION_TOKEN__) from "fetch threw"
            // (network unreachable, etc). tokenFetcher logs at debug so
            // this WARN line surfaces in idea.log without changing the
            // existing call-site contract.
            com.intellij.openapi.diagnostic.logger<HermesClient>().warn(
                "ensureToken: could not obtain dashboard session token — " +
                    "model fetch will be deferred until token becomes available"
            )
        }
        return autoToken != null
    }

    companion object {
        @JvmStatic
        fun getInstance(): HermesClient =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(HermesClient::class.java)
    }
}