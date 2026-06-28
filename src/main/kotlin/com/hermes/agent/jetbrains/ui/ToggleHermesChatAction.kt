package com.hermes.agent.jetbrains.ui

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Toggle action — flips the Hermes Chat tool window visibility.
 *
 * Bound to Ctrl+Alt+H in plugin.xml. Also registered under View → Tool
 * Windows automatically because we ship a <toolWindow> in plugin.xml.
 *
 * Why a dedicated action: ToolWindowManager.getToolWindow().show() /
 * .hide() isn't bound to a keymap by itself; IntelliJ only wires
 * shortcuts through registered Action subclasses.
 */
class ToggleHermesChatAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
        if (tw.isVisible) tw.hide() else tw.show()
    }

    companion object {
        /** Must match the id in plugin.xml. */
        const val TOOL_WINDOW_ID: String = "Hermes Chat"
    }
}