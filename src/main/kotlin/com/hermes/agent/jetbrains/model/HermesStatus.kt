package com.hermes.agent.jetbrains.model

/**
 * State snapshot of the Hermes dashboard, fetched from `GET /api/status`.
 *
 * The dashboard returns a large JSON blob — we project only the fields the
 * IDE shell needs (version, gateway liveness, current model). Everything else
 * stays in the embedded JCEF browser surface where the dashboard SPA renders
 * it natively.
 */
data class HermesStatus(
    val version: String,
    val gateway: GatewayState,
    val authRequired: Boolean,
    val embeddedChatEnabled: Boolean,
) {
    enum class GatewayState {
        RUNNING, STOPPED, UNKNOWN;

        companion object {
            /** Permissive parser — unknown strings map to UNKNOWN rather than throw. */
            fun parse(raw: String?): GatewayState = when (raw?.uppercase()) {
                "RUNNING", "UP", "ALIVE" -> RUNNING
                "STOPPED", "DOWN", "DEAD" -> STOPPED
                else -> UNKNOWN
            }
        }
    }
}

/**
 * One selectable model entry from `GET /api/model/options`.
 *
 * We surface a flat list in the toolbar model-picker. Provider grouping
 * (the dashboard shows them in a grouped dropdown) is intentionally omitted
 * in v0.1.0 — we can revisit once users ask for it.
 */
data class HermesModelOption(
    val id: String,
    val label: String,
    val provider: String,
)

/**
 * What the IDE shell needs to know about a chat session — just enough to
 * power the "Recent conversations" list in the toolwindow header. The
 * actual message history is owned by the embedded dashboard SPA and does
 * not round-trip through the IDE.
 */
data class HermesSessionSummary(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val preview: String,
)