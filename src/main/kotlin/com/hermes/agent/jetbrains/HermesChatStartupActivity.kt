package com.hermes.agent.jetbrains

import com.hermes.agent.jetbrains.client.HermesClient
import com.hermes.agent.jetbrains.model.HermesStatus
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

    override suspend fun execute(project: Project) {
        val client = HermesClient.getInstance()

        // Use Coroutines instead of manual executeOnPooledThread
        val reachable = withContext(Dispatchers.IO) {
            client.isReachable()
        }

        withContext(Dispatchers.Main) {
            if (!reachable) {
                // When the user has opted out of automatic dashboard
                // management, a missing dashboard is normal (they're
                // managing it themselves via systemd / launchd / a
                // separate terminal / Docker). Don't nag them with a
                // notification; the panel already shows the unreachable
                // state and the settings panel shows the manual-launch
                // command.
                if (client.getState().manageAutomatically) {
                    notifyDashboardDown(project)
                }
            } else {
                client.fetchStatusAsync { status: HermesStatus? ->
                    log.info("Hermes dashboard reachable, version=${status?.version}")
                }
                // Auto-open the toolwindow the first time only.
                openToolWindowIfFirstTime(project)
            }
        }
    }

    private fun notifyDashboardDown(project: Project) {
        // Notification group is now registered in plugin.xml
        val manager = NotificationGroupManager.getInstance()
        val group = manager.getNotificationGroup("Hermes Chat")
            ?: return // Should not happen if registered in plugin.xml
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