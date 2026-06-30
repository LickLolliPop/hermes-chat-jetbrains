package com.hermes.agent.jetbrains.dashboard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * WSL-hosted dashboard strategy for Windows IDEs.
 *
 * Why the WSL path is so different from the native path: when `wsl.exe`
 * exits (it's a one-shot launcher), it sends SIGTERM to the entire WSL
 * process group it spawned. `nohup`, `setsid`, `( ... &)` all fail in
 * practice — the child dies with the parent. `tmux new-session -d` is the
 * only reliable way to start a long-lived process from a one-shot
 * `wsl.exe` call: the tmux server is itself a daemon that survives the
 * caller exiting.
 *
 * For Mac/Linux, see [MacLinuxStrategy] — they use `nohup` directly
 * because there's no cross-OS process-group boundary.
 *
 * Field-tested 2026-06-29 / 2026-07-01. See
 * `jetbrains-platform-plugin/references/wsl-dashboard-process-manager.md`
 * for the full rationale.
 */
class WindowsWslStrategy : DashboardProcessStrategy {

    private val log = logger<WindowsWslStrategy>()

    override val osLabel: String = "WSL"

    override fun restart(onComplete: (DashboardProcessManager.Result) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                doRestart()
            } catch (t: Throwable) {
                log.warn("WSL restart failed", t)
                DashboardProcessManager.Result(t.message ?: t::class.java.simpleName, fatal = true)
            }
            ApplicationManager.getApplication().invokeLater { onComplete(result) }
        }
    }

    private fun doRestart(): DashboardProcessManager.Result {
        // 1. Stop. Best-effort: --stop fails when nothing's running, which is fine.
        runWsl("hermes dashboard --stop", timeoutSec = 10)
        // Also clean up the tmux session we may have left from a previous
        // restart — --stop kills the process but doesn't remove the
        // (now-defunct) session, and a stale session would silently
        // swallow the next start command.
        runWsl("tmux kill-session -t hermes-dash 2>/dev/null; true", timeoutSec = 3)
        // Give the port a moment to actually free up. --stop is async on the
        // server side; without this sleep, the next start can hit "port in use"
        // ~20% of the time on the dev box.
        Thread.sleep(800)

        // 2. Decide skip-build by probing dist inside WSL. Use the
        //    discovered $HOME so the path is correct across WSL distros
        //    (the dev box happens to use `/home/administrator`, but a
        //    user-installed Ubuntu might use `/home/<other>`).
        val home = homeDir()
        val distExists = probeWslFile("$home/.hermes/hermes-agent/hermes_cli/web_dist/index.html")
        log.info("WindowsWslStrategy: distExists=$distExists, using ${if (distExists) "--skip-build" else "full build"}")

        // 3. Start, detached from the wsl.exe parent shell.
        val buildFlag = if (distExists) "--skip-build" else ""
        val tmuxCmd = "tmux new-session -d -s hermes-dash " +
            "'hermes dashboard --no-open $buildFlag >/tmp/hermes-dashboard.log 2>&1'"
        val startResult = runWsl(tmuxCmd, timeoutSec = 5)
        if (startResult.exitCode != 0) {
            return DashboardProcessManager.Result(
                "Failed to launch dashboard (exit=${startResult.exitCode}): ${startResult.stderr.take(500)}",
                fatal = true
            )
        }

        // 4. Poll for port. 30s total. Probe every 500ms.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            if (isPortListeningInWsl(9119)) {
                log.info("WindowsWslStrategy: dashboard up on 127.0.0.1:9119")
                return DashboardProcessManager.Result(
                    "Dashboard restarted (${if (distExists) "skip-build" else "full build"})",
                    fatal = false
                )
            }
            Thread.sleep(500)
        }
        // One last chance: read the log and report what went wrong.
        val logTail = runWsl("tail -20 /tmp/hermes-dashboard.log 2>/dev/null", timeoutSec = 3).stdout
        return DashboardProcessManager.Result(
            "Dashboard started but port 9119 did not come up within 30s. " +
                "Log tail: ${logTail.take(400).ifBlank { "(empty)" }}",
            fatal = true
        )
    }

    override fun isRunning(): Boolean =
        runWsl("hermes dashboard --status", timeoutSec = 3).exitCode == 0

    override fun homeDir(): String {
        // POSIX-portable `$HOME` lookup. Strip newlines because some WSL
        // distros' /etc/profile append a trailing newline to `echo $HOME`.
        val r = runWsl("echo \$HOME", timeoutSec = 3)
        val home = r.stdout.trim()
        // Sanity: an empty or non-absolute result means $HOME wasn't set.
        // Fall back to the conventional Ubuntu path; this is best-effort,
        // the distExists probe below will still surface a real miss.
        return home.ifEmpty { "/home/administrator" }
    }

    // ------------------------------------------------------------------
    // Low-level WSL plumbing

    private data class WslResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runWsl(command: String, timeoutSec: Long): WslResult {
        // Build argv directly to avoid shell-quoting issues on Windows.
        // wsl.exe accepts: wsl.exe -- bash -lc "<command>"  (also: wsl.exe bash -c "..." on newer builds)
        // Use `bash -lc` to source the user's profile (PATH etc).
        val proc = ProcessBuilder("wsl.exe", "bash", "-lc", command)
            .redirectErrorStream(false)
            .start()

        val stdout = readBounded(proc.inputStream)
        val stderr = readBounded(proc.errorStream)
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return WslResult(-1, stdout, "$stderr\n[timed out after ${timeoutSec}s]")
        }
        return WslResult(proc.exitValue(), stdout, stderr)
    }

    private fun probeWslFile(path: String): Boolean {
        // `test -f` is the POSIX-portable existence check. Quote the path so
        // spaces in the path don't split it. bash -lc evaluates the quote
        // and the inner single-quotes are safe.
        val r = runWsl("test -f '${path.replace("'", "'\\''")}' && echo Y || echo N", timeoutSec = 3)
        return r.stdout.trim() == "Y"
    }

    private fun isPortListeningInWsl(port: Int): Boolean {
        // ss -tlnH: -H suppresses header, -t tcp, -l listening, -n numeric.
        // grep the port with `:9119` (avoid matching 19119 etc). The grep
        // exit code is the test.
        val r = runWsl(
            "ss -tlnH 2>/dev/null | grep -q ':$port ' && echo Y || echo N",
            timeoutSec = 3
        )
        return r.stdout.trim() == "Y"
    }

    private fun readBounded(stream: java.io.InputStream): String {
        val sb = StringBuilder()
        BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { r ->
            val buf = CharArray(4096)
            while (sb.length < DashboardProcessManager.MAX_OUTPUT) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }
}