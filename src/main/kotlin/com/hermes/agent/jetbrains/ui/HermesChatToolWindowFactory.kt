package com.hermes.agent.jetbrains.ui

import com.hermes.agent.jetbrains.client.HermesClient
import com.hermes.agent.jetbrains.dashboard.DashboardProcessManager
import com.hermes.agent.jetbrains.model.HermesStatus
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ide.BrowserUtil
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBuilder
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * VSCode-Chat-style tool window.
 *
 * Layout:
 * ```
 *   ┌──────────────────────────────────────────────┐
 *   │ ● Connected — v0.6.3 (model: claude-opus-4)  │   ← header status bar
 *   ├──────────────────────────────────────────────┤
 *   │                                              │
 *   │   [JCEF browser hosting dashboard /chat]    │   ← main chat surface
 *   │                                              │
 *   │                                              │
 *   ├──────────────────────────────────────────────┤
 *   │ Model: claude-opus-4              [Open...]  │   ← footer toolbar (read-only)
 *   └──────────────────────────────────────────────┘
 * ```
 *
 * Why host the dashboard SPA inside JCEF rather than reimplementing the
 * chat UI in Swing:
 * - Hermes' chat surface (markdown + streaming + tool-call visualisation +
 *   ANSI rendering) is already a battle-tested React + xterm.js bundle.
 * - Reimplementing it would be ~2-3 months of work and would always be
 *   behind the dashboard.
 * - The IDE shell only owns navigation, settings, and (future) IDE-context
 *   bridges — all the heavy lifting lives in the dashboard.
 *
 * Model selection is handled by the embedded dashboard SPA (its own model &
 * tools dropdowns). The IDE footer only reflects the currently-active model
 * as a read-only label — no dropdown, no POST /api/model/set from the IDE.
 *
 * Fallback path: if JCEF isn't available (some Android Studio builds
 * disable it, or the JBR is missing the cef binaries), we render a static
 * "Open in browser" link instead of crashing.
 */
class HermesChatToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HermesChatPanel(project)
        // The ContentManager is the only thing ToolWindowFactory requires
        // us to populate. Setting a preferred size keeps the toolwindow
        // from collapsing to a 30px-wide sliver on first open.
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(
            panel.component,
            "Chat",
            false,
        )
        content.preferredFocusableComponent = panel.component
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * The actual UI tree. Held together as a single class so the layout
 * invariants (status bar height, footer height, JCEF weight=1.0) stay
 * co-located with the JComponent construction.
 */
class HermesChatPanel(private val project: Project) {

    private val client: HermesClient = HermesClient.getInstance()
    private val log = logger<HermesChatPanel>()

    private val header = JBLabel(" ", SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = UIUtil.getContextHelpForeground()
        icon = HermesIcons.ToolWindowSmall
    }
    private val footer = FooterPanel()

