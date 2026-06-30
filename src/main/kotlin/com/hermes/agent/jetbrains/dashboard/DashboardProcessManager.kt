package com.hermes.agent.jetbrains.dashboard

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger

/**
 * Facade that picks the right per-OS [DashboardProcessStrategy] and exposes
 * a small, stable API to the UI layer.
 *
 * History: in v0.1.0 this class was a single 200-line implementation that
 * hardcoded `wsl.exe bash -lc "tmux new-session -d ..."`. That only worked
 * on Windows hosts with WSL installed. The strategy split (v0.2.0) lets
 * Mac and Linux users use the plugin without WSL — they get a much
 * simpler nohup-based launcher because the JVM-spawned child isn't in a
 * cross-OS process group (no tmux required).
 *
 * The strategy is resolved lazily on first call so we don't pay the OS
 * detection cost during class init (the IDE's classloader can be
 * touchy about static initialization of plugin classes).
 *
 * Callers (`HermesChatToolWindowFactory.onRestartClicked`,
 * `HermesChatStartupActivity`) bind to this facade; they don't need to
 * know which OS they're on.
 */
class DashboardProcessManager(
    private val strategy: DashboardProcessStrategy = DashboardProcessStrategy.forCurrentOs(),
) {

    private val log = logger<DashboardProcessManager>()

    /**
     * OS name the resolved strategy handles. Surfaced in notifications
     * and logs so an operator reading idea.log can see at a glance which
     * code path actually ran ("WSL", "macOS", "Linux").
     */
    val osLabel: String = strategy.osLabel

    /**
     * Restart the dashboard: stop → wait for port → start.
     * Runs off the EDT; [onComplete] fires on the EDT via invokeLater.
     */
    fun restartDashboard(onComplete: (Result) -> Unit) {
        log.info("DashboardProcessManager: restarting via $osLabel strategy")
        strategy.restart(onComplete)
    }

    /**
     * Returns true if the dashboard is running and responding.
     */
    fun isDashboardRunning(): Boolean = strategy.isRunning()

    /**
     * Result of a [restartDashboard] attempt. `fatal=true` means the user
     * should be told something went wrong; `fatal=false` is informational
     * (dashboard is up, all good).
     */
    data class Result(val message: String, val fatal: Boolean) {
        val success: Boolean get() = !fatal
    }

    companion object {
        /** Cap on captured stdout/stderr per process invocation. */
        const val MAX_OUTPUT = 64 * 1024

        /**
         * Show a balloon to the user. The notification group "Hermes Chat"
         * is pre-registered in plugin.xml (see `<notificationGroup>`), so we
         * just resolve it via the modern NotificationGroupManager API.
         *
         * Note: `registerNotificationGroup(...)` on NotificationGroupManager
         * is `@ApiStatus.Internal` in Platform 2024.2+ — calling it from
         * plugin code triggers an `Unresolved reference` compile error
         * (the symbol is generated but excluded from the public API). The
         * correct path is to register in plugin.xml and resolve at runtime.
         */
        fun notify(message: String, type: NotificationType) {
            val mgr = NotificationGroupManager.getInstance()
            val group = mgr.getNotificationGroup("Hermes Chat")
                ?: error("Notification group 'Hermes Chat' not registered in plugin.xml")
            group.createNotification(message, type).notify(null)
        }
    }
}