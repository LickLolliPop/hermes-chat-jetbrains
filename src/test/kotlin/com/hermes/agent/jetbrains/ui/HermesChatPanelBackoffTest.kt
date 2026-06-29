package com.hermes.agent.jetbrains.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for [HermesChatPanel.backoffMs].
 *
 * We deliberately do NOT spin up an IntelliJ test fixture here — the
 * function is pure (input Int → output Long) with no UI / EDT / HTTP
 * dependencies, so plain JUnit 5 is faster and clearer. The companion
 * PROBE_INTERVAL_MS lives in the same file's package-private companion
 * object on the production class, so we can reference it directly.
 *
 * If the staircase ever changes, update the literals here — they're the
 * contract that protects against accidentally regressing to "8s
 * forever, hammering a dead socket" (the original resource issue) or
 * jumping to "5min immediately, hiding real outages".
 */
@DisplayName("HermesChatPanel.backoffMs() polling backoff staircase")
class HermesChatPanelBackoffTest {

    private companion object {
        // Must mirror HermesChatPanel.Companion.PROBE_INTERVAL_MS. If the
        // production constant moves, update here too. We don't expose
        // the constant via reflection because plain literals are
        // easier to read in failure messages.
        const val PROBE_INTERVAL_MS = 8_000L
    }

    @Test
    fun `failures 0 and 1 stay at the base 8s interval`() {
        // 0 failures shouldn't really happen (refreshStatus only calls
        // backoffMs on UNREACHABLE, which increments first), but the
        // staircase should still be safe for it.
        assertEquals(PROBE_INTERVAL_MS, HermesChatPanel::backoffMs.readSafely(0))
        assertEquals(PROBE_INTERVAL_MS, HermesChatPanel::backoffMs.readSafely(1))
    }

    @Test
    fun `failures 2 through 5 use the 30s plateau`() {
        for (f in 2..5) {
            assertEquals(
                30_000L,
                HermesChatPanel::backoffMs.readSafely(f),
                "failures=$f should map to 30s",
            )
        }
    }

    @Test
    fun `failures 6 through 20 use the 60s plateau`() {
        for (f in 6..20) {
            assertEquals(
                60_000L,
                HermesChatPanel::backoffMs.readSafely(f),
                "failures=$f should map to 60s",
            )
        }
    }

    @Test
    fun `failures above 20 saturate at 300s (5 minutes)`() {
        // Sample a few representative large values to keep the test
        // fast while still proving the saturation tail.
        for (f in listOf(21, 50, 100, 1_000, Int.MAX_VALUE)) {
            assertEquals(
                300_000L,
                HermesChatPanel::backoffMs.readSafely(f),
                "failures=$f should saturate at 300s",
            )
        }
    }

    @Test
    fun `backoff is monotonically non-decreasing across the staircase`() {
        // The staircase MUST never go backwards — otherwise a single
        // reordering bug would let the timer re-tighten after it had
        // already stretched, re-introducing the hammering behaviour.
        var previous = HermesChatPanel::backoffMs.readSafely(0)
        for (f in 1..50) {
            val current = HermesChatPanel::backoffMs.readSafely(f)
            assert(
                current >= previous,
                "backoff regressed at failures=$f: previous=$previous ms, current=$current ms",
            )
            previous = current
        }
    }

    /** Hide the Java reflection ugliness behind a Kotlin call-site. */
    private fun ((Int) -> Long).readSafely(failures: Int): Long = invoke(failures)
}