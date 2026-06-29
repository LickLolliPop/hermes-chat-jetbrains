package com.hermes.agent.jetbrains.dashboard

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Manages the WSL-hosted Hermes dashboard process.
 *
 * Why this exists as a separate class: the IDE runs on Windows but the
 * dashboard runs in WSL (where `hermes` is installed at
 * `/home/administrator/.local/bin/hermes`). Shell-out from the IDE means
 * launching `wsl.exe bash -lc "hermes dashboard ..."`, NOT just `hermes ...`
 * (the Windows PATH has no `hermes.exe`).
 *
 * The shell-out MUST happen off the EDT (skill rule). The status callback
 * is invoked on the EDT via `invokeLater` so the UI can update safely.
 *
 * Why `--stop` first instead of probing `--status`: the user asked for
 * "always restart" semantics. `--status` would just tell us it's already
 * running, but we want to clear zombies (an earlier run that crashed without
 * un-registering) and pick up code changes from `hermes-cli` updates. The
 * cost of an extra `hermes dashboard --stop` call (~200ms) is negligible.
 *
 * Why `--no-open`: the IDE's JCEF browser is the consumer, not a system
 * browser. Without `--no-open`, `hermes dashboard` would try to launch
 * `xdg-open` which hangs in WSL2 (well-known WSL2 + Windows browser interop
 * bug, see the hermes-cli docs). The flag is in `hermes dashboard --help`
 * output and is safe to always pass.
 */
class DashboardProcessManager {

    private val log = logger<DashboardProcessManager>()

    /**
     * Restarts the dashboard: stop (best-effort) → wait for port → start.
     *
     * Behavior:
     *  1. Spawn `wsl.exe bash -lc "hermes dashboard --stop"`. Don't fail
     *     if --stop errors (e.g. nothing running). Wait briefly for the
     *     port to free up.
     *  2. Probe `~/.hermes/hermes-agent/hermes_cli/web_dist/index.html`
     *     inside WSL. If it exists, pass `--skip-build`; otherwise the
     *     full build runs.
     *  3. Spawn `wsl.exe bash -lc "hermes dashboard --no-open [--skip-build]"`.
     *  4. Poll `ss -tln` inside WSL for port 9119 to confirm it came up.
     *     30s total timeout (build may take longer, but on this dev box
     *     with a hot dist it's <1s).
     *  5. Invoke [onComplete] on the EDT with success/failure.
     *
     * The dashboard is launched as a backgrounded `nohup` so it survives
     * the shell exit. Without `nohup`, the process is killed when the
     * `wsl.exe` parent shell terminates (Hermes dashboard is a long-lived
     * server, not a one-shot command).
     */
    fun restartDashboard(onComplete: (Result) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                doRestart()
            } catch (t: Throwable) {
                log.warn("restartDashboard failed", t)
                Result(t.message ?: t::class.java.simpleName, fatal = true)
            }
            ApplicationManager.getApplication().invokeLater {
                onComplete(result)
            }
        }
    }

    private fun doRestart(): Result {
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

        // 2. Decide skip-build by probing dist inside WSL.
        val distExists = probeWslFile(
            "/home/administrator/.hermes/hermes-agent/hermes_cli/web_dist/index.html"
        )
        log.info("DashboardProcessManager: distExists=$distExists, using ${if (distExists) "--skip-build" else "full build"}")

        // 3. Start, detached from the wsl.exe parent shell.
        //
        // Why tmux, not nohup/setsid/subshell-`&`: when wsl.exe exits, it
        // sends SIGTERM to the entire WSL process group spawned by that
        // invocation. `nohup`, `setsid`, and `( ... &)` all fail in practice
        // on this dev box — the child dies with the parent. `tmux
        // new-session -d` is the only reliable way to start a long-lived
        // process from a one-shot wsl.exe call: tmux server is itself a
        // daemon that survives the caller exiting.
        //
        // The session name `hermes-dash` is intentional — we kill it in
        // step 1 above to keep restarts idempotent.
        val buildFlag = if (distExists) "--skip-build" else ""
        val tmuxCmd = "tmux new-session -d -s hermes-dash " +
            "'hermes dashboard --no-open $buildFlag >/tmp/hermes-dashboard.log 2>&1'"
        val startResult = runWsl(tmuxCmd, timeoutSec = 5)
        if (startResult.exitCode != 0) {
            return Result(
                "Failed to launch dashboard (exit=${startResult.exitCode}): ${startResult.stderr.take(500)}",
                fatal = true
            )
        }

        // 4. Poll for port. 30s total. Probe every 500ms.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            if (isPortListeningInWsl(9119)) {
                log.info("DashboardProcessManager: dashboard up on 127.0.0.1:9119")
                return Result(
                    "Dashboard restarted (${if (distExists) "skip-build" else "full build"})",
                    fatal = false
                )
            }
            Thread.sleep(500)
        }
        // One last chance: read the log and report what went wrong.
        val logTail = runWsl("tail -20 /tmp/hermes-dashboard.log 2>/dev/null", timeoutSec = 3).stdout
        return Result(
            "Dashboard started but port 9119 did not come up within 30s. " +
                "Log tail: ${logTail.take(400).ifBlank { "(empty)" }}",
            fatal = true
        )
    }

    /**
     * Returns true if `hermes dashboard --status` exits 0 (dashboard running
     * and responding). Cheap probe; we can use it from the UI to enable/disable
     * the restart button or to show "dashboard is already running" feedback.
     */
    fun isDashboardRunning(): Boolean {
        val r = runWsl("hermes dashboard --status", timeoutSec = 3)
        return r.exitCode == 0
    }

    // ------------------------------------------------------------------
    // Low-level WSL plumbing

    /**
     * Result of a synchronous `wsl.exe` invocation. stdout/stderr are
     * captured fully but trimmed to [MAX_OUTPUT] chars to avoid OOM in
     * the (unlikely) case of a misbehaving command.
     */
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
            while (sb.length < MAX_OUTPUT) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return sb.toString()
    }

    /**
     * Result of a [restartDashboard] attempt. `fatal=true` means the user
     * should be told something went wrong; `fatal=false` is informational
     * (dashboard is up, all good).
     */
    data class Result(val message: String, val fatal: Boolean) {
        val success: Boolean get() = !fatal
    }

    companion object {
        private const val MAX_OUTPUT = 64 * 1024

        /**
         * Show a balloon to the user. The notification group "Hermes Chat"
         * is pre-registered in plugin.xml (see `<notificationGroup>`), so we
         * just resolve it via the modern NotificationGroupManager API.
         *
         * Note: `registerNotificationGroup(...)` on NotificationGroupManager
         * is `@ApiStatus.Internal` in Platform 2024.2+ — calling it from
         * plugin code triggers an `Unresolved reference` compile error
         * (the symbol is generated but excluded from the public API). The
         * correct path is to register in plugin.xml and resolve at runtime.
         */
        fun notify(message: String, type: NotificationType) {
            val mgr = NotificationGroupManager.getInstance()
            val group = mgr.getNotificationGroup("Hermes Chat")
                ?: error("Notification group 'Hermes Chat' not registered in plugin.xml")
            group.createNotification(message, type).notify(null)
        }
    }
}
