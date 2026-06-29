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
 *   │ Model: [claude-opus-4 ▼]  [Open in browser]  │   ← footer toolbar
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
        footer.onModelSelected = { id ->
            ApplicationManager.getApplication().executeOnPooledThread {
                client.setActiveModel(id)
            }
        }

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
            val reachable = client.isReachable()
            ApplicationManager.getApplication().invokeLater {
                if (reachable) {
                    // Fetch full status for the version string once we're
                    // sure the dashboard is up.
                    client.fetchStatusAsync { status ->
                        renderStatus(status)
                    }
                    client.fetchModelsAsync { models ->
                        footer.setModels(models)
                    }
                    // If the browser hasn't been initialized yet, do it now —
                    // we know the dashboard is up so loading /chat will work.
                    ensureBrowser()
                } else {
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
            false
        }

        if (!isSupported) {
            log.warn("JCEF not supported by the IDE runtime, falling back to 'Open in browser' link")
            browserHost.add(buildFallbackLink(), BorderLayout.CENTER)
            browserHost.revalidate()
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
            }, jbBrowser.cefBrowser)

            val uiComp = jbBrowser.component
            browserHost.add(uiComp, BorderLayout.CENTER)
            browserHost.revalidate()
            browserHost.repaint()
            browser = jbBrowser.cefBrowser
        } catch (t: Throwable) {
            log.warn("JCEF not available, falling back to 'Open in browser' link", t)
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
 * Footer toolbar — model picker + open-in-browser shortcut.
 */
private class FooterPanel {
    private val modelPicker = com.intellij.openapi.ui.ComboBox<String>().apply {
        isEditable = false
        // Default placeholder; replaced when /api/model/options responds.
        addItem("(no models yet)")
    }
    private val openBrowserBtn = JButton("Open in browser").apply {
        addActionListener { onOpenInBrowser?.invoke() }
    }
    private val panel = JBPanel<JBPanel<*>>().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4, 8)
        val left = JBLabel("Model: ").apply {
            displayedMnemonicIndex = 0 // 'M' in "Model: "
            labelFor = modelPicker
        }
        val leftWrap = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(left, BorderLayout.WEST)
            add(modelPicker, BorderLayout.CENTER)
        }
        add(leftWrap, BorderLayout.CENTER)
        add(openBrowserBtn, BorderLayout.EAST)
    }

    var onOpenInBrowser: (() -> Unit)? = null
    var onModelSelected: ((String) -> Unit)? = null

    init {
        modelPicker.addActionListener {
            val selected = modelPicker.selectedItem as? String ?: return@addActionListener
            // The combo stores "label (id)" so the action handler can pick
            // the id back out from the bracket position.
            val id = extractIdFromLabel(selected) ?: selected
            onModelSelected?.invoke(id)
        }
    }

    val component: JComponent get() = panel

    fun setModels(models: List<com.hermes.agent.jetbrains.model.HermesModelOption>) {
        if (models.isEmpty()) return
        modelPicker.removeAllItems()
        for (m in models) {
            // We display "label (id)" so the picker shows a human-readable
            // name but the action listener still receives the real model id.
            modelPicker.addItem("${m.label} (${m.id})")
        }
    }

    private fun extractIdFromLabel(label: String): String? {
        val open = label.lastIndexOf('(')
        val close = label.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return label.substring(open + 1, close)
    }
}

// ----------------------------------------------------------------------
// Helper accessors — keeps the import block in HermesChatPanel short.

private fun logger(klass: Class<*>): com.intellij.openapi.diagnostic.Logger =
    com.intellij.openapi.diagnostic.Logger.getInstance(klass)