    // Refresh button — sits in the header row, opposite the status label.
    // Restarts the WSL-hosted dashboard process. The button is disabled
    // and the label swaps to "Restarting…" while the restart is in flight,
    // so the user can't double-click and pile up concurrent wsl.exe calls.
    private val refreshButton = JButton("↻").apply {
        toolTipText = "Restart Hermes dashboard in WSL"
        isFocusable = false
        margin = JBUI.insets(2, 6)
        addActionListener { onRestartClicked() }
    }
    // Header row: [icon + status label | refresh button]. Wrapped in a
    // JPanel because BorderLayout needs a container to split horizontally.
    // The label keeps its addMouseListener handler for "click to open in
    // browser" — that behaviour is unchanged.
    private val headerRow: JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 8)
        add(header, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)
    }

    // The browser is created lazily because JCEF requires the IDE-level
    // CefApp to be initialized, which happens on first request.
    private var browser: CefBrowser? = null
    private var isFallbackAdded = false
    private val browserHost = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        background = JBColor.PanelBackground
        border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
    }

    private val root: JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(headerRow, BorderLayout.NORTH)
        add(browserHost, BorderLayout.CENTER)
        add(footer.component, BorderLayout.SOUTH)
        preferredSize = Dimension(420, 600)
    }

    init {
        footer.onOpenInBrowser = { openInExternalBrowser() }
        // Retry handler: invoked when the user clicks the "↻ Retry" button
        // that appears next to the model label when the dashboard token
        // fetch has failed. The handler clears the autoToken latch inside
        // HermesClient so ensureToken() is allowed to try again, then
        // immediately kicks off a status probe (which will fetch the
        // model list if the retry succeeded).
        footer.onRetryTokenFetch = { onRetryTokenClicked() }
        // Model selection is handled by the embedded dashboard SPA.
        // The IDE footer only reflects the current model (read-only).

        // First paint: kick off the async status probe. We *don't* await
        // here — the IDE shell should be visible immediately even if the
        // dashboard is down.
        refreshStatus()
        // Probe every 8s. Cheap (HEAD-style), keeps the green/grey dot honest.
        val timer = javax.swing.Timer(8_000, { refreshStatus() })
        timer.start()
        // Hold the timer on the panel so it survives reparenting.
        root.putClientProperty("hermes-chat.timer", timer)
    }

    val component: JComponent get() = root

    // ------------------------------------------------------------------

    private fun refreshStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Make sure we have a session token before any authenticated
            // call. The first probe gets a chance to scrape the index.html;
            // subsequent probes short-circuit (see HermesClient.ensureToken).
            //
            // Bug fix: reachable=true does NOT guarantee that the session
            // token has been auto-fetched yet. /api/status is public, so it
            // can succeed on the very first tick while DashboardTokenFetcher
            // (which runs on the same pooled thread right above) is still
            // racing on a separate connection or has just timed out. If we
            // dispatch fetchModelsAsync() in that window, /api/model/options
            // returns 401, the client returns an empty list, and
            // footer.setModels() resets the picker to "(no models available)"
            // and disables it. Even though the next tick (8s later) will
            // successfully auto-fetch the token and get a real list, the
            // user sees a broken picker for those 8 seconds — and in some
            // sandbox paths the token fetch never recovers.
            //
            // Fix: only kick off the authenticated model fetch when there's
            // actually a token to send. Otherwise log and let the next tick
            // (which will re-enter ensureToken first) handle it.
            log.info("refreshStatus: tick (autoToken=${client.hasAutoToken()}, endpoint=${client.getState().endpoint})")
            client.ensureToken()
            val reachable = client.isReachable()
            log.info("refreshStatus: isReachable=$reachable")
            ApplicationManager.getApplication().invokeLater {
                if (reachable) {
                    log.info("refreshStatus: reachable, kicking off fetchStatus/fetchModels")
                    // Fetch full status for the version string once we're
                    // sure the dashboard is up.
                    client.fetchStatusAsync { status ->
                        log.info("refreshStatus: fetchStatus returned version=${status?.version}")
                        renderStatus(status)
                    }
                    if (client.hasAutoToken() || client.getState().sessionToken.isNotBlank()) {
                        client.fetchModelsAsync { modelList ->
                            log.info(
                                "refreshStatus: fetchModels returned ${modelList.options.size} models " +
                                    "(currentModelId=${modelList.currentModelId})"
                            )
                            // Footer is now read-only — just reflect the current model
                            val currentLabel = modelList.options
                                .firstOrNull { it.id == modelList.currentModelId }?.label
                            footer.setCurrentModel(currentLabel)
                        }
                    } else {
                        log.info("refreshStatus: reachable but no token yet — deferring model fetch to next tick")
                        // Surface the fetch error in the footer so the user
                        // sees *why* the model list is empty, and gets a
                        // "↻ Retry" button to override the autoToken latch.
                        // Without this, the footer stays "Model: (loading…)"
                        // forever after the first failed fetch.
                        val err = client.lastTokenError()
                        if (err != null) {
                            footer.setTokenError(err)
                        }
                    }
                    // If the browser hasn't been initialized yet, do it now —
                    // we know the dashboard is up so loading /chat will work.
                    ensureBrowser()
                } else {
                    log.info("refreshStatus: UNREACHABLE — skipping model fetch")
                    renderUnreachable()
                }
            }
        }
    }

    private fun renderStatus(status: HermesStatus?) {
        val version = status?.version ?: "unknown"
        header.text = "  Hermes $version — connected"
        header.foreground = JBColor(Color(0x2E7D32), Color(0x81C784))
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                openInExternalBrowser()
            }
        })
    }

    private fun renderUnreachable() {
        header.text = "  Hermes dashboard unreachable — start with: hermes dashboard"
        header.foreground = JBColor(Color(0xC62828), Color(0xEF9A9A))
    }

    private fun ensureBrowser() {
        if (browser != null) return

        val isSupported = try {
            com.intellij.ui.jcef.JBCefApp.isSupported()
        } catch (t: Throwable) {
            log.warn("JBCefApp.isSupported() threw", t)
            false
        }

        if (!isSupported) {
            if (!isFallbackAdded) {
                log.warn(
                    "JCEF not supported by the IDE runtime, falling back to 'Open in browser' link. " +
                            "If you're running inside `./gradlew runIde`, this usually means the sandbox " +
                            "JBR is missing the Cef native binaries. Real Android Studio installs are unaffected."
                )
                browserHost.add(buildFallbackLink(), BorderLayout.CENTER)
                browserHost.revalidate()
                isFallbackAdded = true
            }
            return
        }

        try {
            val jbBrowser = JBCefBrowserBuilder()
                .setUrl(chatUrl())
                .setOffScreenRendering(true)
                .build()

            jbBrowser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: org.cef.browser.CefFrame, httpStatusCode: Int) {
                    log.info("Hermes chat page loaded (status=$httpStatusCode)")
                }
                override fun onLoadError(
                    cefBrowser: CefBrowser,
                    frame: CefFrame,
                    errorCode: CefLoadHandler.ErrorCode,
                    errorText: String,
                    failedUrl: String
                ) {
                    log.warn("Hermes chat page failed to load: $errorText (url=$failedUrl, code=$errorCode)")
                    ApplicationManager.getApplication().invokeLater {
                        header.text = "  Load Error: $errorText ($errorCode)"
                        header.foreground = JBColor.RED
                    }
                }
            }, jbBrowser.cefBrowser)

            val uiComp = jbBrowser.component
            browserHost.add(uiComp, BorderLayout.CENTER)
            browserHost.revalidate()
            browserHost.repaint()
            browser = jbBrowser.cefBrowser
        } catch (t: Throwable) {
            log.warn("JBCefBrowserBuilder.build() threw, falling back to 'Open in browser' link", t)
            browserHost.add(buildFallbackLink(), BorderLayout.CENTER)
            browserHost.revalidate()
        }
    }

    private fun buildFallbackLink(): JComponent {
        val link = com.intellij.ui.HyperlinkLabel("Open Hermes Chat in your browser")
        link.addHyperlinkListener { openInExternalBrowser() }
        val wrap = JBPanel<JBPanel<*>>(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            anchor = GridBagConstraints.CENTER
        }
        wrap.add(link, gbc)
        return wrap
    }

    private fun chatUrl(): String {
        val endpoint = client.getState().endpoint.trim().ifEmpty { "http://127.0.0.1:9119" }
        // The dashboard exposes its chat surface at /chat. We pass the
        // session token via URL fragment so it never lands in HTTP logs.
        val token = client.resolveToken() ?: ""
        return if (token.isBlank()) "$endpoint/chat"
        else "$endpoint/chat#token=$token"
    }

    private fun openInExternalBrowser() {
        val url = chatUrl()
        ApplicationManager.getApplication().executeOnPooledThread {
            BrowserUtil.browse(url)
        }
    }

    // ------------------------------------------------------------------
    // Restart button handler

    /**
     * Fires the dashboard restart. Disables the button + swaps the
     * status label to "Restarting…" so the user gets immediate feedback.
     * The actual work happens in [DashboardProcessManager] off the EDT.
     */
    private fun onRestartClicked() {
        if (!refreshButton.isEnabled) return  // already in flight
        refreshButton.isEnabled = false
        header.text = "  Restarting dashboard…"
        log.info("HermesChatPanel: user clicked ↻ — restarting dashboard")

        DashboardProcessManager().restartDashboard { result ->
            refreshButton.isEnabled = true
            if (result.success) {
                // Trigger an immediate status probe so the green/grey
                // dot updates without waiting for the 8s timer. The
                // /api/status hit will succeed now that the port is up.
                refreshStatus()
                // JCEF is also reloaded so the embedded chat page picks
                // up any dashboard-side state changes (new session, etc).
                browser?.loadURL(chatUrl())
                DashboardProcessManager.notify(result.message, NotificationType.INFORMATION)
            } else {
                // Don't clobber the "Hermes dashboard unreachable" state —
                // the user will see that on the next 8s tick. We just
                // surface what went wrong in a balloon.
                log.warn("HermesChatPanel: restart failed: ${result.message}")
                DashboardProcessManager.notify("Restart failed: ${result.message}", NotificationType.ERROR)
            }
        }
    }

    /**
     * Fires when the user clicks the "↻ Retry" button in the footer
     * (visible when the last token fetch failed). Clears the autoToken
     * latch in HermesClient so [HermesClient.ensureToken] is allowed
     * to try again, then immediately re-runs the status probe so the
     * new attempt's outcome is reflected in the footer without
     * waiting for the next 8s tick.
     *
     * Runs the token fetch on a pooled thread (mirroring the
     * [refreshStatus] threading model) — the underlying HTTP call has
     * a 2s timeout, so worst case the user sees a 2s pause before
     * the footer updates. The button is disabled for that window so
     * a frantic user can't queue up multiple retries.
     */
    private fun onRetryTokenClicked() {
        log.info("HermesChatPanel: user clicked ↻ Retry — re-attempting token fetch")
        // Clear the error display immediately so the user sees feedback
        // (footer flips back to "Model: (loading…)" while we retry).
        footer.setTokenError(null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val gotToken = client.retryTokenFetch()
            ApplicationManager.getApplication().invokeLater {
                if (gotToken) {
                    log.info("HermesChatPanel: token retry succeeded — re-running status probe")
                    // refreshStatus will pick up the now-cached token and
                    // populate the model list + clear the error state.
                    refreshStatus()
                } else {
                    val err = client.lastTokenError() ?: "(unknown error)"
                    log.warn("HermesChatPanel: token retry failed: $err")
                    footer.setTokenError(err)
                }
            }
        }
    }
}

