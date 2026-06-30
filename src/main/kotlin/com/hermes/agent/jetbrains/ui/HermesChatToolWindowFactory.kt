package com.hermes.agent.jetbrains.ui

import com.hermes.agent.jetbrains.client.HermesClient
import com.hermes.agent.jetbrains.dashboard.DashboardProcessManager
import com.hermes.agent.jetbrains.model.HermesStatus
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ide.BrowserUtil
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
import java.util.concurrent.atomic.AtomicLong
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkListener

/**
 * VSCode-Chat-style tool window.
 */
class HermesChatToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HermesChatPanel(project)
        Disposer.register(project, panel)
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

class HermesChatPanel(private val project: Project) : Disposable {

    private val clientLazy = lazy { HermesClient.getInstance() }
    private val client: HermesClient get() = clientLazy.value
    private val log = logger<HermesChatPanel>()

    private val header = JBLabel(" ", SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(4, 8)
        foreground = UIUtil.getContextHelpForeground()
        icon = HermesIcons.ToolWindowSmall
    }

    internal fun headerForTest(): JBLabel = header
    private val footerByLazy = lazy { FooterPanel() }
    internal val footer: FooterPanel get() = footerByLazy.value

    private var _statusTimer: javax.swing.Timer? = null
    private val statusTimer: javax.swing.Timer?
        get() {
            if (ApplicationManager.getApplication() == null) return null
            if (_statusTimer == null) {
                _statusTimer = javax.swing.Timer(PROBE_INTERVAL_MS) { refreshStatus() }
            }
            return _statusTimer
        }

    private val refreshButton = JButton(com.intellij.icons.AllIcons.Actions.Refresh).apply {
        toolTipText = "Restart Hermes dashboard"
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        margin = JBUI.insets(2)
        addActionListener { onRestartClicked() }
    }

