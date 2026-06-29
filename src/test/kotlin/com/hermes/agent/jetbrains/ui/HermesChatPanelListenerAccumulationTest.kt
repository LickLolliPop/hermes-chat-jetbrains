package com.hermes.agent.jetbrains.ui

import com.intellij.testFramework.junit5.BasePlatformTest
import java.awt.event.MouseEvent
import javax.swing.event.EventListenerList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Regression test for the P0 listener-accumulation bug
 * (BUG-2026-06-29-HermesChat-Listener-Leak.md).
 *
 * Before the fix: `renderStatus()` called
 * `header.addMouseListener(object : MouseAdapter() { ... })` on every
 * 8s timer tick. After a few hours of idle dashboard uptime, ~1500
 * anonymous MouseAdapters were attached to the header JBLabel. A
 * single mouse hover then fired all of them, spawning ~1500
 * msedge.exe processes and freezing the entire IDE.
 *
 * After the fix: the header mouse listener is a single field attached
 * once in init {}, and renderStatus() no longer touches the listener
 * list at all. This test simulates 10,000 status probes (≈ 22 hours
 * of idle timer ticks) and asserts the header still has exactly one
 * mouse listener.
 *
 * We use [BasePlatformTest] (JUnit 5) because HermesChatPanel pulls
 * Project through the IDE's service container (HermesClient.getInstance
 * throws without one). Test method names are prefixed `test...` only
 * by convention; JUnit 5 finds them via [Test].
 *
 * We do NOT call refreshStatus() itself — it depends on HTTP and the
 * WSL dashboard. Instead we directly invoke renderStatus() to model
 * the production call site that used to leak the listener.
 */
@DisplayName("HermesChatPanel.renderStatus does not accumulate mouse listeners")
class HermesChatPanelListenerAccumulationTest : BasePlatformTest() {

    @Test
    fun renderStatusTenThousandTimesAttachesExactlyOneMouseListener() {
        val panel = HermesChatPanel(project)

        // Baseline: the persistent field listener is attached in init {}.
        val baselineListenerCount = listenerCount(panel)
        assertEquals(
            1,
            baselineListenerCount,
            "init {} should attach exactly one persistent MouseListener. " +
                "If this is 0, the persistent field pattern was removed; " +
                "if >1, something else is leaking."
        )

        // Simulate ~22 hours of idle 8s timer ticks (10000 * 8s = 80000s).
        // Pre-fix this would attach 10000 anonymous MouseAdapters and
        // dispatching a single mouse event would freeze the IDE.
        repeat(10_000) {
            panel.renderStatus(null)  // null status -> "unknown" version path
        }

        val afterListenerCount = listenerCount(panel)
        assertEquals(
            1,
            afterListenerCount,
            "Listener accumulated: started with $baselineListenerCount, " +
                "ended with $afterListenerCount after 10000 renderStatus() calls. " +
                "This is the exact bug that froze the IDE on 2026-06-29."
        )
    }

    @Test
    fun dispatchingOneMouseClickAfterTenThousandTicksIsSafe() {
        val panel = HermesChatPanel(project)
        repeat(10_000) { panel.renderStatus(null) }

        // After the fix the header has exactly one MouseListener, so a
        // single click fires one debounced call to openInExternalBrowser.
        // In a headless test fixture there's no real browser to launch,
        // so we just verify the dispatch is non-throwing and the
        // listener count is unchanged. The debounce property itself
        // is exercised by HermesChatPanelBackoffTest.
        assertEquals(1, listenerCount(panel))

        val header = panel.headerForTest()
        header.dispatchEvent(
            MouseEvent(
                header,
                MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(),
                0,
                5,
                5,
                1,
                false,
                MouseEvent.BUTTON1,
            )
        )

        // Sanity: still one listener after the dispatch.
        assertEquals(1, listenerCount(panel))
    }

    /**
     * Count the MouseListeners attached to the header JBLabel by
     * walking the JLabel's internal EventListenerList via reflection.
     *
     * Swing stores listeners in a private `listenerList` EventListener
     * field. We filter to MouseListener instances, which is what the
     * bug attaches.
     */
    private fun listenerCount(panel: HermesChatPanel): Int {
        val header = panel.headerForTest()
        val field = javax.swing.JLabel::class.java.getDeclaredField("listenerList").apply {
            isAccessible = true
        }
        val listenerList = field.get(header) as EventListenerList
        return listenerList.getListeners(java.awt.event.MouseListener::class.java).size
    }
}