/**
 * Footer toolbar — current model display (read-only) + open-in-browser shortcut.
 * Model selection is handled by the embedded dashboard SPA; the IDE shell only
 * reflects the currently-active model.
 *
 * Stability note: refreshStatus() ticks every 8s. Transient failures of
 * /api/model/options (auth retry, blank currentModelId, empty list) used to
 * overwrite a previously-known good label with "—", causing the footer to
 * flicker between "Model: MiniMax-M3" and "Model: —". The footer now keeps
 * the last non-empty label it successfully received; transient null/blank
 * inputs are no-ops. Initial state is "Model: (loading…)" so the user can
 * distinguish "haven't heard back yet" from "explicitly no model".
 *
 * Error state: when [setTokenError] is called with a non-null message, the
 * label switches to "Model: ✗ {message}" in red and a "↻ Retry" button
 * appears next to the "Open in browser" button. The button is wired up by
 * [onRetryTokenFetch] in HermesChatPanel. Calling [setTokenError] with
 * null returns the footer to the normal "Model: …" state.
 */
private class FooterPanel {
    private val modelLabel = JBLabel("Model: (loading…)").apply {
        foreground = UIUtil.getInactiveTextColor() // gray / disabled look
        border = JBUI.Borders.emptyLeft(8)
    }
    // The "Model: ✗ {message}" / "Model: {name}" prefix differs by state.
    // We keep the bare label text and rebuild the prefix in setCurrentModel
    // and setTokenError so there's no need to mutate a shared "Model: " string.
    private val retryButton = JButton("↻ Retry").apply {
        isVisible = false
        toolTipText = "Retry fetching the dashboard session token"
        addActionListener { onRetryTokenFetch?.invoke() }
    }
    private val openBrowserBtn = JButton("Open in browser").apply {
        addActionListener { onOpenInBrowser?.invoke() }
    }
    // East-side button row: retry (hidden when no error) + open-in-browser.
    // We pack them in a sub-panel so BorderLayout.EAST stays a single
    // component while still showing both buttons inline.
    private val eastRow = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(retryButton)
        add(Box.createHorizontalStrut(4))
        add(openBrowserBtn)
    }
    private val panel = JBPanel<JBPanel<*>>().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4, 8)
        val leftWrap = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(modelLabel, BorderLayout.CENTER)
        }
        add(leftWrap, BorderLayout.CENTER)
        add(eastRow, BorderLayout.EAST)
    }

    var onOpenInBrowser: (() -> Unit)? = null
    var onRetryTokenFetch: (() -> Unit)? = null

    val component: JComponent get() = panel

    /**
     * Updates the displayed current model.
     *
     * @param modelLabelText  The human-readable model label from the dashboard,
     *                        or null/blank when this tick's fetch was unsuccessful
     *                        (transient 401, empty list, missing currentModelId).
     *                        Null/blank inputs are ignored once we have a known-good
     *                        label, so a single failed tick can't blank out the UI.
     *
     * Calling this with a non-blank value clears the error state set by
     * [setTokenError] — the model becoming available implies the token
     * fetch succeeded.
     */
    fun setCurrentModel(modelLabelText: String?) {
        // Drop null/blank — preserve last known-good label.
        val resolved = modelLabelText?.takeIf { it.isNotBlank() } ?: return
        ApplicationManager.getApplication().invokeLater {
            modelLabel.text = "Model: $resolved"
            modelLabel.foreground = UIUtil.getLabelForeground()
            retryButton.isVisible = false
        }
    }

    /**
     * Show a token-fetch error in place of the model label, plus a Retry
     * button. Pass null to clear (back to the model-label state).
     */
    fun setTokenError(message: String?) {
        ApplicationManager.getApplication().invokeLater {
            if (message == null) {
                // Cleared externally — restore the default look. The next
                // refreshStatus() tick will repopulate the model label.
                modelLabel.foreground = UIUtil.getInactiveTextColor()
                retryButton.isVisible = false
            } else {
                // The "✗ " glyph is a heavy ballot X (U+2717) — rendered
                // by any platform font on IntelliJ 2024.2+; falls back
                // to a textual "(error)" if the glyph is missing.
                modelLabel.text = "Model: ✗ $message"
                modelLabel.foreground = JBColor(
                    Color(0xC62828),  // light theme: dark red
                    Color(0xEF9A9A),  // dark theme:  light red
                )
                retryButton.isVisible = true
            }
        }
    }
}

// ----------------------------------------------------------------------
// Helper accessors — keeps the import block in HermesChatPanel short.

private fun logger(klass: Class<*>): com.intellij.openapi.diagnostic.Logger =
    com.intellij.openapi.diagnostic.Logger.getInstance(klass)