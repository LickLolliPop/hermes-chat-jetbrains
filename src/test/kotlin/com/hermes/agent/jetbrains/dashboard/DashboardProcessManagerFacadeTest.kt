package com.hermes.agent.jetbrains.dashboard

import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DashboardProcessManager] the facade.
 *
 * Why this is short: the facade is a one-liner delegator. The real
 * logic lives in the per-OS strategies, which are tested in their own
 * files. The only thing the facade owns is the strategy picker and the
 * `osLabel` field — both pinned by [DashboardProcessStrategyFactoryTest].
 *
 * What we DO need to pin here:
 * - The facade holds the strategy passed in its constructor (does NOT
 *   silently swap it for `forCurrentOs()` — important for tests of
 *   downstream code that injects a fake strategy).
 * - `restartDashboard` delegates to `strategy.restart`, threading
 *   the callback through.
 * - `isDashboardRunning` delegates to `strategy.isRunning`.
 * - The `Result.success` derived property is the inverse of `fatal`.
 */
class DashboardProcessManagerFacadeTest {

    /**
     * In-memory fake strategy. Captures all calls so tests can assert
     * on them. Resets between tests via the `reset()` helper.
     */
    private class FakeStrategy : DashboardProcessStrategy {
        override val osLabel: String = "Fake"
        val restartCalls = AtomicInteger(0)
        val isRunningCalls = AtomicInteger(0)
        var nextIsRunningReturn: Boolean = false
        var nextRestartReturn: DashboardProcessManager.Result =
            DashboardProcessManager.Result("ok", fatal = false)

        override fun restart(onComplete: (DashboardProcessManager.Result) -> Unit) {
            restartCalls.incrementAndGet()
            onComplete(nextRestartReturn)
        }

        override fun isRunning(): Boolean {
            isRunningCalls.incrementAndGet()
            return nextIsRunningReturn
        }

        override fun homeDir(): String = "/fake/home"

        fun reset() {
            restartCalls.set(0)
            isRunningCalls.set(0)
        }
    }

    @Test
    fun `facade osLabel delegates to the strategy`() {
        // The facade's osLabel is the strategy's osLabel verbatim.
        // This is the contract the idea.log banner depends on.
        val fake = FakeStrategy()
        val facade = DashboardProcessManager(fake)
        assertEquals(fake.osLabel, facade.osLabel)
        assertEquals("Fake", facade.osLabel)
    }

    @Test
    fun `restartDashboard delegates to strategy restart and fires the callback`() {
        val fake = FakeStrategy()
        val facade = DashboardProcessManager(fake)
        val received = AtomicInteger(0)
        val latch = CountDownLatch(1)
        fake.nextRestartReturn = DashboardProcessManager.Result("test ok", fatal = false)

        facade.restartDashboard { result ->
            received.incrementAndGet()
            // The callback on the facade is the SAME callback passed
            // to the strategy — we expect exactly-once invocation.
            assertEquals("test ok", result.message)
            assertFalse(result.fatal)
            latch.countDown()
        }

        assertEquals(1, fake.restartCalls.get(), "strategy.restart must be called exactly once")
        assertEquals(1, received.get(), "callback must fire exactly once")
        assertTrue(latch.await(1, TimeUnit.SECONDS), "callback must fire within 1s")
    }

    @Test
    fun `restartDashboard threads a fatal Result through unchanged`() {
        // Pin that the facade doesn't try to "improve" or swallow
        // fatal results — fatal errors must reach the UI intact so
        // the user sees what went wrong.
        val fake = FakeStrategy()
        val facade = DashboardProcessManager(fake)
        val fatalMessage = "Cannot find hermes on PATH"
        fake.nextRestartReturn = DashboardProcessManager.Result(fatalMessage, fatal = true)
        var receivedFatal: Boolean? = null
        var receivedMessage: String? = null

        facade.restartDashboard { result ->
            receivedFatal = result.fatal
            receivedMessage = result.message
        }

        assertEquals(fatalMessage, receivedMessage, "fatal message must pass through unchanged")
        assertEquals(true, receivedFatal, "fatal flag must pass through unchanged")
    }

    @Test
    fun `isDashboardRunning delegates to strategy isRunning and returns the value`() {
        val fake = FakeStrategy()
        val facade = DashboardProcessManager(fake)

        fake.nextIsRunningReturn = true
        assertTrue(facade.isDashboardRunning(), "must return true when strategy says true")
        assertEquals(1, fake.isRunningCalls.get())

        fake.nextIsRunningReturn = false
        assertFalse(facade.isDashboardRunning(), "must return false when strategy says false")
        assertEquals(2, fake.isRunningCalls.get())
    }

    @Test
    fun `Result success is the inverse of fatal`() {
        // Derived property: success = !fatal. Pin both branches.
        assertTrue(DashboardProcessManager.Result("ok", fatal = false).success)
        assertFalse(DashboardProcessManager.Result("boom", fatal = true).success)
    }

    @Test
    fun `facade uses the injected strategy rather than calling forCurrentOs`() {
        // If the facade silently called `DashboardProcessStrategy.forCurrentOs()`
        // (instead of using the injected one), the fake would never
        // see its calls. The fact that the previous tests' assertions
        // pass on the fake IS this test — but we make it explicit so
        // a future maintainer doesn't add a "convenience constructor"
        // that drops the strategy parameter and surprises everyone.
        val fake = FakeStrategy()
        DashboardProcessManager(fake).restartDashboard { /* no-op */ }
        assertEquals(
            1, fake.restartCalls.get(),
            "facade must use the injected strategy — forCurrentOs() would not call our fake",
        )
        DashboardProcessManager(fake).isDashboardRunning()
        assertEquals(
            1, fake.isRunningCalls.get(),
            "facade must use the injected strategy for isDashboardRunning too",
        )
    }
}