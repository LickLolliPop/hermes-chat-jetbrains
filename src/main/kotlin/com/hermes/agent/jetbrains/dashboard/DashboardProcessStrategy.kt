package com.hermes.agent.jetbrains.dashboard

/**
 * Per-OS strategy for controlling the Hermes dashboard process from the IDE.
 *
 * Why this is an interface and not a single class with `if (isWindows)`:
 * the WSL-hosted path is so different from the native path that mixing them
 * in one body produces unreadable code. Splitting also makes the difference
 * visible at the call site: `DashboardProcessManager(WindowsWslStrategy())`
 * reads as "this is the WSL path; the trap lives here".
 *
 * Contract for any implementation:
 * - [restart] runs entirely off the EDT (the caller wraps it in
 *   `executeOnPooledThread`). The [onComplete] callback fires on the EDT
 *   via `invokeLater` so the UI can update safely.
 * - [isRunning] is synchronous and may block briefly (a process probe). Safe
 *   to call from background threads; not from the EDT.
 * - [homeDir] returns the user's home directory inside whatever environment
 *   this strategy controls (WSL for WindowsWslStrategy, native for
 *   MacLinuxStrategy). Used to resolve `$HOME/.hermes/...` paths.
 *
 * Why we don't use Java's ProcessHandle / Process API uniformly:
 * the WSL path goes through a `wsl.exe` indirection (the user's `hermes`
 * binary is only on $PATH inside WSL, not in Windows $PATH). ProcessHandle
 * only sees processes the JVM itself spawned — we can't use it to manage
 * a process tree rooted in a different OS.
 */
interface DashboardProcessStrategy {
    /**
     * Restart the dashboard: stop (best-effort) → wait for port → start.
     * [onComplete] fires on the EDT.
     */
    fun restart(onComplete: (DashboardProcessManager.Result) -> Unit)

    /**
     * Returns true if the dashboard is responding to its `--status` probe.
     * Cheap; safe to call on the EDT's polling cycle.
     */
    fun isRunning(): Boolean

    /**
     * Returns the home directory inside the controlled environment
     * (`/home/administrator` inside WSL on this dev box; `/Users/me` on macOS;
     * `/home/me` on Linux). Used to build `--skip-build` probe paths.
     */
    fun homeDir(): String

    /**
     * Short human label for this strategy ("WSL", "macOS", "Linux").
     * Used in notifications and the idea.log banner so an operator can
     * see at a glance which code path actually ran.
     */
    val osLabel: String

