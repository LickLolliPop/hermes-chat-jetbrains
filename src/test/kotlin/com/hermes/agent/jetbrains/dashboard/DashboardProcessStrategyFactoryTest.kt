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

    // -- pickWithUseWsl ------------------------------------------------
    //
    // The v0.2.0 settings checkbox (useWsl) only affects Windows hosts.
    // On macOS/Linux the setting is stored in State but ignored at the
    // strategy level. These tests pin the two Windows cases (useWsl=true
    // → WSL strategy, useWsl=false → native strategy) plus the
    // cross-OS "useWsl is ignored" contract.
    //
    // Earlier draft tried to make pickWithUseWsl refuse native fallback
    // when WSL was installed (a "WslInstalledButDisabled" placeholder
    // strategy). User feedback: that's paternalistic — if the user
    // flipped the toggle to native, they want native, full stop. The
    // native strategy's "hermes not found on PATH" error is the right
    // signal. These tests pin the simpler, trust-the-user semantics.

    @Test
    fun `pickWithUseWsl on Windows with useWsl=true returns WindowsWslStrategy`() {
        // The default path — WSL strategy, unchanged from the no-arg
        // overload.
        val strategy = DashboardProcessStrategy.pickWithUseWsl(
            osName = "Windows 11",
            useWsl = true,
        )
        assertIs<WindowsWslStrategy>(
            strategy,
            "useWsl=true must pick WindowsWslStrategy on Windows",
        )
        assertEquals("WSL", strategy.osLabel)
    }

    @Test
    fun `pickWithUseWsl on Windows with useWsl=false returns MacLinuxStrategy (native)`() {
        // User has flipped the WSL toggle off — they want to run the
        // dashboard via a native `hermes.exe` on their Windows PATH.
        // Whether or not WSL is installed on the host is irrelevant:
        // the strategy picker trusts the user's intent. If the native
        // binary is missing the strategy surfaces its own "hermes not
        // found" error, which is the right escape hatch.
        //
        // The Linux-flavoured MacLinuxStrategy is reused because its
        // process-spawning code (setsid + nohup) and port probe (ss /
        // lsof) are POSIX-portable; on Windows it runs against the
        // user's Windows shell via bash.exe (Git Bash / WSL interop),
        // which is how the existing v0.1.x build path for native
        // users has always worked.
        val strategy = DashboardProcessStrategy.pickWithUseWsl(
            osName = "Windows 11",
            useWsl = false,
        )
        assertIs<MacLinuxStrategy>(
            strategy,
            "Windows + useWsl=false must return MacLinuxStrategy (native) regardless of WSL presence",
        )
        assertEquals(
            "Linux", strategy.osLabel,
            "Windows-native fallback uses the Linux-labelled strategy (POSIX-portable launcher)",
        )
    }

    @Test
    fun `pickWithUseWsl on macOS ignores useWsl and returns MacLinuxStrategy isMac=true`() {
        // macOS hosts: no WSL exists. The useWsl toggle is hidden in
        // the UI (WslDetector returns false on non-Windows), but if
        // a user with a cross-OS config somehow sets it via the XML
        // file, the picker must ignore it.
        for (useWsl in listOf(true, false)) {
            val strategy = DashboardProcessStrategy.pickWithUseWsl(
                osName = "Mac OS X",
                useWsl = useWsl,
            )
            assertIs<MacLinuxStrategy>(
                strategy,
                "macOS must always pick MacLinuxStrategy regardless of useWsl (useWsl=$useWsl)",
            )
            assertEquals(
                "macOS", strategy.osLabel,
                "macOS-dispatched strategy must label itself 'macOS'",
            )
        }
    }

    @Test
    fun `pickWithUseWsl on Linux ignores useWsl and returns MacLinuxStrategy isMac=false`() {
        // Same as the macOS case but for Linux. Pin both branches so
        // a future refactor can't quietly make the picker OS-sensitive
        // when the user has useWsl=false in their XML.
        for (useWsl in listOf(true, false)) {
            val strategy = DashboardProcessStrategy.pickWithUseWsl(
                osName = "Linux",
                useWsl = useWsl,
            )
            assertIs<MacLinuxStrategy>(
                strategy,
                "Linux must always pick MacLinuxStrategy regardless of useWsl (useWsl=$useWsl)",
            )
            assertEquals(
                "Linux", strategy.osLabel,
                "Linux-dispatched strategy must label itself 'Linux'",
            )
        }
    }
}