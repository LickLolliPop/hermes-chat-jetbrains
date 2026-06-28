# Changelog

All notable changes to Hermes Chat for JetBrains IDEs.

## [Unreleased]

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