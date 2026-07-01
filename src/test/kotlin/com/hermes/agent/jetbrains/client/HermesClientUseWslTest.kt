package com.hermes.agent.jetbrains.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the `useWsl` setting added in v0.2.0 to control whether
 * the Windows plugin routes the dashboard process through WSL or runs
 * it natively.
 *
 * Scope of these tests (mirrors [HermesClientManageAutomaticallyTest]
 * for the earlier `manageAutomatically` field):
 * - [HermesClient.State.useWsl] defaults to true (preserves v0.1.x
 *   "always WSL on Windows" behaviour for existing users on upgrade).
 * - [HermesClient.updateSettings] with `useWsl=false` flips the field;
 *   the other fields stay untouched.
 * - Calling `updateSettings` with all-null params is a true no-op
 *   (don't accidentally clobber the user's saved toggle).
 *
 * What this test does NOT cover:
 * - The UI side ([HermesChatConfigurable]). The checkbox is hidden
 *   when WSL is not installed — that's the [WslDetector] / Settings
 *   panel's responsibility, and tested implicitly by
 *   [com.hermes.agent.jetbrains.dashboard.WslDetectorTest].
 * - The [DashboardProcessStrategy] side. Pinned by
 *   [com.hermes.agent.jetbrains.dashboard.DashboardProcessStrategyFactoryTest]'s
 *   `pickWithUseWsl` cases — the State field just feeds into the
 *   picker's `useWsl` argument.
 */
class HermesClientUseWslTest {

    @Test
    fun `State default has useWsl=true`() {
        // The default matters: a fresh `State()` is what `loadState(null)`
        // produces and what `loadState` produces for users who upgraded
        // from v0.1.x (no XML file yet). Default must be true so existing
        // Windows users keep their WSL behaviour.
        val state = HermesClient.State()
        assertTrue(
            state.useWsl,
            "HermesClient.State().useWsl must default to true " +
                "to preserve v0.1.x 'use WSL on Windows' behaviour on upgrade",
        )
    }

    @Test
    fun `fresh HermesClient starts with useWsl=true`() {
        // Pin the contract via the constructor too, not just the State
        // data class default. Catches a regression where someone wires
        // a different State into the constructor.
        val client = HermesClient()
        assertTrue(client.getState().useWsl)
    }

    @Test
    fun `updateSettings with useWsl=false flips the field`() {
        val client = HermesClient()
        assertTrue(client.getState().useWsl, "sanity: starts true")

        client.updateSettings(useWsl = false)
        assertFalse(
            client.getState().useWsl,
            "updateSettings(useWsl=false) must persist",
        )
    }

    @Test
    fun `updateSettings with useWsl=true restores the field`() {
        // Round-trip: start true → flip false → flip true. Pin that the
        // restore path works (e.g. user un-checks the box in settings,
        // hits Apply, then re-checks it and hits Apply again — both
        // transitions must round-trip).
        val client = HermesClient()
        client.updateSettings(useWsl = false)
        assertFalse(client.getState().useWsl)

        client.updateSettings(useWsl = true)
        assertTrue(client.getState().useWsl)
    }

    @Test
    fun `updateSettings does not clobber unrelated fields when only useWsl is set`() {
        // AGENTS.md "Adding a new settings field" §4 says callers can
        // update individual fields by passing only the ones they care
        // about. Pin that contract for the new field: setting just
        // `useWsl` must leave endpoint/token/model/manageAutomatically
        // alone.
        val client = HermesClient()
        val before = client.getState()
        val originalEndpoint = before.endpoint
        val originalToken = before.sessionToken
        val originalModel = before.defaultModelId
        val originalManageAuto = before.manageAutomatically

        client.updateSettings(useWsl = false)

        val after = client.getState()
        assertEquals(originalEndpoint, after.endpoint, "endpoint must not change")
        assertEquals(originalToken, after.sessionToken, "token must not change")
        assertEquals(originalModel, after.defaultModelId, "defaultModelId must not change")
        assertEquals(originalManageAuto, after.manageAutomatically, "manageAutomatically must not change")
        assertFalse(after.useWsl)
    }

    @Test
    fun `updateSettings with all null params is a no-op`() {
        // The five-arg overload has all default-null. The UI calls it
        // from `HermesChatConfigurable.reset()` indirectly via the
        // rollback path in `runTestConnection` — that path must NOT
        // clobber the user's saved `useWsl` setting just because the
        // temp test set the endpoint.
        val client = HermesClient()
        client.updateSettings(useWsl = false) // user opt-out
        assertFalse(client.getState().useWsl)

        // No-op: pass nothing. useWsl must stay false.
        client.updateSettings()
        assertFalse(
            client.getState().useWsl,
            "updateSettings() with no args must not flip useWsl back to true",
        )
        client.updateSettings(
            endpoint = null, token = null, model = null,
            manageAutomatically = null, useWsl = null,
        )
        assertFalse(
            client.getState().useWsl,
            "updateSettings with all-null args must not flip useWsl back to true",
        )
    }

    @Test
    fun `loadState with persisted false value is honored`() {
        // Simulate the IDE restart path: XML has useWsl=false from
        // the user's last save. loadState() must restore it (rather
        // than silently resetting to default true).
        val client = HermesClient()
        val persistedState = HermesClient.State(
            endpoint = "http://my-custom:9119",
            sessionToken = "user-pinned-token",
            defaultModelId = "claude-opus-4.8",
            useWsl = false,
            manageAutomatically = false,
        )
        client.loadState(persistedState)
        val loaded = client.getState()
        assertFalse(
            loaded.useWsl,
            "loadState(persistedState) must preserve useWsl=false across IDE restarts",
        )
        // Pin that the other fields round-trip too — guards against a
        // regression where someone refactors State and breaks the
        // loadState() copy.
        assertEquals("http://my-custom:9119", loaded.endpoint)
        assertEquals("user-pinned-token", loaded.sessionToken)
        assertEquals("claude-opus-4.8", loaded.defaultModelId)
        assertFalse(loaded.manageAutomatically)
    }
}
