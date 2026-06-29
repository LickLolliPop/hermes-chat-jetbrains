# Changelog

All notable changes to Hermes Chat for JetBrains IDEs.

## [Unreleased]

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