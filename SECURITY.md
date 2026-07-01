# Security Policy

## Supported versions

| Version | Supported          |
| ------- | ------------------ |
| latest  | ✅                 |
| older   | ❌ best-effort only |

This plugin tracks the latest Hermes Agent dashboard protocol. Old
plugin versions against new dashboards (or new plugins against old
dashboards) are not security-supported.

## Reporting a vulnerability

**Please do not file a public GitHub issue for security bugs.**

Send a private report to the maintainers (see the GitHub repo's
"Security" tab → "Report a vulnerability" — GitHub will route it
privately).

Include in your report:

1. **Description** of the vulnerability
2. **Reproduction steps** (minimal, ideally a runnable test or script)
3. **Impact** (what an attacker could do, worst case)
4. **Affected versions** (plugin version, Hermes Agent version, IDE version)
5. **Your environment** (OS, WSL if applicable, JBR version)

## What to expect

- **Acknowledgement** within 3 business days
- **Triage** within 7 days: confirm + severity assessment (Critical /
  High / Medium / Low)
- **Fix timeline**:
  - Critical: 1-2 weeks
  - High: 2-4 weeks
  - Medium: best-effort, bundled with next release
  - Low: best-effort
- A **CVE** will be requested for Critical / High issues once a fix
  is published
- You will be **credited** in the fix release notes unless you
  prefer anonymity

## Scope

In scope:

- Anything in this repo (plugin source)
- Token handling, dashboard ↔ plugin communication
- File-system / network access the plugin does on the user's behalf
- WSL bridge (Windows-side code that spawns `wsl.exe`)

Out of scope (file in the [main Hermes Agent project](https://hermes-agent.nousresearch.com) instead):

- Hermes Agent backend vulnerabilities
- LLM provider (Anthropic / OpenAI / etc.) issues
- Third-party skill sandbox escapes (skill code is user-controlled by
  design)

## Security best practices for users

- The plugin sends your dashboard session token to `127.0.0.1:9119`
  (or your configured endpoint). If you bind the dashboard to a public
  interface (`--host 0.0.0.0`), the token travels in cleartext over
  loopback only — but on a multi-user system, any local user can read
  it. Treat the token like a password.
- Use `--insecure` only on trusted networks.
- The plugin does not store credentials in the IDE's secure store
  (the IDE's `PasswordSafe` integration is a future TODO). Token
  persistence uses the standard `PersistentStateComponent` (XOR-encoded
  in `options.xml`). Treat `~/.config/JetBrains/<IDE>/options/hermes.chat.xml`
  as sensitive.
