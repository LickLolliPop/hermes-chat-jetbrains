package com.hermes.agent.jetbrains.dashboard

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [WslDetector].
 *
 * Why we test the detector with injected probe functions rather than
 * against the real `wsl.exe`:
 * - The CI runner is Linux — `wsl.exe` doesn't exist there, so the
 *   "WSL installed on Linux" branch is the only realistic baseline.
 * - The `wsl.exe --status` call takes ~200-500ms and the file
 *   detection in `C:\Windows\System32\` is meaningless on a non-Windows
 *   filesystem. Both are out-of-scope for a unit test; we trust the
 *   injected probes to model the four real states (wsl.exe present
 *   vs absent × distro present vs absent) and let the production
 *   defaults run in the integration tests instead.
 *
 * The detector also short-circuits on non-Windows hosts (returns
 * false regardless of what the probes would say). Those branches
 * are covered by the Linux-macOS-named tests at the bottom of
 * this file — by virtue of the JVM `os.name` we're running on, all
 * the "wsl present" scenarios degrade to "not installed" anyway,
 * which is exactly the property we care about.
 *
 * What this pins:
 * - The four state combinations resolve to the right answer:
 *     wsl.exe + distro   → installed
 *     wsl.exe, no distro → not installed
 *     no wsl.exe         → not installed (regardless of distro probe)
 *     wsl.exe + distro probe throws → not installed (fail closed)
 * - The result is cached: the probe is called at most once per
 *   instance per [isInstalled] / [refresh] cycle.
 * - `refresh()` invalidates the cache (next isInstalled re-probes).
 */
class WslDetectorTest {

    private val originalOsName: String? = System.getProperty("os.name")

    @BeforeEach
    fun forceWindowsOsName() {
        // The non-Windows short-circuit is interesting on its own
        // (see the bottom of this file), but the four core state
        // combinations only matter on a Windows host. Pin the test
        // OS name so we always reach the probe code path.
        System.setProperty("os.name", "Windows 11")
    }

    @AfterEach
    fun restoreOsName() {
        if (originalOsName != null) System.setProperty("os.name", originalOsName)
        else System.clearProperty("os.name")
    }

    @Test
    fun `wsl exe present and distro present reports installed`() {
        val detector = WslDetector(
            wslExeProbe = { true },
            distroProbe = { true },
        )
        assertTrue(detector.isInstalled())
    }

    @Test
    fun `wsl exe present but no distro reports not installed`() {
        // This is the rare-but-real case where the WSL kernel is
        // installed but the user removed their distro. The plugin
        // should NOT offer a WSL toggle — there's nothing to run
        // the dashboard in.
        val detector = WslDetector(
            wslExeProbe = { true },
            distroProbe = { false },
        )
        assertFalse(detector.isInstalled())
    }

    @Test
    fun `wsl exe absent reports not installed regardless of distro probe`() {
        // The distro probe is short-circuited — even if the user
        // somehow has a registered distro (impossible without
        // wsl.exe, but the test pins the order-of-checks contract).
        val distroCalls = AtomicInteger(0)
        val detector = WslDetector(
            wslExeProbe = { false },
            distroProbe = { distroCalls.incrementAndGet(); true },
        )
        assertFalse(detector.isInstalled())
        assertEquals(0, distroCalls.get(), "distro probe must not be called when wsl.exe is absent")
    }

    @Test
    fun `distro probe that throws is treated as not installed`() {
        // Fail-closed: a flaky `wsl.exe --status` (timeout, weird
        // exit code, garbled output) should NOT cause the plugin to
        // offer a WSL toggle that would then fail at restart time.
        val detector = WslDetector(
            wslExeProbe = { true },
            distroProbe = { throw RuntimeException("wsl.exe timed out") },
        )
        assertFalse(detector.isInstalled())
    }

    @Test
    fun `isInstalled caches the result across calls`() {
        val probeCalls = AtomicInteger(0)
        val detector = WslDetector(
            wslExeProbe = { probeCalls.incrementAndGet(); true },
            distroProbe = { true },
        )
        // First call probes, subsequent calls return the cached value.
        assertTrue(detector.isInstalled())
        assertTrue(detector.isInstalled())
        assertTrue(detector.isInstalled())
        assertEquals(
            1, probeCalls.get(),
            "wsl.exe probe must be called exactly once across multiple isInstalled() invocations",
        )
    }

    @Test
    fun `refresh drops the cache and re-probes`() {
        val installed = AtomicBoolean(false)
        val probeCalls = AtomicInteger(0)
        val detector = WslDetector(
            wslExeProbe = {
                probeCalls.incrementAndGet()
                installed.get()
            },
            distroProbe = { true },
        )
        // Initially not installed.
        installed.set(false)
        assertFalse(detector.isInstalled())
        assertEquals(1, probeCalls.get())

        // User installs WSL and clicks "Re-detect WSL" in settings.
        installed.set(true)
        assertTrue(
            detector.refresh(),
            "refresh() must report the new value, not the stale cached one",
        )
        assertEquals(2, probeCalls.get())

        // Subsequent isInstalled returns the refreshed value (no re-probe).
        assertTrue(detector.isInstalled())
        assertEquals(2, probeCalls.get(), "isInstalled after refresh must use the new cache")
    }

    @Test
    fun `non-Windows host short-circuits to not installed`() {
        // Pin the cross-OS contract: on macOS/Linux, the detector
        // always returns false regardless of what the probes would
        // say. This is what lets the Settings panel hide the
        // "Use WSL" checkbox on every non-Windows build without
        // running the probe at all.
        try {
            System.setProperty("os.name", "Mac OS X")
            val probeCalls = AtomicInteger(0)
            val detector = WslDetector(
                wslExeProbe = { probeCalls.incrementAndGet(); true },
                distroProbe = { true },
            )
            assertFalse(detector.isInstalled())
            assertEquals(
                0, probeCalls.get(),
                "non-Windows hosts must not invoke the wsl.exe probe",
            )
        } finally {
            // Restore the Windows os.name for any subsequent test
            // in this class. AfterEach would also do this, but
            // restoring here keeps the test order-independent.
            System.setProperty("os.name", "Windows 11")
        }
    }
}
