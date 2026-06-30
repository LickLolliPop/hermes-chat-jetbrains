package com.hermes.agent.jetbrains.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Regression tests for the "events feed disconnected" bug.
 *
 * Symptom (reported 2026-07-01):
 *   - From WSL, user switched the model on the running dashboard.
 *   - Returned to Android Studio, clicked the right-side refresh button.
 *   - Tool window showed "events feed disconnected ... tool calls may not
 *     appear". The error persisted across reconnect attempts and only
 *     cleared after restarting the IDE.
 *
 * Root cause:
 *   The old onRestartClicked() called `browser?.loadURL(chatUrl())` on the
 *   EXISTING JBCefBrowser instance. That CefBrowser still held the dead
 *   EventSource / WebSocket connection to the just-killed dashboard
 *   process. Reloading the URL didn't unhook the old events feed, so the
 *   freshly-restarted dashboard couldn't push events to that JCEF
 *   instance. The dead socket was held inside the JCEF runtime and only
 *   got released when the whole IDE was restarted.
 *
 * Fix (this test pins the contract):
 *   onRestartClicked() now disposes the existing JBCefBrowser via
 *   JBCefBrowser.dispose() BEFORE killing the dashboard process, then
 *   forces a fresh JBCefBrowser via ensureBrowser(force = true) after
 *   the new dashboard is reachable. The dead EventSource is released
 *   eagerly so the new dashboard's /api/events feed wires up cleanly.
 */
class HermesChatPanelBrowserRecreateTest : BasePlatformTestCase() {

    @Test
    fun testDisposeBrowserIsNoOpWhenNothingAttached() {
        val panel = HermesChatPanel(project)
        // Fresh panel: nothing in browserHost, no JBCefBrowser wrapper.
        assertEquals(
            "Fresh panel must start with empty JCEF host",
            0, panel.browserHostChildCount(),
        )
        assertFalse(
            "Fresh panel must have no JBCefBrowser wrapper",
            panel.isJbBrowserAttached(),
        )

        // disposeBrowser() must be a safe no-op when there's nothing to
        // dispose — guards against NPE on rapid double-clicks of refresh.
        panel.disposeBrowserForTest()
        assertEquals(
            "disposeBrowser() on empty host must not change child count",
            0, panel.browserHostChildCount(),
        )
        assertFalse(
            "disposeBrowser() on empty host must keep jbBrowser == null",
            panel.isJbBrowserAttached(),
        )
    }

    @Test
    fun testEnsureBrowserForceFalseOnEmptyHostAddsFallbackLikeForceTrue() {
        val panel = HermesChatPanel(project)
        // force=false on a CLEAN empty host is NOT a no-op — the early-return
        // guard `!force && (browser != null || isFallbackAdded)` only fires
        // when something is already attached. With an empty host, force=false
        // takes the same path as force=true: JBCefApp.isSupported() returns
        // false under the headless fixture, so the fallback hyperlink is
        // added. This mirrors testEnsureBrowserForceRebuildsHostAfterDispose.
        panel.ensureBrowserForTest(force = false)
        assertEquals(
            "ensureBrowser(force=false) on empty host should add fallback link",
            1, panel.browserHostChildCount(),
        )
    }

    @Test
    fun testEnsureBrowserForceFalseIsNoOpWhenFallbackAlreadyAdded() {
        val panel = HermesChatPanel(project)
        // Now prime the host with a fallback (same as the new test above).
        panel.ensureBrowserForTest(force = true)
        assertEquals(1, panel.browserHostChildCount())

        // Subsequent force=false MUST short-circuit via the early-return
        // guard (`browser == null || isFallbackAdded`). Pin that contract —
        // if the guard ever drifts, calling refreshStatus() repeatedly
        // would stack fallback links into the host.
        panel.ensureBrowserForTest(force = false)
        assertEquals(
            "ensureBrowser(force=false) must not add a second fallback",
            1, panel.browserHostChildCount(),
        )
    }

    @Test
    fun testEnsureBrowserForceRebuildsHostAfterDispose() {
        val panel = HermesChatPanel(project)

        // In a headless test fixture JBCefApp.isSupported() returns false,
        // so ensureBrowser() takes the fallback path and adds exactly one
        // HyperlinkLabel child to browserHost. This is what we want for
        // the regression: the force=true round-trip is observable via
        // child count and the isFallbackAdded reset.
        panel.ensureBrowserForTest(force = true)
        assertEquals(
            "ensureBrowser(force=true) on empty host should add fallback link",
            1, panel.browserHostChildCount(),
        )

        // Now simulate a refresh: the production code calls disposeBrowser()
        // before killing the old dashboard, then ensureBrowser(force=true)
        // again to wire the new dashboard. Mirror that two-step.
        panel.disposeBrowserForTest()
        assertEquals(
            "disposeBrowser() must clear the host's children",
            0, panel.browserHostChildCount(),
        )

        panel.ensureBrowserForTest(force = true)
        assertEquals(
            "ensureBrowser(force=true) after dispose must re-add fallback link",
            1, panel.browserHostChildCount(),
        )
    }

    @Test
    fun testEnsureBrowserForceDoesNotStackComponents() {
        val panel = HermesChatPanel(project)
        // Run the force-rebuild cycle 5 times. If disposeBrowser() failed
        // to clear browserHost (the bug we just fixed), the child count
        // would grow unbounded. Pin it at 1 — exactly one fallback link
        // per cycle, because disposeBrowser() resets isFallbackAdded and
        // the host's removeAll() drops the previous child.
        repeat(5) {
            panel.disposeBrowserForTest()
            panel.ensureBrowserForTest(force = true)
        }
        assertEquals(
            "Repeated dispose+rebuild must NOT stack components",
            1, panel.browserHostChildCount(),
        )
    }
}
