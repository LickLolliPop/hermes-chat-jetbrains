# Contributing to Hermes Chat (JetBrains Plugin)

[中文版本 →](CONTRIBUTING.md)

Thanks for contributing! This document explains how to file issues, send
code changes, and submit pull requests.

## 0. Scope

This repo is **only** the JetBrains IDE plugin source. Hermes Agent's
core capabilities (LLM chat, skills, memory, cron, subagents) live in
the main [hermes-agent](https://hermes-agent.nousresearch.com) project.
If your need is a backend capability, file the issue / PR there —
**not** here.

## 1. Filing bugs

Use the [Bug report template](.github/ISSUE_TEMPLATE/bug_report.md).
**Critical**: attach logs (the last 200 lines of `idea.log` plus the
dashboard startup log). Without logs we usually can't reproduce.

## 2. Requesting features

Use the [Feature request template](.github/ISSUE_TEMPLATE/feature_request.md).
Lead with "why" before "how". Plugin scope ≠ backend capability.

## 3. Sending code changes — PR flow

### 3.1 Setup

1. **Fork** this repo
2. **Clone** your fork
3. Create a branch: `git checkout -b feat/your-feature` or `fix/issue-123`
4. **Do not** commit directly to main

### 3.2 Development

```bash
# Open in IDEA / Android Studio Panda4
# Must be JDK 21 (Panda4 bundles one, or configure in gradle.properties)
./gradlew test --offline          # run existing tests, confirm environment works
./gradlew runIde                  # sandbox IntelliJ with live plugin
./gradlew buildPlugin --offline   # produce distributable ZIP
```

### 3.3 Style & architecture

- **Read `AGENTS.md`**: this file lists the project's architectural
  invariants (PersistentStateComponent boundaries, EDT threading rules,
  JCEF dispose patterns, cross-OS dispatch). **PRs that violate AGENTS.md
  will be rejected.**
- **Read the skill references under `references/`**: each common pitfall
  (listener leak, WSL token, cross-OS strategy) has its own section.
- **Don't introduce new dependencies** unless truly necessary. New
  dependencies must be Apache-2.0 license compatible.
- **Commit messages**: follow [Conventional Commits](https://www.conventionalcommits.org/),
  format `feat(scope): summary` / `fix(scope): summary` / `chore(scope): summary`.

### 3.4 Tests

**PRs without tests will be rejected.** The code you change must have
corresponding test coverage:

- Pure functions / strategy selection / edge values → JUnit 5 unit tests
  (no IDE fixture)
- UI behavior / Disposable tree / IDE integration → `BasePlatformTestCase`
  (with IDE fixture)
- Cross-OS behavior → injectable probes (don't mock system calls like
  `wsl.exe` directly)

Reference the existing tests under
`src/test/kotlin/com/hermes/agent/jetbrains/dashboard/`.

### 3.5 CHANGELOG

Update the `[Unreleased]` section in `CHANGELOG.md` with a clear
description of your change. Format follows existing sections. This is
the source for release "what's new" notes.

### 3.6 Opening the PR

1. Push to your fork: `git push -u origin feat/your-feature`
2. Open the PR on GitHub; the template will load automatically
3. Link the issue: write `Closes #123` (auto-closes the issue on merge)
4. Wait for CI to pass + reviewer approval

### 3.7 Commit splitting (recommended)

Per project convention, **split production code + tests into 2 commits**:

- Commit 1: production code + `AGENTS.md` / skill reference updates
- Commit 2: test code

This way `git revert <commit-1>` cleanly reverts the feature without
muddling test history.

## 4. Review process

- Maintainers will respond within 7 days when possible
- Review focus: conformance to `AGENTS.md`, test coverage, commit
  message style
- CI must pass (`.github/workflows/ci.yml` runs `./gradlew test` +
  `buildPlugin` + `verifyPlugin`)

## 5. Code of conduct

The project community norms are still being established. For now:
**be professional, be on-topic, no personal attacks.** Major issues
will follow the conventional Apache-2.0 community practices.

## 6. License

By submitting a PR you agree to license your contribution under
Apache-2.0, matching the main project's license.
