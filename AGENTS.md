# AGENTS.md — Architecture notes for AI coding assistants

If you're an AI agent (or a human) reading this to extend the plugin,
read this first. It captures non-obvious decisions and the rules we don't
want re-litigated.

## Core invariants

### 1. The plugin is a thin shell
**Never** add LLM calls, prompt construction, or token counting to this
plugin. Every message must round-trip through the Hermes dashboard's
`/chat` SPA via JCEF. The whole point is to avoid reimplementing what
Hermes already does.

### 2. The dashboard is the source of truth
- Sessions, message history, skill activations — all owned by Hermes.
- The plugin reads summaries only for the "Recent conversations" header.
- For anything richer, point users at the embedded SPA, don't try to mirror it.

### 3. Loopback auth only (v0.1.0)
The plugin speaks to Hermes via `http://127.0.0.1:9119` with a Bearer
token. Gated-mode OAuth (the dashboard's external-bind mode) is **out of
scope** until users ask for it — adding OAuth support means implementing
a browser-driven login flow inside the IDE, which is months of work.

### 4. No bundled dependencies for what the JDK already provides
Use `java.net.http.HttpClient` for REST. OkHttp is reserved for future
WebSocket work because JDK HttpClient's WebSocket API is preview-quality.
Do NOT add kotlinx-serialization for the few fields we parse — manual
regex extraction is more than adequate and avoids dep churn.

### 5. The EDT is sacred
Every public method on `HermesClient` that touches the network returns
through `invokeLater` on the EDT. UI code never blocks waiting for a
response. Settings code (`HermesChatConfigurable`) is the exception —
it's run on user-initiated background threads explicitly.

## Architectural choices, defended

### Why JCEF, not pure Swing
Hermes' chat surface (markdown streaming, tool-call visualisation, ANSI
rendering, code-block highlighting) is implemented in React + xterm.js.
Reimplementing in Swing would take 2-3 months and would always lag the
dashboard. JCEF lets us ship M1 in days, not months.

The fallback path (a static "Open in browser" link) covers Android Studio
builds that disable JCEF, which is rare but documented.

### Why PersistentStateComponent
The plugin's state is three strings (endpoint, token, model). It needs
to survive IDE restarts but is not editor-specific. Application-level
`PersistentStateComponent` is the smallest dep-free solution.

If we add per-project overrides later, swap to a project-level service
with a `PersistentStateComponent<State>` plus a project-level facade.

### Why a manual JSON parser
The dashboard returns 4 fields we care about. Adding a JSON dep (kotlinx-
serialization, Jackson, Gson) for 4 fields is a bad trade:

- Adds 200-500KB to the plugin zip
- Locks us to that library's annotation/Kotlin-coroutine story
- We don't need streaming or schema validation

The manual parser is regex-based and intentionally tolerant of schema
additions (unknown fields ignored). If the schema changes in a
backwards-incompatible way, we upgrade.

### Why no WebSocket in M1
The dashboard has three WS endpoints:
- `/api/pty` — PTY byte stream for xterm.js terminal (not useful here)
- `/api/ws` — JSON-RPC sidecar for the TUI
- `/api/pub` — event broadcast

For M1 we render chat via JCEF, which gets all of those for free because
it IS the dashboard SPA. Adding a parallel Kotlin-side WS client would
mean duplicating rendering, message storage, etc. — pointless.

When M5 (approval UI) lands, we'll need to subscribe to `/api/pub` to
get push notifications for permission requests. That's when OkHttp
WebSocket gets added.

## File-by-file reasoning

### `HermesChatToolWindowFactory.kt`
- Hosts the JCEF browser at `endpoint/chat`
- Status bar polls `/api/status` every 8s (cheap HEAD-style probe)
- Falls back to "Open in browser" link if JCEF fails to init
- Status bar click → open dashboard in external browser

### `HermesClient.kt`
- Singleton service, persists endpoint/token/model
- Async variants (`fetchStatusAsync`) marshal back to EDT
- Sync variants (`isReachable`, `testConnection`) for use from background
  threads (settings panel, startup activity)

### `HermesRestClient.kt`
- Pure I/O, no Swing imports, no IntelliJ imports
- Could be unit-tested in isolation if we ever add tests
- Manual JSON parser is regex-based — see invariant #4

### `HermesChatConfigurable.kt`
- Three fields + Test button + status label
- "Test" probes `/api/status` and refreshes the model dropdown
- Validates endpoint URL on Apply (must start with http:// or https://)
- Does NOT validate token format — Hermes may rotate token formats

### `HermesChatStartupActivity.kt`
- One-shot "Hermes dashboard unreachable" notification on first project
- Auto-opens toolwindow on first IDE install (controlled by system
  property; survives until JVM restart — acceptable for one-time UX nudge)

## Build gotchas

- **JDK 21 required** — IntelliJ Platform 2.x baseline
- **Internet access during build** — first build downloads ~300MB of
  IntelliJ Community + Kotlin stdlib + OkHttp + transitive deps
- **`runIde` uses IDEA Community by default** — for Android Studio
  testing pass `-PandroidStudioPath=/path/to/android-studio` to override

## What NOT to do

- ❌ Don't fork the Hermes web SPA to add IDE-specific UI
- ❌ Don't add kotlinx-serialization "for cleanliness" — it isn't free
- ❌ Don't bypass Hermes to call an LLM directly — defeats the purpose
- ❌ Don't persist chat history in the IDE — Hermes owns it
- ❌ Don't add OAuth/WebAuthn in v0.1.x — out of scope
- ❌ Don't use Swing's WebBrowser (JavaFX WebView) — incompatible with
  IntelliJ Platform's threading model

## How to extend

### Adding a new endpoint
1. Add a typed model in `model/`
2. Add a sync method on `HermesRestClient` that returns the typed model
3. Add an async wrapper on `HermesClient` that marshals back to EDT
4. If UI is needed, add a component to `HermesChatPanel` and wire it up
   in the init block

### Adding a new settings field
1. Add to `HermesClient.State` (data class — auto-persists)
2. Add a field + getter in `HermesChatConfigurable`
3. Add to `isModified()` and `apply()`
4. Call `HermesClient.getInstance().updateSettings(...)` from anywhere
   that needs to write the value programmatically

### Touching Hermes' protocol
If you're modifying both this plugin and the dashboard SPA together, the
authoritative protocol is `/mnt/d/AI/hermes/hermes-agent/hermes_cli/web_server.py`.
Don't duplicate the schema definition here — link to the source.

## Testing

v0.1.0 has no automated tests. The validation strategy is:
- Build succeeds → types are sound
- Manual `runIde` → UI lays out correctly
- Manual dashboard round-trip → REST auth works
- CI in a future milestone will add gradle test task with mocked HttpClient

Adding tests now would slow down the M1 validation cycle. Prioritise
getting a working build into users' hands, then test.