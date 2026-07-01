package com.hermes.agent.jetbrains.dashboard

import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Detects whether WSL is installed and usable on the current Windows host.
 *
 * Used by the Settings panel to decide whether to show the "Use WSL for
 * dashboard" checkbox. If WSL isn't installed, the checkbox is hidden
 * entirely — the user can't toggle something that doesn't exist on their
 * box, and showing it would just confuse them.
 *
 * "Installed" means two things, BOTH of which must be true:
 *   1. `wsl.exe` is reachable on %PATH% (or the standard
 *      `C:\Windows\System32\wsl.exe` location — sometimes PATH-stripped
 *      when the IDE is launched from Start Menu).
 *   2. At least one WSL distro is registered (the user has actually
 *      enabled WSL inside Windows, not just installed the WSL kernel).
 *      `wsl.exe --status` exits 0 in both cases (kernel installed, distro
 *      missing) and prints a different message, so we can't rely on the
 *      exit code alone — we grep for "Default Distribution" or any
 *      registered distro line.
 *
 * Caching: results are cached for the JVM lifetime. Re-detection is only
 * triggered by [refresh], which the Settings panel can call from a
 * "Re-detect" button if the user just installed WSL. We deliberately do
 * NOT re-probe on every Settings open — the `wsl.exe --status` call
 * takes ~200-500ms and settings open is a hot path during onboarding.
 *
 * Why this lives in `dashboard/` and not `client/` or its own package:
 * it's only consumed by the dashboard process-management stack
 * ([DashboardProcessStrategy] picker, [HermesChatConfigurable] checkbox
 * visibility, [HermesChatToolWindowFactory]'s restart button). Keeping
 * it next to the strategies that depend on it avoids a cross-package
 * import for a 100-line helper.
 */
class WslDetector(
    /**
     * Test seam: function that returns true if `wsl.exe` is reachable.
     * In production this checks `C:\Windows\System32\wsl.exe` and falls
     * back to a PATH search. Tests can inject a fake to avoid
     * filesystem/process dependencies.
     */
    private val wslExeProbe: () -> Boolean = ::probeWslExeOnWindows,
    /**
     * Test seam: function that returns true if at least one WSL distro
     * is registered. In production this runs `wsl.exe --status` and
     * greps for "Default Distribution:". Tests inject a stub.
     */
    private val distroProbe: () -> Boolean = ::probeDistroViaWslStatus,
) {

    private val log = logger<WslDetector>()

    /**
     * Result of a single [isInstalled] probe. Cached so the Settings
     * panel can poll it without spawning `wsl.exe` repeatedly.
     */
    @Volatile private var cached: Boolean? = null

    /**
     * Synchronous probe. Returns the cached value if available; otherwise
     * runs the two-step check (wsl.exe present → distro registered) and
     * caches the result. Always returns false on non-Windows hosts — the
     * detector is a no-op on macOS/Linux.
     */
    fun isInstalled(): Boolean {
        cached?.let { return it }
        val detected = probe()
        cached = detected
        log.info("WslDetector: WSL installed=$detected")
        return detected
    }

    /**
     * Drop the cache and re-probe. Used by the Settings "Re-detect"
     * button for users who just installed WSL. Safe to call from the
     * EDT (the probe has a 5s overall timeout).
     */
    fun refresh(): Boolean {
        val detected = probe()
        cached = detected
        log.info("WslDetector: refresh → WSL installed=$detected")
        return detected
    }

    private fun probe(): Boolean {
        // Non-Windows: WSL is meaningless. Don't even try to spawn
        // `wsl.exe` — the WSL kernel exists on macOS/Linux paths as
        // a real Linux, not as a Windows-on-Linux layer.
        val os = System.getProperty("os.name") ?: ""
        if (!os.contains("Windows", ignoreCase = true) &&
            !os.contains("Win32", ignoreCase = true) &&
            !os.contains("Win64", ignoreCase = true) &&
            !os.contains("WinNT", ignoreCase = true)
        ) {
            return false
        }
        // Step 1: wsl.exe present? Fail-closed on any exception
        // (filesystem errors, process spawn failures, etc). The
        // default impl already swallows its own errors, but tests
        // inject raw lambdas that can throw — we want consistent
        // "treat errors as not-installed" semantics regardless of
        // whether the probe is the prod impl or a test stub.
        val wslExePresent = try {
            wslExeProbe()
        } catch (t: Throwable) {
            log.warn("WslDetector: wsl.exe probe failed: ${t.message}")
            false
        }
        if (!wslExePresent) return false
        // Step 2: at least one distro registered? Same fail-closed.
        return try {
            distroProbe()
        } catch (t: Throwable) {
            log.warn("WslDetector: distro probe failed: ${t.message}")
            false
        }
    }

    companion object {
        /**
         * Default `wsl.exe` probe: checks the absolute
         * `C:\Windows\System32\wsl.exe` path first, then falls back
         * to `where wsl.exe` from the PATH.
         */
        private fun probeWslExeOnWindows(): Boolean {
            val system32 = File("C:\\Windows\\System32\\wsl.exe")
            if (system32.exists()) return true
            return try {
                val proc = ProcessBuilder("where", "wsl.exe")
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(3, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return false
                }
                // `where` prints one path per match. Exit 0 = at least one
                // match. We don't care WHICH path — just that one exists.
                val out = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                    .use { it.readText() }
                out.lineSequence().any { it.trim().endsWith("wsl.exe", ignoreCase = true) }
            } catch (t: Throwable) {
                false
            }
        }

        /**
         * Default distro probe: runs `wsl.exe --status` and looks for
         * "Default Distribution:" in the output. The output format
         * depends on WSL version:
         *
         *   Installed WSL (kernel + distro):
         *     "Default Distribution: Ubuntu"
         *     "Default Version: 2"
         *
         *   Kernel installed but NO distro (rare, post-reset):
         *     "No distributions are currently installed."
         *     exit 0
         *
         *   WSL not enabled:
         *     "Please enable the Windows Subsystem for Linux feature..."
         *     exit 1
         *
         * We treat "Default Distribution:" as the positive signal — it's
         * only printed when a distro is registered, regardless of version.
         */
        private fun probeDistroViaWslStatus(): Boolean {
            return try {
                val proc = ProcessBuilder("wsl.exe", "--status")
                    .redirectErrorStream(true)
                    .start()
                val finished = proc.waitFor(5, TimeUnit.SECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return false
                }
                if (proc.exitValue() != 0) return false
                val out = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
                    .use { it.readText() }
                out.contains("Default Distribution:", ignoreCase = true) ||
                    // Belt-and-braces: some WSL builds print "Default Version:"
                    // but omit the distribution line. If the user has ANY
                    // version configured we treat that as a positive signal.
                    out.contains("Default Version:", ignoreCase = true)
            } catch (t: Throwable) {
                false
            }
        }
    }
}
