package com.hermes.agent.jetbrains.client

import com.hermes.agent.jetbrains.model.HermesModelOption
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
@Service
class HermesClient : PersistentStateComponent<HermesClient.State> {

    data class State(
        var endpoint: String = "http://127.0.0.1:9119",
        var sessionToken: String = "",
        var defaultModelId: String = "",
    )

    private val stateRef = AtomicReference(State())
    private val restClient by lazy {
        HermesRestClient(
            endpointProvider = { URI.create(currentState().endpoint.trim().ifEmpty { "http://127.0.0.1:9119" }) },
            tokenProvider = { currentState().sessionToken.takeIf { it.isNotBlank() } },
        )
    }

    // -- PersistentStateComponent ----------------------------------------
    override fun getState(): State = stateRef.get()
    override fun loadState(state: State) { stateRef.set(state) }

    /**
     * Convenience accessor for UI code — `client.state` reads more naturally
     * than `client.getState()` at call sites.
     */
    val state: State get() = currentState()

    private fun currentState(): State = stateRef.get()

    fun updateSettings(endpoint: String? = null, token: String? = null, model: String? = null) {
        val current = stateRef.get()
        stateRef.set(current.copy(
            endpoint = endpoint?.takeIf { it.isNotBlank() } ?: current.endpoint,
            sessionToken = token ?: current.sessionToken,
            defaultModelId = model ?: current.defaultModelId,
        ))
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

    fun fetchModelsAsync(uiCallback: (List<HermesModelOption>) -> Unit) {
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

    companion object {
        @JvmStatic
        fun getInstance(): HermesClient =
            com.intellij.openapi.application.ApplicationManager.getApplication().getService(HermesClient::class.java)
    }
}