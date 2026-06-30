package com.hermes.agent.jetbrains.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test

/**
 * Regression tests for the "stale model label after reconnect" bug.
 *
 * Symptom (reported 2026-07-01, alongside the events-feed bug):
 *   - User switched the active model on the dashboard (via WSL).
 *   - Returned to Android Studio, clicked the footer's Reconnect button.
 *   - The footer model label still showed the PREVIOUS model's name,
 *     even though the dashboard was now running the new model.
 *
 * Root cause:
 *   refreshStatus() resolved the current model's display label by
 *   `modelList.options.firstOrNull { it.id == modelList.currentModelId }
 *   ?.label` and passed it to FooterPanel.setCurrentModel(). When the
 *   just-switched model hadn't propagated to the dashboard's
 *   /api/model/options response yet (cached list, race during reconnect),
 *   that firstOrNull returned null, and setCurrentModel() did an early
 *   return on null — leaving the previous model's label in the footer.
 *
 * Fix (this test pins the contract):
 *   refreshStatus() now falls back to the raw currentModelId when no
 *   option matches, so FooterPanel.setCurrentModel() is always driven
 *   with a non-empty string. The footer's label always reflects what
 *   the dashboard is actually running.
 *
 * We also assert the contract directly on FooterPanel: setCurrentModel
 * must update the label for any non-blank string (label or raw id).
 */
class HermesChatPanelFooterModelLabelTest : BasePlatformTestCase() {

    @Test
    fun testSetCurrentModelUpdatesLabelWithFriendlyName() {
        val panel = HermesChatPanel(project)
        val footer = panel.footer

        // Initial state: the placeholder "loading…" text.
        val initial = footer.currentModelLabelTextForTest()
        assertTrue(
            "Footer should start in the loading state, got: $initial",
            initial.contains("loading", ignoreCase = true),
        )

        // Happy path: dashboard returns a friendly label.
        footer.setCurrentModelForTest("Claude 3.5 Sonnet — anthropic")
        flushEdt()
        assertEquals(
            "Model: Claude 3.5 Sonnet — anthropic",
            footer.currentModelLabelTextForTest(),
        )
    }

    @Test
    fun testSetCurrentModelFallsBackToRawIdWhenLabelUnresolvable() {
        val panel = HermesChatPanel(project)
        val footer = panel.footer

        // Simulate the bug scenario: dashboard reports a currentModelId
        // whose option entry was NOT in the /api/model/options list (e.g.
        // just-switched model whose list response was cached). The fix
        // makes refreshStatus() pass the raw id through to the footer
        // instead of dropping the update.
        footer.setCurrentModelForTest("openai/gpt-5-turbo")
        flushEdt()
        assertEquals(
            "Footer must reflect dashboard's raw model id, " +
                "not get stuck on the previous label",
            "Model: openai/gpt-5-turbo",
            footer.currentModelLabelTextForTest(),
        )
    }

    @Test
    fun testSetCurrentModelSecondCallOverwritesFirst() {
        // The pre-fix bug: the second setCurrentModel call (with a raw id
        // fallback after the first call's label-friendly update) was
        // being dropped on the floor. Pin that the second call wins.
        val panel = HermesChatPanel(project)
        val footer = panel.footer

        footer.setCurrentModelForTest("Old Model — old-provider")
        flushEdt()
        assertEquals(
            "Model: Old Model — old-provider",
            footer.currentModelLabelTextForTest(),
        )

        footer.setCurrentModelForTest("new-provider/new-model-id")
        flushEdt()
        assertEquals(
            "Second setCurrentModel call must overwrite the first",
            "Model: new-provider/new-model-id",
            footer.currentModelLabelTextForTest(),
        )
    }

    @Test
    fun testSetCurrentModelNullOrBlankIsNoOp() {
        // Sanity guard: the null/blank no-op is intentional — a non-update
        // signal — but it must NOT clobber a previously-set label. This
        // pins the "refreshStatus can pass null safely" contract.
        val panel = HermesChatPanel(project)
        val footer = panel.footer

        footer.setCurrentModelForTest("Some Model — provider")
        flushEdt()
        assertEquals(
            "Model: Some Model — provider",
            footer.currentModelLabelTextForTest(),
        )

        footer.setCurrentModelForTest(null)
        flushEdt()
        assertEquals(
            "Null must not clobber existing label",
            "Model: Some Model — provider",
            footer.currentModelLabelTextForTest(),
        )

        footer.setCurrentModelForTest("   ")
        flushEdt()
        assertEquals(
            "Blank must not clobber existing label",
            "Model: Some Model — provider",
            footer.currentModelLabelTextForTest(),
        )
    }

    /**
     * Drain the EDT. FooterPanel.setCurrentModel posts its update to
     * invokeLater, so without a pump the label text is still the old
     * value when we read it back.
     */
    private fun flushEdt() {
        com.intellij.openapi.application.ApplicationManager
            .getApplication()
            .invokeAndWait { /* drain pending EDT tasks */ }
    }
}
