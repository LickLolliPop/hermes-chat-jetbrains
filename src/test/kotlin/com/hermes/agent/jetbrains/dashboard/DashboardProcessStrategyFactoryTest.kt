package com.hermes.agent.jetbrains.dashboard

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [DashboardProcessStrategy.pick] (the pure dispatch
 * function) and [DashboardProcessStrategy.forCurrentOs] (the prod entry
 * point that reads `os.name` and delegates to `pick`).
 *
 * Why we test `pick(osName)` instead of `forCurrentOs()`:
 * `os.name` is a JVM startup-time system property. In a single test run
 * the value is fixed (e.g. "Linux" on CI, "Windows 10" on a Windows
 * runner). Testing all four branches requires either monkey-patching
 * `System.setProperty("os.name", ...)` — which the JVM doesn't allow
 * after startup on most JDKs — or running the suite four times on four
 * different OSes. Splitting `pick` out as a pure function lets a single
 * test run cover all branches in milliseconds.
 *
 * What this pins:
 * - Each branch picks the right concrete strategy class.
 * - The `osLabel` of the picked strategy matches the OS name.
 * - The fallback branch (unknown OS) returns a Linux-style strategy
 *   rather than crashing or returning WindowsWslStrategy (which would
 *   try to spawn `wsl.exe` on a system that may not have it).
 */
class DashboardProcessStrategyFactoryTest {

    @Test
    fun `pick dispatches Windows family to WindowsWslStrategy`() {
        // Real os.name values across the major Windows JVMs we care about.
        listOf("Windows 10", "Windows 11", "Windows Server 2022").forEach { osName ->
            val strategy = DashboardProcessStrategy.pick(osName)
            assertIs<WindowsWslStrategy>(
                strategy,
                "Expected WindowsWslStrategy for os.name='$osName' but got ${strategy::class.simpleName}",
            )
            assertEquals("WSL", strategy.osLabel, "Windows strategy must report 'WSL' as its osLabel")
        }
    }

    @Test
    fun `pick dispatches macOS family to MacLinuxStrategy isMac=true`() {
        // macOS JVMs have reported various strings over the years: "Mac OS X"
        // (legacy), "Mac OS X 14.x" (Sonoma+). "Darwin" appears in some
        // downstream distributions (Homebrew JDKs, conda).
        listOf("Mac OS X", "Mac OS X 14.5", "Darwin").forEach { osName ->
            val strategy = DashboardProcessStrategy.pick(osName)
            assertIs<MacLinuxStrategy>(
                strategy,
                "Expected MacLinuxStrategy for os.name='$osName'",
            )
            // The flag is private, but we can assert on the observable
            // contract: the osLabel says "macOS" not "Linux".
            assertEquals(
                "macOS", strategy.osLabel,
                "macOS-dispatched strategy must report 'macOS' as its osLabel (got '${strategy.osLabel}')",
            )
        }
    }

    @Test
    fun `pick dispatches Linux family to MacLinuxStrategy isMac=false`() {
        // HotSpot reports "Linux"; some downstream JDKs (BellSoft,
        // Eclipse Temurin for ARM) append the arch. The "nix" catch-all
        // covers *BSD family and any other Unix that calls itself that.
        listOf("Linux", "Linux/arm64", "Linux/amd64", "FreeBSD", "SunOS").forEach { osName ->
            val strategy = DashboardProcessStrategy.pick(osName)
            assertIs<MacLinuxStrategy>(
                strategy,
                "Expected MacLinuxStrategy for os.name='$osName'",
            )
            assertEquals(
                "Linux", strategy.osLabel,
                "Linux-dispatched strategy must report 'Linux' as its osLabel (got '${strategy.osLabel}')",
            )
        }
    }

    @Test
    fun `pick falls back to Linux strategy for unknown OS names`() {
        // Important: never crash, never silently pick WindowsWslStrategy
        // (which would try to spawn `wsl.exe` on a system that may not
        // have it). POSIX `nohup`/`setsid` is portable enough that the
        // Linux strategy is the safest fallback for unknown platforms.
        listOf("", "Plan 9", "Haiku", "ReactOS", "OS/2").forEach { osName ->
            val strategy = DashboardProcessStrategy.pick(osName)
            assertIs<MacLinuxStrategy>(
                strategy,
                "Unknown os.name='$osName' must fall back to MacLinuxStrategy, not WindowsWslStrategy",
            )
            assertEquals(
                "Linux", strategy.osLabel,
                "Unknown-OS fallback strategy must label itself 'Linux'",
            )
        }
    }

    @Test
    fun `pick is case-insensitive`() {
        // Real os.name values are capitalized ("Mac OS X", "Linux") but
        // our impl calls `.lowercase()` first — pin that contract so a
        // future refactor can't quietly introduce a case-sensitivity bug.
        // (We don't test "WINDOWS 10" here because Windows JVMs never
        // report the os.name as uppercase; this is belt-and-braces.)
        val macUpper = DashboardProcessStrategy.pick("MAC OS X")
        val linuxUpper = DashboardProcessStrategy.pick("LINUX")
        val winMixed = DashboardProcessStrategy.pick("Windows 11")
        assertEquals("macOS", macUpper.osLabel)
        assertEquals("Linux", linuxUpper.osLabel)
        assertEquals("WSL", winMixed.osLabel)
    }

    @Test
    fun `forCurrentOs delegates to pick with the actual os_name property`() {
        // The contract of `forCurrentOs`: it must NOT do its own matching
        // or cache; it must call `pick(os.name)` and return whatever that
        // returns. This is the test that breaks if anyone in the future
        // "optimizes" `forCurrentOs` to short-circuit the dispatch.
        //
        // We assert that the class matches what `pick` would produce for
        // the current runtime's os.name — not the absolute class, because
        // we don't know what OS the test runner is on.
        val actualOsName = System.getProperty("os.name") ?: ""
        val expected = DashboardProcessStrategy.pick(actualOsName)
        val actual = DashboardProcessStrategy.forCurrentOs()
        assertEquals(
            expected::class, actual::class,
            "forCurrentOs() must return the same class as pick(System.getProperty(\"os.name\"))",
        )
        assertEquals(
            expected.osLabel, actual.osLabel,
            "forCurrentOs() must return the same osLabel as pick(...)",
        )
        // Sanity: at minimum the result must be a DashboardProcessStrategy
        // (catches the "regression to null" failure mode if anyone ever
        // changes forCurrentOs() to return null for unknown OS).
        assertTrue(actual is DashboardProcessStrategy)
    }
}