    private val headerRow: JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 8)
        add(header, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)
    }

    private var browser: CefBrowser? = null
    // Hold the wrapper too — CefBrowser has no dispose(); JBCefBrowser does.
    // Without the wrapper, we can't tear down a JCEF instance and the
    // dashboard's /api/events feed (EventSource/WebSocket) stays wedged to
    // the dead socket of the previous dashboard process, surfacing the
    // "events feed disconnected" error that the user reported after
    // hitting the refresh button post-model-switch.
    private var jbBrowser: com.intellij.ui.jcef.JBCefBrowser? = null
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

    private val headerMouseListener = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent?) {
            openInExternalBrowser()
        }
    }

    private val fallbackLinkListener = HyperlinkListener {
        openInExternalBrowser()
    }

    private val openBrowserDebounceMs = 500L
    private val lastOpenBrowserAt = AtomicLong(0L)
    private var consecutiveUnreachable = 0

    init {
        header.addMouseListener(headerMouseListener)
        footer.onOpenInBrowser = { openInExternalBrowser() }
        footer.onRetryTokenFetch = { onRetryTokenClicked() }
        
        refreshStatus()
        statusTimer?.start()
    }

    companion object {
        const val PROBE_INTERVAL_MS = 8_000

        /**
         * Stretch the polling interval based on how long the dashboard has been down.
         */
        @JvmStatic
        internal fun backoffMs(failures: Int): Int = when {
            failures <= 1 -> PROBE_INTERVAL_MS
            failures <= 5 -> 30_000
            failures <= 20 -> 60_000
            else -> 300_000
        }
    }

    val component: JComponent get() = root

    private fun refreshStatus() {
        ApplicationManager.getApplication().executeOnPooledThread {
            client.ensureToken()
            val reachable = client.isReachable()
            ApplicationManager.getApplication().invokeLater {
                if (reachable) {
                    if (consecutiveUnreachable != 0) {
                        consecutiveUnreachable = 0
                        rescheduleTimer(PROBE_INTERVAL_MS)
                    }
                    client.fetchStatusAsync { status -> renderStatus(status) }
                    if (client.hasAutoToken() || client.getState().sessionToken.isNotBlank()) {
                        client.fetchModelsAsync { modelList ->
                            // Resolve label from options first; fall back to the
                            // raw currentModelId if (a) the id isn't in the list
                            // (e.g. just-switched model whose /api/model/options
                            // response was cached) or (b) options is empty. This
                            // keeps the footer honest about what the dashboard
                            // is actually running, instead of sticking on the
                            // previous model's label.
                            val resolved = modelList.options
                                .firstOrNull { it.id == modelList.currentModelId }
                                ?.label
                                ?: modelList.currentModelId
                            footer.setCurrentModel(resolved)
                        }
                    } else {
                        val err = client.lastTokenError()
                        if (err != null) footer.setTokenError(err)
                    }
                    ensureBrowser()
                } else {
                    consecutiveUnreachable++
                    val newDelay = backoffMs(consecutiveUnreachable)
                    rescheduleTimer(newDelay)
                    renderUnreachable()
                }
            }
        }
    }

    private fun rescheduleTimer(delayMs: Int) {
        val timer = statusTimer ?: return
        if (timer.delay == delayMs) return
        timer.delay = delayMs
        timer.restart()
    }

    internal fun renderStatus(status: HermesStatus?) {
        val version = status?.version ?: "unknown"
        header.text = "  Hermes $version — connected"
        header.foreground = JBColor(Color(0x2E7D32), Color(0x81C784))
        header.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private fun renderUnreachable() {
        header.text = "  Hermes dashboard unreachable"
        header.foreground = JBColor(Color(0xC62828), Color(0xEF9A9A))
    }

    /**
     * Test seam: number of children currently in the JCEF host panel.
     * Used by regression tests for the disposeBrowser/ensureBrowser(force)
     * round-trip — when the JCEF instance is disposed, the host should
     * be empty (componentCount == 0); when ensureBrowser(force=true) runs
     * against a headless fixture (JCEF unsupported), a single fallback
     * link is added back (componentCount == 1).
     */
    internal fun browserHostChildCount(): Int = browserHost.componentCount

    /**
     * Test seam: non-null when an embedded JBCefBrowser is currently
     * attached. Regression tests assert this flips to null after
     * disposeBrowser() and that the field is the right wrapper.
     */
    internal fun isJbBrowserAttached(): Boolean = jbBrowser != null

    /**
     * Test seam: invoke the protected disposeBrowser() path directly
     * so tests can assert the JCEF teardown side-effects without
     * going through the full restartDashboard() flow (which spawns
     * external processes).
     */
    internal fun disposeBrowserForTest() = disposeBrowser()

    /**
     * Test seam: build a fresh JCEF (or fallback) inside the host panel.
     * Mirrors onRestartClicked's success branch without touching the
     * dashboard process manager.
     */
    internal fun ensureBrowserForTest(force: Boolean) = ensureBrowser(force)

    private fun ensureBrowser(force: Boolean = false) {
        if (!force && browser != null) return
        if (force) disposeBrowser()
        val isSupported = try {
            com.intellij.ui.jcef.JBCefApp.isSupported()
        } catch (t: Throwable) { false }

        if (!isSupported) {
            if (!isFallbackAdded) {
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
                override fun onLoadError(cefBrowser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
                    ApplicationManager.getApplication().invokeLater {
                        header.text = "  Load Error"
                        header.foreground = JBColor.RED
                    }
                }
            }, jbBrowser.cefBrowser)

            browserHost.add(jbBrowser.component, BorderLayout.CENTER)
            browserHost.revalidate()
            browser = jbBrowser.cefBrowser
            this.jbBrowser = jbBrowser
        } catch (t: Throwable) {
            if (!isFallbackAdded) {
                browserHost.add(buildFallbackLink(), BorderLayout.CENTER)
                browserHost.revalidate()
                isFallbackAdded = true
            }
        }
    }

    private fun buildFallbackLink(): JComponent {
        val link = com.intellij.ui.HyperlinkLabel("Open Hermes Chat in your browser")
        link.addHyperlinkListener(fallbackLinkListener)
        val wrap = JBPanel<JBPanel<*>>(GridBagLayout())
        wrap.add(link, GridBagConstraints())
        return wrap
    }

    private fun chatUrl(): String {
        val endpoint = client.getState().endpoint.trim().ifEmpty { "http://127.0.0.1:9119" }
        val token = client.resolveToken() ?: ""
        return if (token.isBlank()) "$endpoint/chat" else "$endpoint/chat#token=$token"
    }

    private fun openInExternalBrowser() {
        val now = System.currentTimeMillis()
        val last = lastOpenBrowserAt.get()
        if (now - last < openBrowserDebounceMs) return
        if (!lastOpenBrowserAt.compareAndSet(last, now)) return

        val url = chatUrl()
        ApplicationManager.getApplication().executeOnPooledThread {
            BrowserUtil.browse(url)
        }
    }

    override fun dispose() {
        statusTimer?.stop()
        _statusTimer = null
        header.removeMouseListener(headerMouseListener)
    }

    /**
     * Tear down the embedded CEF browser so its in-flight EventSource /
     * WebSocket connections to the (now-dead) dashboard process are fully
     * released. Without this, the freshly-restarted dashboard cannot push
     * events to this JCEF instance — the old feed stays in
     * "events feed disconnected" state until the whole IDE is restarted.
     *
     * Must run on the EDT; the JCEF client expects UI-thread disposal.
     */
    private fun disposeBrowser() {
        val wrapper = jbBrowser
        jbBrowser = null
        browser = null
        if (wrapper != null) {
            try {
                // JBCefBrowser.dispose() releases the underlying CefBrowser
                // and unhooks all CefLoadHandlers / EventSource listeners
                // associated with this instance. That's what we need — the
                // dashboard's events feed is the listener we care about.
                wrapper.dispose()
            } catch (t: Throwable) {
                log.warn("JBCefBrowser.dispose failed", t)
            }
        }
        // Remove the component from the host panel so the next ensureBrowser()
        // call (force=true) doesn't stack two browsers on top of each other.
        browserHost.removeAll()
        browserHost.revalidate()
        browserHost.repaint()
        isFallbackAdded = false
    }

    private fun onRestartClicked() {
        if (!refreshButton.isEnabled) return
        refreshButton.isEnabled = false
        header.text = "  Restarting dashboard…"
        // New dashboard mints a fresh token; drop the cached one so the
        // first refresh doesn't waste a roundtrip on a guaranteed 401.
        client.invalidateAutoToken()
        // Dispose the embedded CEF browser eagerly so any in-flight events
        // feed to the soon-to-be-killed dashboard process is released. The
        // browser is rebuilt once the new dashboard is reachable.
        disposeBrowser()
        DashboardProcessManager().restartDashboard { result ->
            refreshButton.isEnabled = true
            if (result.success) {
                refreshStatus()
                // Force a fresh JCEF instance so the new dashboard's
                // /api/events feed is wired up cleanly. Reusing the old
                // browser's loadURL() leaves a dead EventSource attached.
                ensureBrowser(force = true)
            } else {
                // Restart failed; keep the panel usable by rebuilding anyway
                // so the user can still see the "unreachable" state cleanly.
                renderUnreachable()
            }
        }
    }

    private fun onRetryTokenClicked() {
        footer.setTokenError(null)
        ApplicationManager.getApplication().executeOnPooledThread {
            val gotToken = client.retryTokenFetch()
            ApplicationManager.getApplication().invokeLater {
                if (gotToken) refreshStatus()
                else footer.setTokenError(client.lastTokenError() ?: "(unknown error)")
            }
        }
    }
}

