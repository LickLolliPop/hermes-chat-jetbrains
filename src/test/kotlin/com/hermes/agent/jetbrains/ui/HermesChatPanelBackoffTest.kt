package com.hermes.agent.jetbrains.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HermesChatPanel.backoffMs() 轮询退避算法测试")
class HermesChatPanelBackoffTest {

    private val baseInterval = 8_000

    @Test
    fun `failures 0 and 1 stay at the base interval`() {
        assertEquals(baseInterval, HermesChatPanel.backoffMs(0))
        assertEquals(baseInterval, HermesChatPanel.backoffMs(1))
    }

    @Test
    fun `failures 2 through 5 use 30s`() {
        for (f in 2..5) {
            assertEquals(30_000, HermesChatPanel.backoffMs(f), "failures=$f")
        }
    }

    @Test
    fun `failures 6 through 20 use 60s`() {
        for (f in 6..20) {
            assertEquals(60_000, HermesChatPanel.backoffMs(f), "failures=$f")
        }
    }

    @Test
    fun `failures above 20 saturate at 300s`() {
        assertEquals(300_000, HermesChatPanel.backoffMs(21))
        assertEquals(300_000, HermesChatPanel.backoffMs(1000))
    }

    @Test
    fun `backoff is monotonic`() {
        var previous = 0
        for (f in 0..50) {
            val current = HermesChatPanel.backoffMs(f)
            assert(current >= previous)
            previous = current
        }
    }
}
