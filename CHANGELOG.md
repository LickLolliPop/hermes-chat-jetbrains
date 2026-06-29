# Changelog

All notable changes to Hermes Chat for JetBrains IDEs.

## [Unreleased]

### Fixed
- **P0 listener accumulation**: `HermesChatPanel.renderStatus()` was calling
  `header.addMouseListener(new MouseAdapter { ... })` on every 8s status
  probe, so a few hours of idle uptime accumulated ~1500 anonymous
  listeners on the header JBLabel. A single mouse hover then fired all
  of them simultaneously, spawning ~1500 `msedge.exe` processes and
  freezing the entire IDE. The header mouse listener is now attached
  once in `init {}` from a persistent field, and `buildFallbackLink()`
  uses a similar persistent HyperlinkListener field instead of an
  anonymous lambda. See `E:\Temp\androidPlugin\HermesChat\BUG-2026-06-29-HermesChat-Listener-Leak.md`
  for the full freeze-thread-dump analysis.
- **`openInExternalBrowser()` debounce (500 ms)**: Added as a safety net
  using `AtomicLong.compareAndSet` for thread-safe lock-free check. If
  a listener ever leaks again (regression or new code), repeated calls
  within half a second are silently dropped instead of spawning
  1000+ browser processes.
- **Timer / listener leak across project close**: `HermesChatPanel` now
  implements `com.intellij.openapi.Disposable`; its `dispose()` stops
  the 8s refresh timer and removes the header mouse listener. The
  panel is registered with the project-level Disposer tree in
  `HermesChatToolWindowFactory.createToolWindowContent`, so the IDE
  automatically calls `panel.dispose()` when the project closes. No
  more leaking timers or listeners across long-lived IDE sessions.

### Changed
- **Polling timer backoff on unreachable dashboard**: The 8s refresh
  timer now stretches out when the dashboard is down — `8s → 30s → 60s
  → 300s` based on `consecutiveUnreachable` count (reset by any
  successful probe). Idle IDE sessions no longer hammer a dead socket
  at 0.125 req/s indefinitely. A single transient blip still uses 8s
  to avoid UI jitter.
- **Per-tick log lines downgraded to DEBUG**: `refreshStatus` was
  logging 5 INFO lines per tick (tick, isReachable, kicking off,
  fetchStatus, fetchModels). An idle 8h session added ~18k lines to
  `idea.log`, drowning real errors. All trace lines now log at DEBUG;
  the only per-tick INFO is the UNREACHABLE state-change line
  (which carries the consecutive-failure count and next probe delay).
  Bump `#com.hermes.agent.jetbrains.ui.HermesChatPanel` to DEBUG in
  `Help → Diagnostic Tools → Debug Log Settings` to see the full
  trace when needed.

### Added
- ↻ Restart button in the toolwindow header (right side of the status row).
  Restarts the WSL-hosted Hermes dashboard in place: stops the running
  process, polls for port 9119 to free, then re-launches with
  `--skip-build` if `web_dist/index.html` exists, otherwise a full build.
  Status label swaps to "Restarting dashboard…" while the restart is
  in flight; on success, the JCEF browser reloads `/chat` and the
  status probe runs immediately. Notifications are shown for both
  success and failure paths.
- Visible error + manual Retry button for the dashboard token fetch.
  When the auto-token fetch fails, the footer model label switches to
  `Model: ✗ {error message}` in red and a "↻ Retry" button appears next
  to the existing "Open in browser" button. Clicking Retry clears the
  autoToken latch in HermesClient and re-runs the fetch + status probe
  immediately. The previous behavior was a silent "Model: (loading…)"
  that never updated after the first failed fetch.
- **Regression tests for the P0 listener-accumulation fix**:
  - `HermesChatPanelBackoffTest` — pure-function tests for the
    backoff staircase (4 plateaus, monotonicity, edge values). Runs
    as plain JUnit 5, no IDE fixture needed.
  - `HermesChatPanelListenerAccumulationTest` — spins up an IDE
    fixture, creates a `HermesChatPanel`, calls `renderStatus()`
    10 000 times to simulate ~22h of idle 8s timer ticks, then
    introspects the header's `EventListenerList` and asserts there
    is still exactly one `MouseListener` attached. Also dispatches
    a synthetic mouse click to verify the panel doesn't throw on
    the post-fix code path.
  Both tests would have failed loudly on the pre-fix code that
  crashed the IDE on 2026-06-29.
- **Test seams**: `HermesChatPanel.renderStatus()`,
  `HermesChatPanel.headerForTest()`, and `HermesChatPanel.backoffMs()`
  are now `internal` (was `private`) so the test module can exercise
  them directly without reflection. Production code never calls any
  of them — they're package-internal on purpose, so misuse would
  have to deliberately widen visibility to leak.

### Changed
- Toolwindow header restructured from a single `JBLabel` to a
  `JPanel(BorderLayout)` containing the status label (left/center) and
  the new restart button (right). Click-to-open-in-browser on the
  status label is preserved unchanged.
- `DashboardTokenFetcher.fetchToken` now records the last error message
  in a `lastError` field (exposed via `HermesClient.lastTokenError()`)
  and logs at WARN (was DEBUG). The DEBUG-level message was invisible
  by default and made "Model: (loading…)" failures undebuggable.

## [0.1.0] — 2026-06-28

### Added
- VSCode-Chat-style toolwindow anchored to the right sidebar.
- Embedded Hermes dashboard chat surface via JCEF, with fallback
  "Open in browser" link for IDE builds without JCEF.
- Status bar in toolwindow header showing dashboard version + reachability
  (green dot when connected, grey/red when unreachable).
- Footer toolbar with model picker and external-browser shortcut.
- Settings panel under Preferences → Tools → Hermes Chat:
  - Dashboard endpoint (default `http://127.0.0.1:9119`)
  - Session token
  - Default model
  - "Test connection" button with live dashboard probe
- Persistent state for endpoint, token, and model across IDE restarts.
- Post-startup activity that surfaces a notification when the dashboard
  is unreachable and auto-opens the toolwindow on first install.
- Toggle action `Toggle Hermes Chat` bound to Ctrl+Alt+H.
- AGENTS.md with architectural invariants for future contributors.