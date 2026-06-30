package com.hermes.agent.jetbrains.client

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the `manageAutomatically` setting added in v0.2.0 to
 * gate [com.hermes.agent.jetbrains.dashboard.DashboardProcessManager]
 * behaviour.
 *
 * Scope of these tests:
 * - [HermesClient.State.manageAutomatically] defaults to true (preserves
 *   the v0.1.x "auto-manage" behaviour for existing users on upgrade).
 * - [HermesClient.updateSettings] with `manageAutomatically=false`
 *   flips the field; the three other fields stay untouched.
 * - Calling `updateSettings` with all-null params is a true no-op
 *   (don't accidentally clobber the user's saved toggle).
 *
 * What this test does NOT cover:
 * - The UI side ([HermesChatConfigurable]). That has IntelliJ panel
 *   construction dependencies and is covered by the existing
 *   `HermesChatPanel*Test` family once a panel test for the
 *   checkbox lands.
 * - The [DashboardProcessManager] side. That's covered by
 *   `DashboardProcessStrategyFactoryTest` and the `MacLinuxStrategy*`
 *   tests — the manager is just a facade.
 *
 * Why we can construct `HermesClient` directly without a fixture:
 * the `tokenFetcher` and `restClient` are `by lazy` — they're only
 * constructed when first accessed. The unit tests here only exercise
 * `getState()` / `updateSettings()`, neither of which touches the
 * network or token machinery.
 */
class HermesClientManageAutomaticallyTest {

    @Test
    fun `State default has manageAutomatically=true`() {
        // The default matters: a fresh `State()` is what `loadState(null)`
        // produces and what `loadState` produces for users who upgraded
        // from v0.1.x (no XML file yet). Default must be true so existing
        // users keep their auto-manage behaviour.
        val state = HermesClient.State()
        assertTrue(
            state.manageAutomatically,
            "HermesClient.State().manageAutomatically must default to true " +
                "to preserve v0.1.x auto-manage behaviour on upgrade",
        )
    }

    @Test
    fun `fresh HermesClient starts with manageAutomatically=true`() {
        // Pin the contract via the constructor too, not just the State
        // data class default. Catches a regression where someone wires
        // a different State into the constructor.
        val client = HermesClient()
        assertTrue(client.getState().manageAutomatically)
    }

    @Test
    fun `updateSettings with manageAutomatically=false flips the field`() {
        val client = HermesClient()
        assertTrue(client.getState().manageAutomatically, "sanity: starts true")

        client.updateSettings(manageAutomatically = false)
        assertFalse(
            client.getState().manageAutomatically,
            "updateSettings(manageAutomatically=false) must persist",
        )
    }

    @Test
    fun `updateSettings with manageAutomatically=true restores the field`() {
        // Round-trip: start true → flip false → flip true. Pin that the
        // restore path works (e.g. user un-checks the box in settings,
        // hits Apply, then re-checks it and hits Apply again — both
        // transitions must round-trip).
        val client = HermesClient()
        client.updateSettings(manageAutomatically = false)
        assertFalse(client.getState().manageAutomatically)

        client.updateSettings(manageAutomatically = true)
        assertTrue(client.getState().manageAutomatically)
    }

    @Test
    fun `updateSettings does not clobber unrelated fields when only manageAutomatically is set`() {
        // AGENTS.md "Adding a new settings field" §4 says callers can
        // update individual fields by passing only the ones they care
        // about. Pin that contract for the new field: setting just
        // `manageAutomatically` must leave endpoint/token/model alone.
        val client = HermesClient()
        val before = client.getState()
        val originalEndpoint = before.endpoint
        val originalToken = before.sessionToken
        val originalModel = before.defaultModelId

        client.updateSettings(manageAutomatically = false)

        val after = client.getState()
        assertEquals(originalEndpoint, after.endpoint, "endpoint must not change")
        assertEquals(originalToken, after.sessionToken, "token must not change")
        assertEquals(originalModel, after.defaultModelId, "defaultModelId must not change")
        assertFalse(after.manageAutomatically)
    }

    @Test
    fun `updateSettings with all null params is a no-op`() {
        // Important: the four-arg overload has all default-null. The
        // UI calls it from `HermesChatConfigurable.reset()` indirectly
        // via the rollback path in `runTestConnection` — that path
        // must NOT clobber the user's saved `manageAutomatically`
        // setting just because the temp test set the endpoint.
        val client = HermesClient()
        client.updateSettings(manageAutomatically = false) // user opt-out
        assertFalse(client.getState().manageAutomatically)

        // No-op: pass nothing. manageAutomatically must stay false.
        client.updateSettings()
        assertFalse(
            client.getState().manageAutomatically,
            "updateSettings() with no args must not flip manageAutomatically back to true",
        )
        client.updateSettings(endpoint = null, token = null, model = null, manageAutomatically = null)
        assertFalse(
            client.getState().manageAutomatically,
            "updateSettings with all-null args must not flip manageAutomatically back to true",
        )
    }

    @Test
    fun `loadState with persisted false value is honored`() {
        // Simulate the IDE restart path: XML has manageAutomatically=false
        // from the user's last save. loadState() must restore it
        // (rather than silently resetting to default true).
        val client = HermesClient()
        val persistedState = HermesClient.State(
            endpoint = "http://my-custom:9119",
            sessionToken = "user-pinned-token",
            defaultModelId = "claude-opus-4.8",
            manageAutomatically = false,
        )
        client.loadState(persistedState)
        val loaded = client.getState()
        assertFalse(
            loaded.manageAutomatically,
            "loadState(persistedState) must preserve manageAutomatically=false across IDE restarts",
        )
        // Pin that the other fields round-trip too — guards against a
        // regression where someone refactors State and breaks the
        // loadState() copy.
        assertEquals("http://my-custom:9119", loaded.endpoint)
        assertEquals("user-pinned-token", loaded.sessionToken)
        assertEquals("claude-opus-4.8", loaded.defaultModelId)
    }
}