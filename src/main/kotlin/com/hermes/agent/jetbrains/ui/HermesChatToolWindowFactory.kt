package com.hermes.agent.jetbrains.ui

import com.hermes.agent.jetbrains.client.HermesClient
import com.hermes.agent.jetbrains.model.HermesStatus
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

    // The browser is created lazily because JCEF requires the IDE-level
    // CefApp to be initialized, which happens on first request.
    private var browser: CefBrowser? = null
    private var isFallbackAdded = false
    private val browserHost = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        background = JBColor.PanelBackground
        border = BorderFactory.createMatteBorder(1, 0, 1, 0, JBColor.border())
    }

    private val root: JPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(browserHost, BorderLayout.CENTER)
        add(footer.component, BorderLayout.SOUTH)
        preferredSize = Dimension(420, 600)
    }

    init {
        footer.onOpenInBrowser = { openInExternalBrowser() }
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
        val token = client.getState().sessionToken
        return if (token.isBlank()) "$endpoint/chat"
        else "$endpoint/chat#token=$token"
    }

    private fun openInExternalBrowser() {
        val url = chatUrl()
        ApplicationManager.getApplication().executeOnPooledThread {
            BrowserUtil.browse(url)
        }
    }
}

/**
 * Footer toolbar — current model display (read-only) + open-in-browser shortcut.
 * Model selection is handled by the embedded dashboard SPA; the IDE shell only
 * reflects the currently-active model.
 */
private class FooterPanel {
    private val modelLabel = JBLabel("Model: —").apply {
        foreground = UIUtil.getInactiveTextColor() // gray / disabled look
        border = JBUI.Borders.emptyLeft(8)
    }
    private val openBrowserBtn = JButton("Open in browser").apply {
        addActionListener { onOpenInBrowser?.invoke() }
    }
    private val panel = JBPanel<JBPanel<*>>().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4, 8)
        val leftWrap = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(modelLabel, BorderLayout.CENTER)
        }
        add(leftWrap, BorderLayout.CENTER)
        add(openBrowserBtn, BorderLayout.EAST)
    }

    var onOpenInBrowser: (() -> Unit)? = null

    val component: JComponent get() = panel

    /**
     * Updates the displayed current model. Called from refreshStatus()
     * when the dashboard returns a new currentModelId.
     */
    fun setCurrentModel(modelLabelText: String?) {
        ApplicationManager.getApplication().invokeLater {
            modelLabel.text = "Model: ${modelLabelText ?: "—"}"
        }
    }
}

// ----------------------------------------------------------------------
// Helper accessors — keeps the import block in HermesChatPanel short.

private fun logger(klass: Class<*>): com.intellij.openapi.diagnostic.Logger =
    com.intellij.openapi.diagnostic.Logger.getInstance(klass)