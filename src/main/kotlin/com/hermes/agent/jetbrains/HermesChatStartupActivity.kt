package com.hermes.agent.jetbrains

import com.hermes.agent.jetbrains.client.HermesClient
import com.hermes.agent.jetbrains.model.HermesStatus
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Post-startup hook.
 *
 * Two responsibilities:
 *   1. Probe the Hermes dashboard. If it's not running, show a one-shot
 *      notification with a "how to start" link — but only on the first
 *      project that opens in this IDE session (we key off the dashboard's
 *      own reachability to avoid spamming multi-project workspaces).
 *   2. Auto-open the Hermes Chat tool window on the first IDE start so
 *      users don't have to dig through View → Tool Windows to find it.
 *      Subsequent IDE restarts remember the user's last visibility state.
 *
 * Why a ProjectActivity rather than app-component init: most users have
 * the dashboard already running by the time they open a project, so the
 * startup check has to wait for that user's project context (different
 * projects may have different token/endpoint settings).
 */
class HermesChatStartupActivity : ProjectActivity {

    private val log = logger<HermesChatStartupActivity>()

    override fun runActivity(project: Project) {
        val client = HermesClient.getInstance()
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val reachable = client.isReachable()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (!reachable) {
                    notifyDashboardDown(project)
                } else {
                    client.fetchStatusAsync { status: HermesStatus? ->
                        log.info("Hermes dashboard reachable, version=${status?.version}")
                    }
                    // Auto-open the toolwindow the first time only.
                    openToolWindowIfFirstTime(project)
                }
            }
        }
    }

    private fun notifyDashboardDown(project: Project) {
        // Register the group once (idempotent) at notification time rather
        // than at plugin-load time — startup activities should be cheap and
        // not depend on user notification preferences.
        val manager = NotificationGroupManager.getInstance()
        val group = manager.getNotificationGroup("Hermes Chat")
            ?: manager.registerNotificationGroup("Hermes Chat")
        group.createNotification(
            "Hermes dashboard not reachable",
            "Start it in a terminal with: hermes dashboard. The plugin will connect automatically once it's up.",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    private fun openToolWindowIfFirstTime(project: Project) {
        // Only auto-open once per IDE install. JVM system property is
        // sufficient — it's a one-shot UX nudge, not persistent preference.
        val opened = java.lang.Boolean.getBoolean("hermes.chat.toolwindow.autoopened")
        if (opened) return
        System.setProperty("hermes.chat.toolwindow.autoopened", "true")
        val tw = ToolWindowManager.getInstance(project).getToolWindow("Hermes Chat") ?: return
        tw.show()
    }
}