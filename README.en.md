# Hermes Chat — JetBrains Plugin

[中文](README.md) | English

VSCode-Chat-style sidebar that talks to a locally-running
[Hermes Agent](https://hermes-agent.nousresearch.com) dashboard. Works in
**Android Studio, IntelliJ IDEA, and every other IntelliJ Platform IDE**.

```
┌─────────────────────────────┐
│ ● Hermes v0.6.3 — connected │  ← status bar
├─────────────────────────────┤
│                             │
│  [JCEF: dashboard /chat]    │  ← embedded chat surface
│                             │
├─────────────────────────────┤
│ Model: [claude-opus-4 ▼]    │  ← footer toolbar
└─────────────────────────────┘
```

## Why this exists

Android Studio bundles its own AI assistant, but it's a thin LLM wrapper
without the things developers actually want:

| Capability | Android Studio AI | Hermes Agent |
|---|---|---|
| Cross-session memory | ❌ per-project only | ✅ persistent, searchable |
| Skills / custom tools | ❌ | ✅ `~/.hermes/skills/` |
| Autonomous subagents | ❌ | ✅ `delegate_task` |
| Scheduled cron jobs | ❌ | ✅ `hermes cron` |
| Multiple LLM providers | ⚠️ limited | ✅ any model, any provider |
| Telegram/Discord bridge | ❌ | ✅ |
| Skills from community hub | ❌ | ✅ `hermes skills install` |

This plugin is the IDE shell — all the intelligence stays in Hermes.

## Architecture

```
┌─────────────────────────────────────────────┐
│  Android Studio / IntelliJ IDEA             │
│  ┌───────────────────────────────────────┐  │
│  │  Hermes Chat toolwindow               │  │
│  │  ┌─────────────────────────────────┐  │  │
│  │  │  JCEF browser hosting           │  │  │
│  │  │  http://127.0.0.1:9119/chat     │  │  │
│  │  └─────────────────────────────────┘  │  │
│  │  ▲  REST (Bearer *** * HermesClient.kt │  │  │
│  └────│───────────────────────────────────┘  │
└──────│────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────┐
│  Hermes Dashboard (FastAPI, port 9119)      │
│  - SPA: React + xterm.js chat surface       │
│  - REST: /api/status, /api/sessions, ...    │
│  - WS: /api/pty, /api/ws, /api/pub          │
└─────────────────────────────────────────────┘
       │
       ▼
┌─────────────────────────────────────────────┐
│  Hermes CLI / Agent                         │
│  - skills, memory, cron, run_agent          │
└─────────────────────────────────────────────┘
```

The plugin never speaks to an LLM directly. Every message you send goes
through Hermes' `run_agent` path, which means:

- Skills load automatically based on your query
- Memory persists across IDE restarts
- Subagents can be spawned mid-conversation
- Cron jobs, approvals, and skill usage all work the same as the CLI

> **Project ownership**: this repository is maintained by `LickLolliPop`
> as an independent community project — **not** an official plugin by
> Nous Research. Backend capabilities, bug reports, and feature requests
> should go to the [main hermes-agent project](https://hermes-agent.nousresearch.com).
> This repository is responsible only for the IDE-side tool window and
> configuration integration.

## Module structure

```
clients/jetbrains/
├── build.gradle.kts              # IntelliJ Platform Gradle 2.x
├── settings.gradle.kts
├── gradle.properties
├── src/main/
│   ├── kotlin/com/hermes/agent/jetbrains/
│   │   ├── HermesChatStartupActivity.kt    # post-open dashboard probe
│   │   ├── client/
│   │   │   ├── HermesClient.kt             # PersistentStateComponent + facade
│   │   │   └── HermesRestClient.kt         # JDK HttpClient, manual JSON
│   │   ├── model/
│   │   │   └── HermesStatus.kt             # HermesStatus, ModelOption, Session
│   │   ├── settings/
│   │   │   └── HermesChatConfigurable.kt   # Preferences UI
│   │   └── ui/
│   │       ├── HermesChatToolWindowFactory.kt   # VSCode Chat layout
│   │       ├── HermesIcons.kt
│   │       └── ToggleHermesChatAction.kt
│   └── resources/
│       ├── META-INF/plugin.xml
│       ├── icons/hermesChat{,_16}.svg
│       └── messages/HermesChatBundle.properties
└── README.md
```

## Build & install

```bash
cd clients/jetbrains
./gradlew buildPlugin         # produces release/hermes-chat-0.1.0.zip
```

Install in any JetBrains IDE:
1. Settings → Plugins → ⚙ → Install Plugin from Disk…
2. Select the ZIP from `release/`
3. Restart the IDE

Or for live development:

```bash
./gradlew runIde               # launches a sandbox IntelliJ with the plugin
```

The default `runIde` target uses IntelliJ IDEA Community. To test against
Android Studio, pass `-PandroidStudioPath=/path/to/android-studio`.

## Configuration

1. Start the Hermes dashboard in a terminal: `hermes dashboard`
2. Copy the session token from the dashboard's startup log
3. In the IDE: Settings → Tools → Hermes Chat
   - Endpoint: `http://127.0.0.1:9119` (default)
   - Session token: paste from dashboard log
   - Default model: pick from dropdown
4. Click **Test connection** to verify

The token is shared between the dashboard process and the plugin. Both
sides must agree on it. If you start the dashboard with an explicit
token (`HERMES_DASHBOARD_SESSION_TOKEN=<token> hermes dashboard`), paste
that same value into the plugin settings.

## ⚠️ WSL 2 users — read this before configuring

WSL 2 runs in its own Hyper-V VM, so the `127.0.0.1` inside WSL is
**not** the `127.0.0.1` that Windows / Android Studio sees. If you start
`hermes dashboard` in WSL and try to connect from a Windows-side IDE
without doing one of the two things below, the endpoint will silently
fail with "unreachable".

Pick **one** of the following. Both are stable; the difference is which
side does the bridging.

### Option A — Tell Hermes to listen on `0.0.0.0`

Bind the dashboard to all interfaces inside WSL, then point the plugin
at WSL's eth0 IP:

```bash
# Inside WSL
hermes dashboard --host 0.0.0.0 --port 9119 --insecure
```

```bash
# Inside WSL — grab the IP you must use from Windows
hostname -I | awk '{print $1}'
# → e.g. 172.24.32.105
```

In Android Studio → Settings → Tools → Hermes Chat:
- **Endpoint**: `http://172.24.32.105:9119` (use the IP from above)
- **Token**: the one the dashboard printed at startup

⚠️ WSL's eth0 IP can change on every WSL restart. If your IDE suddenly
stops connecting, re-run `hostname -I` and update the endpoint. To pin
the IP, add this to `/etc/wsl.conf` inside WSL (then `wsl --shutdown`
from PowerShell):

```ini
[network]
generateHosts = true
generateResolvConf = true
```

…and create a Windows Task Scheduler entry that runs on logon to (1)
start Hermes with `--host 0.0.0.0` and (2) write its current IP into a
file the plugin can read. The plugin's endpoint field is a plain
string, so a tiny wrapper that reads the file works fine — but most
users don't need this; just re-check the IP when it breaks.

### Option B — Windows portproxy to WSL (no Hermes flag change)

Forward Windows `127.0.0.1:9119` into the WSL VM. The plugin keeps
using the default `http://127.0.0.1:9119` endpoint.

Run **once** in an **elevated PowerShell**:

```powershell
$wslIp = (wsl hostname -I).Trim().Split()[0]
netsh interface portproxy add v4tov4 `
    listenaddress=127.0.0.1 listenport=9119 `
    connectaddress=$wslIp connectport=9119

# Allow the port through the Windows firewall
New-NetFirewallRule -DisplayName "Hermes Dashboard (WSL)" `
    -Direction Inbound -LocalPort 9119 -Protocol TCP -Action Allow
```

Then start Hermes in WSL **without** any special flags:

```bash
hermes dashboard
```

In Android Studio → Settings → Tools → Hermes Chat:
- **Endpoint**: `http://127.0.0.1:9119` (default — Windows-side loopback)
- **Token**: as printed

⚠️ WSL IP can change on restart, which silently breaks the proxy. To
fix after a restart, just re-run the PowerShell snippet above. Save it
as `~\setup-hermes-portproxy.ps1` and run it whenever Hermes won't
connect.

### Which option should I pick?

|  | Option A (`0.0.0.0`) | Option B (portproxy) |
|---|---|---|
| Plugin endpoint | WSL IP (changes) | `127.0.0.1` (stable) |
| Hermes flags | `--host 0.0.0.0 --insecure` | none |
| Admin PowerShell needed | ❌ no | ✅ yes (one-time) |
| Survives WSL restart | ⚠️ IP may change | ⚠️ proxy rule may need rerun |
| Works on Win10 21H2 | ✅ | ✅ |
| Works on Win11 22H2+ | ✅ + `wsl.localhost` shortcut | ✅ |

**Most users: pick Option B.** The "endpoint never changes" property is
worth the one-time PowerShell setup, and `wsl.localhost` (Win11 22H2+)
gives you an alternative if the IP changes.

If you also want the dashboard accessible from your phone on the same
LAN, Option A is more flexible — but that's a v0.2.0 concern, not
M1.

## Roadmap

| Milestone | Status | Description |
|---|---|---|
| **M1** — VSCode Chat sidebar | ✅ shipped | ToolWindow + JCEF + status bar + model picker |
| **M2** — Code context | planned | Right-click → send selection/file as context |
| **M3** — Multi-session | planned | Recent conversations dropdown in header |
| **M4** — Multimodal | planned | Screenshot paste, image attach |
| **M5** — Approval UI | planned | Render permission requests from Hermes inside the IDE |

## Compatibility

- IntelliJ IDEA 2024.2 → 2025.2
- Android Studio Koala (2024.1.1) → Meerkat (2025.x)
- JetBrains Runtime 21+
- Hermes Agent 0.6.0+ (the dashboard SPA is the source of truth for chat)

## License

Apache 2.0 (matches the parent hermes-agent repo).