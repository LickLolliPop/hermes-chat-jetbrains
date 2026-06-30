package com.hermes.agent.jetbrains.ui

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Icons used by the plugin.
 */
object HermesIcons {
    /** Sidebar tool-window glyph — a chat-bubble variant of Hermes' mark. */
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon(
        "/icons/hermesChat.svg",
        HermesIcons::class.java,
    )

    /** Smaller variant for menus and inline labels. */
    @JvmField
    val ToolWindowSmall: Icon = IconLoader.getIcon(
        "/icons/hermesChat_16.svg",
        HermesIcons::class.java,
    )
}
