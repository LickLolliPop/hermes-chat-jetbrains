package com.hermes.agent.jetbrains.dashboard

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Native dashboard strategy for macOS and Linux hosts.
 *
 * Mirrors the design documented in
 * `references/cross-os-process-manager.md` §"macOS / Linux: no
 * process-group boundary": the JVM-spawned child is in the IDE's
 * normal process tree, so `nohup ... &` is enough to detach it.
 * No `tmux` needed — that's a WSL-only requirement (see
 * `WindowsWslStrategy` and `references/wsl-dashboard-process-manager.md`).
 *
 * Design choices (matching the cross-os skill reference):
 *
 * - **PID file at `~/.hermes/dashboard.pid`**: so we can `kill` the
 *   child cleanly on restart instead of relying on the CLI's --stop
 *   (which itself calls `kill <pid>` against the same file on the
 *   CLI side). This makes the stop/start idempotent across IDE
 *   crashes that left an orphaned process.
 *
 * - **`setsid nohup <cmd> </dev/null >log 2>&1 &`**: `setsid` puts
 *   the child in a new session (so even an IDE kill -9 doesn't take
 *   it down — paranoia), `nohup` ignores SIGHUP, the redirects
 *   detach stdio from the IDE's terminal, and the trailing `&`
 *   backgrounds it. This is the standard "fire and forget" recipe
 *   that the cross-os reference documents.
 *
 * - **`lsof -nP -iTCP:PORT -sTCP:LISTEN`**: macOS doesn't ship `ss`
 *   before Sonoma. `lsof` is on every macOS install since 10.5 and
 *   exits 0 when at least one matching listener exists. `ss -tlnH`
 *   is preferred on Linux (cheaper, more scriptable).
 *
 * - **Log at `~/.hermes/dashboard.log`**: same location as the PID
 *   file so the user can `cat ~/.hermes/dashboard.{log,pid}` to
 *   diagnose. Apple/XDG conventions are nice but for a dev-tool
 *   the "everything in `~/.hermes/`" rule is easier to find.
 *
 * Fallback for missing `setsid`: `nohup <cmd> </dev/null >log 2>&1 &
 * disown` works without `setsid` (cross-os skill §"Diagnostic recipe"
 * item 2). We don't currently detect this — if a user reports
 * "restart doesn't work" on a stripped macOS sandbox, add a `which
 * setsid` probe and fall back.
 */