    companion object {
        /**
         * Pick the right strategy for the current OS. Windows hosts get the
         * WSL strategy regardless of whether the user has set up WSL —
         * the strategy's [restart] call will surface a clear error if
         * `wsl.exe` is missing.
         *
         * Future: if Hermes ever ships a Windows-native CLI, branch here
         * to pick WindowsNativeStrategy for users who have it installed.
         */
        fun forCurrentOs(): DashboardProcessStrategy =
            pick(System.getProperty("os.name") ?: "")

        /**
         * Variant that honours the user's `useWsl` setting on Windows.
         *
         * On macOS / Linux, [useWsl] is ignored: there's no WSL to
         * toggle, and the native Mac/Linux strategy is the right answer
         * regardless.
         *
         * On Windows the logic is:
         * - `useWsl=true` (default): [WindowsWslStrategy]. Behaviour
         *   unchanged from the no-arg overload. If `wsl.exe` is missing
         *   the strategy surfaces its own clear error.
         * - `useWsl=false`: [MacLinuxStrategy] (which on Windows looks
         *   for `hermes.exe` on the user's Windows PATH). Whether or
         *   not WSL is installed on the host is **irrelevant** — the
         *   user has explicitly opted out of the WSL path, so we run
         *   the native launcher. If the native binary is missing the
         *   strategy surfaces its own clear error ("Cannot find
         *   hermes on PATH and ~/.local/bin/hermes does not exist").
         *
         * The WSL-detector is NOT consulted here. WslDetector is
         * used only by the Settings panel to decide whether to show
         * the "Use WSL" checkbox in the first place (your spec:
         * "未安装就不显示"). Once the user has flipped the toggle,
         * the strategy picker trusts the intent.
         */
        fun forCurrentOs(useWsl: Boolean): DashboardProcessStrategy =
            pickWithUseWsl(System.getProperty("os.name") ?: "", useWsl)

        /**
         * Pure dispatch: given an `os.name` string (as reported by
         * `System.getProperty("os.name")`), return the strategy that
         * should handle it. Split out so unit tests can verify each
         * branch without monkey-patching the JVM's system properties.
         *
         * Match rules are deliberately permissive (`contains` rather than
         * `startsWith`) because:
         * - Windows reports "Windows 10", "Windows 11", "Windows Server 2022"
         * - macOS reports "Mac OS X" on older JVMs, "Mac OS X 14.x" now
         * - Linux reports "Linux" on HotSpot but "Linux/arm64" on some
         *   downstream distributions
         * A user setting `os.name` to anything containing "win" should
         * be treated as Windows — there's no plausible false positive.
         */
        fun pick(osName: String): DashboardProcessStrategy {
            val os = osName.lowercase()
            // Match rules must be ordered Windows-Last because the
            // string "Darwin" (macOS kernel name) contains the substring
            // "win" — naive `os.contains("win")` would misclassify
            // every macOS JVM as Windows. We disambiguate by requiring
            // a Windows-specific word boundary ("win" without an
            // adjacent 'dar' prefix) or a known Windows-family token.
            val isWindows = os.contains("windows") ||
                // Catches "Win32", "Win64", "WinNT" — what cygwin/msys
                // historically reported. We don't add bare "win" because
                // of the "Darwin" collision above.
                os.contains("win32") || os.contains("win64") || os.contains("winnt")
            val isMac = os.contains("mac") ||
                os == "darwin" ||  // exact match — the macOS kernel name
                os.startsWith("darwin ") || os.startsWith("darwin/")
            val isLinux = os == "linux" ||
                os.startsWith("linux ") || os.startsWith("linux/") ||
                // BSDs and other Unixes. Strict "nix" matches "Unix"
                // (capital U is fine — we lowercased).
                os.contains("nix") || os.contains("bsd") || os.contains("freebsd")
            return when {
                isWindows -> WindowsWslStrategy()
                isMac -> MacLinuxStrategy(isMac = true)
                isLinux -> MacLinuxStrategy(isMac = false)
                // Unknown / exotic (Plan 9, Haiku, ReactOS, ...) — fall
                // back to Mac/Linux path. `nohup`/`setsid` are
                // POSIX-portable enough that this is the safest default;
                // picking WindowsWslStrategy here would try to spawn
                // `wsl.exe` on a system that may not have it.
                else -> MacLinuxStrategy(isMac = false)
            }
        }

        /**
         * Like [pick] but applies the [useWsl] override on Windows.
         * On Windows + useWsl=false we return the native
         * [MacLinuxStrategy] unconditionally — the host may or may
         * not have WSL installed, but the user has opted out of the
         * WSL path either way, so we run the native launcher. The
         * strategy's own "hermes not found" error message is the
         * right escape hatch when the native binary is missing.
         */
        fun pickWithUseWsl(osName: String, useWsl: Boolean): DashboardProcessStrategy {
            val base = pick(osName)
            // Non-Windows: useWsl is irrelevant. Return the base.
            if (base !is WindowsWslStrategy) return base
            // Windows + useWsl=true: same as no-arg pick.
            if (useWsl) return base
            // Windows + useWsl=false: native launcher. WSL's presence
            // is irrelevant — the user said they want native.
            return MacLinuxStrategy(isMac = false)
        }
    }
}