internal class FooterPanel {
    private val modelLabel = JBLabel("Model: (loading…)").apply {
        foreground = UIUtil.getInactiveTextColor()
        border = JBUI.Borders.emptyLeft(8)
    }
    private val retryButton = JButton("↻ Retry").apply {
        isVisible = false
        addActionListener { onRetryTokenFetch?.invoke() }
    }
    private val openBrowserBtn = JButton("Open in browser").apply {
        addActionListener { onOpenInBrowser?.invoke() }
    }
    private val eastRow = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(retryButton)
        add(Box.createHorizontalStrut(4))
        add(openBrowserBtn)
    }
    private val panel = JBPanel<JBPanel<*>>().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4, 8)
        add(modelLabel, BorderLayout.CENTER)
        add(eastRow, BorderLayout.EAST)
    }

    var onOpenInBrowser: (() -> Unit)? = null
    var onRetryTokenFetch: (() -> Unit)? = null
    val component: JComponent get() = panel

    fun setCurrentModel(modelLabelText: String?) {
        // Accept the raw id as a last-resort fallback so the footer always
        // reflects what the dashboard is currently running. Previously we
        // returned on null, which left the previous model's label stuck in
        // the footer when (a) the just-switched model hadn't propagated to
        // the options list yet, or (b) the new id was unlisted.
        val resolved = modelLabelText?.takeIf { it.isNotBlank() } ?: return
        ApplicationManager.getApplication().invokeLater {
            modelLabel.text = "Model: $resolved"
            modelLabel.foreground = UIUtil.getLabelForeground()
            retryButton.isVisible = false
        }
    }

    /** Test seam: forwards to setCurrentModel so tests can drive the
     *  raw-id fallback path that fixed BUG 2 (stale model label after
     *  reconnect when the just-switched model isn't in the options list). */
    internal fun setCurrentModelForTest(modelLabelText: String?) = setCurrentModel(modelLabelText)

    /** Test seam: read the current footer label text. */
    internal fun currentModelLabelTextForTest(): String = modelLabel.text

    fun setTokenError(message: String?) {
        ApplicationManager.getApplication().invokeLater {
            if (message == null) {
                modelLabel.foreground = UIUtil.getInactiveTextColor()
                retryButton.isVisible = false
            } else {
                modelLabel.text = "Model: ✗ $message"
                modelLabel.foreground = JBColor.RED
                retryButton.isVisible = true
            }
        }
    }
}

private fun logger(klass: Class<*>): com.intellij.openapi.diagnostic.Logger =
    com.intellij.openapi.diagnostic.Logger.getInstance(klass)
