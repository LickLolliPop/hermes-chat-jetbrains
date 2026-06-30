package com.hermes.agent.jetbrains.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Test
import java.awt.event.MouseListener

class HermesChatPanelListenerAccumulationTest : BasePlatformTestCase() {

    @Test
    fun testRenderStatusDoesNotAccumulateListeners() {
        // 使用 BasePlatformTestCase 提供的 project
        val panel = HermesChatPanel(project)

        // 初始状态：应该只有 1 个从 init 块挂载的持久监听器
        val baseline = getMouseListenerCount(panel)
        assertEquals("Should have exactly 1 mouse listener from init", 1, baseline)

        // 模拟运行 100 次状态渲染
        repeat(100) {
            panel.renderStatus(null)
        }

        // 验证：监听器数量必须依然为 1，不能增加
        val after = getMouseListenerCount(panel)
        assertEquals("Listener count must not increase after multiple renderStatus calls", 1, after)
    }

    private fun getMouseListenerCount(panel: HermesChatPanel): Int {
        val header = panel.headerForTest()
        // 使用 Swing 标准公共 API 获取监听器，不再依赖反射
        return header.getListeners(MouseListener::class.java).size
    }
}
