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
        foreground = Color.BLACK
    }

    internal fun headerForTest(): JBLabel = header
    private val footerByLazy = lazy { FooterPanel() }
    private val footer: FooterPanel get() = footerByLazy.value

    private var _statusTimer: javax.swing.Timer? = null
    private val statusTimer: javax.swing.Timer?
        get() {
            if (ApplicationManager.getApplication() == null) return null
            if (_statusTimer == null) {
                _statusTimer = javax.swing.Timer(PROBE_INTERVAL_MS) { refreshStatus() }
            }
            return _statusTimer
        }

    private val refreshButton = JButton("↻").apply {
        toolTipText = "Restart Hermes dashboard in WSL"
        isFocusable = false
        margin = JBUI.insets(2, 6)
        addActionListener { onRestartClicked() }
    }

    private val headerRow: JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(2, 8)
        add(header, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)
    }

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
                            val currentLabel = modelList.options
                                .firstOrNull { it.id == modelList.currentModelId }?.label
                            footer.setCurrentModel(currentLabel)
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

    private fun ensureBrowser() {
        if (browser != null) return
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

    private fun onRestartClicked() {
        if (!refreshButton.isEnabled) return
        refreshButton.isEnabled = false
        header.text = "  Restarting dashboard…"
        DashboardProcessManager().restartDashboard { result ->
            refreshButton.isEnabled = true
            if (result.success) {
                refreshStatus()
                browser?.loadURL(chatUrl())
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

private class FooterPanel {
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
        val resolved = modelLabelText?.takeIf { it.isNotBlank() } ?: return
        ApplicationManager.getApplication().invokeLater {
            modelLabel.text = "Model: $resolved"
            modelLabel.foreground = UIUtil.getLabelForeground()
            retryButton.isVisible = false
        }
    }

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
