package com.hermes.agent.jetbrains.settings

import com.hermes.agent.jetbrains.client.HermesClient
import com.hermes.agent.jetbrains.dashboard.DashboardProcessManager
import com.hermes.agent.jetbrains.dashboard.WslDetector
import com.hermes.agent.jetbrains.model.HermesModelList
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Settings panel under Preferences → Tools → Hermes Chat.
 *
 * Three fields:
 *   1. Dashboard endpoint   — `http://127.0.0.1:9119` by default
 *   2. Session token        — copied from the dashboard's startup log
 *   3. Default model        — populated lazily from /api/model/options
 *
 * The "Test connection" button probes /api/status and shows the dashboard
 * version on success or the error message on failure. It does NOT modify
 * settings — that only happens on Apply.
 *
 * Validation contract: we never throw on unreachable — instead we surface
 * a banner and let the user save anyway. This matches how the dashboard
 * itself behaves when launched without network.
 */
class HermesChatConfigurable : Configurable {

    private val client = HermesClient.getInstance()
    private val endpointField = JBTextField(client.getState().endpoint).apply {
        toolTipText = "Default http://127.0.0.1:9119 — point to wherever your hermes dashboard runs"
    }
    private val tokenField = JBTextField(client.getState().sessionToken).apply {
        toolTipText = "Paste the token printed when 'hermes dashboard' started"
    }
    private val modelPicker = ComboBox<String>().apply {
        isEditable = false
        addItem(client.getState().defaultModelId.ifEmpty { "(default — unchanged)" })
    }
    private val statusLabel = JBLabel(" ")
    private val testButton = JButton("Test connection")
    private val manageAutomaticallyCheckbox = JCheckBox(
        "Manage dashboard automatically (restart it from the IDE when needed)",
        client.getState().manageAutomatically,
    ).apply {
        toolTipText = "When on, the plugin will spawn and restart the Hermes dashboard " +
            "process for you. Turn this off if you manage the dashboard yourself " +
            "(systemd, launchd, Docker, a separate terminal, etc.) — the plugin will " +
            "still connect to it as a client."
    }
    /**
     * Windows-only: "Use WSL for dashboard" toggle. Hidden entirely
     * when WSL is not installed on the host — we don't want to show
     * the user an option they can't actually use. The detector caches
     * its probe result so opening Settings repeatedly is cheap.
     *
     * On non-Windows hosts the detector returns false so the checkbox
     * stays hidden — the field exists in State regardless, it just
     * isn't surfaced in the UI.
     *
     * On change: only the State is updated. The new value is read on
     * the next dashboard restart (the tool-window 🔄 button), which
     * is when [DashboardProcessStrategy.forCurrentOs] actually picks
     * a strategy. We deliberately don't trigger a restart from
     * Settings — that would interrupt any in-flight chat session.
     */
    private val wslDetector = WslDetector()
    private val useWslCheckbox = JCheckBox(
        "Use WSL for dashboard",
        client.getState().useWsl,
    ).apply {
        toolTipText = "On: run the dashboard inside WSL via `wsl.exe bash -lc \"hermes dashboard …\"`. " +
            "Off: run a native `hermes.exe` from your Windows PATH. " +
            "Takes effect on the next dashboard restart (🔄 in the tool window)."
        isVisible = wslDetector.isInstalled()
    }
    /**
     * Manual-launch instructions. Hidden by default; shown only when
     * [manageAutomaticallyCheckbox] is unchecked so the user knows how to
     * start the dashboard without the plugin's help.
     *
     * JTextArea (not a JLabel) because we need multi-line text with
     * monospace styling — copy-paste-friendly for a shell command.
     */
    private val manualLaunchHint = JTextArea(buildManualLaunchHint()).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 0)
        font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12)
        isVisible = !client.getState().manageAutomatically
    }

    init {
        testButton.addActionListener { runTestConnection() }
        manageAutomaticallyCheckbox.addActionListener {
            // Show the manual-launch hint when the user un-checks the box,
            // hide it when they re-check. Done in the listener so the UI
            // reacts immediately, before they hit Apply.
            manualLaunchHint.isVisible = !manageAutomaticallyCheckbox.isSelected
        }
    }

    override fun getDisplayName(): String = "Hermes Chat"

    override fun createComponent(): JComponent {
        // FormBuilder gives us consistent label-field alignment across
        // light/dark themes without pulling in a UI designer.
        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Dashboard endpoint:"), endpointField, 1, false)
            .addLabeledComponent(JBLabel("Session token:"), tokenField, 1, false)
            .addTooltip("Paste the token printed when 'hermes dashboard' started")
            .addLabeledComponent(JBLabel("Default model:"), modelPicker, 1, false)
            .addComponent(testButton, 0)
            .addComponent(statusLabel, 0)
            .addComponent(manageAutomaticallyCheckbox, 0)
            .addComponent(manualLaunchHint, 0)
            .addComponent(useWslCheckbox, 0)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .apply { border = JBUI.Borders.empty(8, 12) }
    }

    override fun isModified(): Boolean {
        return endpointField.text != client.getState().endpoint ||
            tokenField.text != client.getState().sessionToken ||
            (modelPicker.selectedItem as? String)?.let { extractId(it) } != client.getState().defaultModelId.takeIf { it.isNotEmpty() } ||
            manageAutomaticallyCheckbox.isSelected != client.getState().manageAutomatically ||
            useWslCheckbox.isSelected != client.getState().useWsl
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        // Lazy-validate the URL — a malformed one will simply produce
        // "unreachable" on first use, which the user can fix in place.
        val endpoint = endpointField.text.trim()
        if (endpoint.isNotEmpty() && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            throw ConfigurationException("Endpoint must start with http:// or https://")
        }
        val modelId = (modelPicker.selectedItem as? String)?.let { extractId(it) }.orEmpty()
        client.updateSettings(
            endpoint = endpoint,
            token = tokenField.text,
            model = modelId,
            manageAutomatically = manageAutomaticallyCheckbox.isSelected,
            useWsl = useWslCheckbox.isSelected,
        )
    }

    override fun reset() {
        endpointField.text = client.getState().endpoint
        tokenField.text = client.getState().sessionToken
        val items = listOf("(default — unchanged)") +
            (modelPicker.model?.let { (0 until it.size).map(modelPicker.model::getElementAt) }.orEmpty())
        // Always rebuild so the dropdown shows the user's saved choice.
        modelPicker.removeAllItems()
        for (s in items) modelPicker.addItem(s)
        // Pre-select the saved model if any.
        if (client.getState().defaultModelId.isNotEmpty()) {
            modelPicker.selectedItem = client.getState().defaultModelId
        }
        manageAutomaticallyCheckbox.isSelected = client.getState().manageAutomatically
        manualLaunchHint.isVisible = !manageAutomaticallyCheckbox.isSelected
        useWslCheckbox.isSelected = client.getState().useWsl
        // We deliberately do NOT re-probe WSL on Settings open:
        // `wsl.exe --status` takes ~200-500ms, and the cache from the
        // initial probe is correct unless the user installed WSL
        // since opening the IDE — in which case restarting the IDE
        // picks it up. Adding a "Re-detect" button was rejected by
        // the user: they want a simple toggle, not a settings panel
        // with maintenance buttons. If the user toggles between
        // WSL-disabled (not installed) and WSL-enabled, they can
        // restart the IDE.
    }

    private fun runTestConnection() {
        statusLabel.text = "Testing…"
        // Temporarily set the in-memory state so the test reflects what
        // the user just typed, not the last-saved value.
        val prior = client.getState()
        client.updateSettings(endpoint = endpointField.text, token = tokenField.text)
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            val status = client.testConnection()
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (status != null) {
                    statusLabel.text = "Connected to Hermes ${status.version}"
                    // Also fetch models so the dropdown reflects what's actually available.
                    client.fetchModelsAsync { modelList: HermesModelList ->
                        if (modelList.options.isNotEmpty()) {
                            modelPicker.removeAllItems()
                            var selectedIdx = 0
                            for ((idx, m) in modelList.options.withIndex()) {
                                modelPicker.addItem("${m.label} (${m.id})")
                                // Pre-select the dashboard's currently-active
                                // model so the user sees what's actually running.
                                if (modelList.currentModelId != null && m.id == modelList.currentModelId) {
                                    selectedIdx = idx
                                }
                            }
                            modelPicker.selectedIndex = selectedIdx
                        }
                    }
                } else {
                    statusLabel.text = "Failed: dashboard not reachable at ${endpointField.text}"
                }
                // Roll back the temp state — actual save still requires Apply.
                client.updateSettings(endpoint = prior.endpoint, token = prior.sessionToken, model = prior.defaultModelId)
            }
        }
    }

    private fun extractId(label: String): String? {
        if (label.startsWith("(")) return null
        val open = label.lastIndexOf('(')
        val close = label.lastIndexOf(')')
        if (open < 0 || close <= open) return label
        return label.substring(open + 1, close)
    }

    /**
     * Build the manual-launch instructions shown when the user un-checks
     * "Manage dashboard automatically". Tells them the exact shell command
     * for their OS — copy-paste-ready into a terminal.
     *
     * The command uses `--no-open` because (a) the IDE's JCEF browser is
     * the consumer, not a system browser, and (b) on macOS the system
     * `open` is fine but on WSL2 `xdg-open` hangs — and either way the
     * plugin would just redirect to it.
     */
    private fun buildManualLaunchHint(): String {
        val os = System.getProperty("os.name")?.lowercase().orEmpty()
        val cmd = when {
            os.contains("win") -> "wsl.exe bash -lc 'hermes dashboard --no-open'"
            os.contains("mac") || os.contains("darwin") -> "hermes dashboard --no-open"
            os.contains("linux") || os.contains("nix") -> "hermes dashboard --no-open"
            else -> "hermes dashboard --no-open"
        }
        return "Run this in a terminal to start the dashboard manually:\n\n" +
            "    $cmd\n\n" +
            "The plugin will auto-connect once it sees the dashboard at the " +
            "endpoint configured above. Leave this terminal open while you use " +
            "the IDE."
    }
}