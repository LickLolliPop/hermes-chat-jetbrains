package com.hermes.agent.jetbrains.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icons used by the plugin. We use the standard IconLoader so IntelliJ
 * resolves them correctly across bundled and custom themes.
 *
 * v0.1.0 ships a single tool-window glyph. Future milestones may add
 * message-list icons (user / assistant / tool-call), but for now the
 * embedded dashboard SPA renders those itself.
 */
object HermesIcons {
    /** Sidebar tool-window glyph — a chat-bubble variant of Hermes' mark. */
    val ToolWindow: Icon = IconLoader.getIcon(
        "/icons/hermesChat.svg",
        HermesIcons::class.java,
    )

    /** Smaller variant for menus and inline labels. */
    val ToolWindowSmall: Icon = IconLoader.getIcon(
        "/icons/hermesChat_16.svg",
        HermesIcons::class.java,
    )
}