class MacLinuxStrategy(
    private val isMac: Boolean,
) : DashboardProcessStrategy {

    private val log = logger<MacLinuxStrategy>()

    override val osLabel: String = if (isMac) "macOS" else "Linux"

    override fun restart(onComplete: (DashboardProcessManager.Result) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = try {
                doRestart()
            } catch (t: Throwable) {
                log.warn("Native restart failed", t)
                DashboardProcessManager.Result(t.message ?: t::class.java.simpleName, fatal = true)
            }
            ApplicationManager.getApplication().invokeLater { onComplete(result) }
        }
    }

    private fun doRestart(): DashboardProcessManager.Result {
        val home = homeDir()

        // 1. Stop previous instance via PID file (if any). The CLI also
        //    has `hermes dashboard --stop` which writes/reads the same
        //    file, so we honour whatever the user may have set up.
        val pidFile = File(home, ".hermes/dashboard.pid")
        if (pidFile.exists()) {
            val oldPid = pidFile.readText().trim().toLongOrNull()
            if (oldPid != null) {
                ProcessHandle.of(oldPid).ifPresent { handle ->
                    if (handle.isAlive) {
                        log.info("MacLinuxStrategy: stopping previous dashboard (pid=$oldPid)")
                        handle.destroy()
                        // Wait up to 2s for graceful exit. If the process
                        // is stuck in a syscall, fall through to
                        // destroyForcibly below — we'd rather have a
                        // 2-second window of broken state than a hung
                        // restart button.
                        val deadline = System.currentTimeMillis() + 2_000
                        while (handle.isAlive && System.currentTimeMillis() < deadline) {
                            Thread.sleep(100)
                        }
                        if (handle.isAlive) handle.destroyForcibly()
                    }
                }
            }
            pidFile.delete()
        }
        // Socket close is near-instant on macOS/Linux; 500ms is plenty
        // (matches cross-os reference §"MacStrategy" sketch).
        Thread.sleep(500)

        // 2. Decide --skip-build by probing dist with $HOME expansion.
        //    $HOME/.hermes/... is the universal Hermes install location
        //    (don't hardcode /home/administrator — that's a Windows-IDE
        //    developer's box only).
        val distExists = File("$home/.hermes/hermes-agent/hermes_cli/web_dist/index.html").exists()
        val buildFlag = if (distExists) "--skip-build" else ""
        log.info("MacLinuxStrategy: distExists=$distExists, using ${if (distExists) "--skip-build" else "full build"}")

        // 3. Resolve the `hermes` binary. `command -v hermes` finds
        //    anything on $PATH; we fall back to ~/.local/bin/hermes (uv
        //    tool install's default) for the case where the user
        //    installed hermes via uv but their login shell isn't
        //    sourcing the path-mutating profile (common when launching
        //    the IDE from Finder or a .desktop file).
        val hermesBin = resolveHermesBinary() ?: return DashboardProcessManager.Result(
            "Cannot find `hermes` on PATH and ~/.local/bin/hermes does not exist. " +
                "Install Hermes CLI first (e.g. `uv tool install hermes-cli`), then click Restart again.",
            fatal = true,
        )

        // 4. Start, detached. setsid + nohup + & + redirected stdio +
        //    `echo $! > pidfile` so the next restart can stop us cleanly.
        val logFile = File(home, ".hermes/dashboard.log")
        logFile.parentFile?.mkdirs()
        val pidFileAbs = pidFile.absolutePath
        val logFileAbs = logFile.absolutePath
        val shellCmd = "setsid nohup '$hermesBin' dashboard --no-open $buildFlag " +
            "</dev/null >'$logFileAbs' 2>&1 & echo \$! > '$pidFileAbs'"
        val startProc = ProcessBuilder("bash", "-lc", shellCmd)
            .redirectErrorStream(true)
            .start()
        val finished = startProc.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            startProc.destroyForcibly()
            return DashboardProcessManager.Result(
                "Failed to launch dashboard (timed out spawning `setsid nohup hermes …`). " +
                    "Check that `hermes` is on your PATH (or install with `uv tool install hermes-cli`).",
                fatal = true,
            )
        }
        // If `setsid` isn't on PATH, the shell exits immediately with a
        // non-zero status and the dashboard never starts. Surface that
        // as a clear error instead of waiting 30s on a silent failure.
        if (startProc.exitValue() != 0) {
            val stderr = readBounded(startProc.inputStream)
            return DashboardProcessManager.Result(
                "Failed to launch dashboard (setsid/nohup exit=${startProc.exitValue()}): " +
                    stderr.take(500),
                fatal = true,
            )
        }

        // 5. Poll for port. 30s total. lsof on macOS, ss on Linux.
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30)
        while (System.currentTimeMillis() < deadline) {
            if (isPortListening(9119)) {
                log.info("MacLinuxStrategy: dashboard up on 127.0.0.1:9119")
                return DashboardProcessManager.Result(
                    "Dashboard restarted (${if (distExists) "skip-build" else "full build"})",
                    fatal = false,
                )
            }
            Thread.sleep(500)
        }
        // Last-chance log tail.
        val logTail = runCommandForLog(arrayOf("bash", "-lc", "tail -20 '$logFileAbs' 2>/dev/null"), timeoutSec = 3).stdout
        return DashboardProcessManager.Result(
            "Dashboard started but port 9119 did not come up within 30s. " +
                "Log tail: ${logTail.take(400).ifBlank { "(empty)" }}",
            fatal = true,
        )
    }

    override fun isRunning(): Boolean = isPortListening(9119)

    override fun homeDir(): String = System.getProperty("user.home") ?: "."

    /**
     * Test seam: invoke the private [doRestart] path directly so tests
     * can assert on the result without going through the
     * `executeOnPooledThread` + `invokeLater` dance (which requires a
     * live `ApplicationManager`). Marked `internal` so the test source
     * set can see it.
     */
    internal fun doRestartForTest(): DashboardProcessManager.Result = doRestart()

    /**
     * Test seam: invoke the private [resolveHermesBinary] path. Used by
     * `MacLinuxStrategyLifecycleTest` to verify the
     * PATH → ~/.local/bin/hermes fallback without spinning up a full
     * restart.
     */
    internal fun resolveHermesBinaryForTest(): String? = resolveHermesBinary()

    /**
     * Test seam: invoke the private [isPortListening] path. Used by
     * `MacLinuxStrategyPortProbeTest` to verify the ss / lsof dual-probe
     * without needing a real running dashboard.
     */
    internal fun isPortListeningForTest(port: Int): Boolean = isPortListening(port)

    // ------------------------------------------------------------------
    // Native (Mac/Linux) plumbing

    /**
     * Resolve the `hermes` CLI binary. `command -v` is POSIX-portable
     * (works in both bash and zsh, on macOS and Linux, doesn't print
     * aliases or functions, which `which` sometimes does and which
     * would give false positives).
     *
     * Fallback: `~/.local/bin/hermes` — uv's `uv tool install` default
     * location. This covers the common case where the user installed
     * hermes via uv but their login shell isn't sourcing the
     * path-mutating profile (common when launching the IDE from
     * Finder or a .desktop file).
     */
    private fun resolveHermesBinary(): String? {
        val onPath = runCommandForLog(
            arrayOf("bash", "-lc", "command -v hermes"),
            timeoutSec = 3,
        ).stdout.trim()
        if (onPath.isNotEmpty()) return onPath
        val localBin = File(homeDir(), ".local/bin/hermes")
        return if (localBin.exists() && localBin.canExecute()) localBin.absolutePath else null
    }

    /**
     * Port probe. `lsof` is the cross-OS-portable primary (works on
     * all macOS releases since 10.5 and is shipped by default). On
     * Linux `ss` is cheaper (already-installed iproute2) and we prefer
     * it for the scriptable exit code.
     *
     * Both produce exit 0 when there's at least one listener.
     */
    private fun isPortListening(port: Int): Boolean {
        if (!isMac) {
            // Linux: ss -tlnH | grep -q ':PORT ' && echo Y || echo N
            val ss = runCommandForLog(
                arrayOf("bash", "-lc", "ss -tlnH 2>/dev/null | grep -q ':$port ' && echo Y || echo N"),
                timeoutSec = 3,
            )
            if (ss.stdout.trim() == "Y") return true
            // ss missing on some minimal containers — fall back to lsof
        }
        val lsof = runCommandForLog(
            arrayOf("bash", "-lc", "lsof -nP -iTCP:$port -sTCP:LISTEN >/dev/null 2>&1 && echo Y || echo N"),
            timeoutSec = 3,
        )
        return lsof.stdout.trim() == "Y"
    }

    private data class CmdResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runCommandForLog(argv: Array<String>, timeoutSec: Long): CmdResult {
        val proc = ProcessBuilder(*argv)
            .redirectErrorStream(false)
            .start()
        val stdout = readBounded(proc.inputStream)
        val stderr = readBounded(proc.errorStream)
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return CmdResult(-1, stdout, "$stderr\n[timed out after ${timeoutSec}s]")
        }
        return CmdResult(proc.exitValue(), stdout, stderr)
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