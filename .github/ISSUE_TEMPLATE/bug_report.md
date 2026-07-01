---
name: Bug report
about: 报告一个让插件无法正常工作的 bug
title: "[Bug]: "
labels: bug
assignees: ''
---

## Bug 描述

<!-- 一句话讲清楚出了什么问题 -->

## 复现步骤

1.
2.
3.

## 预期行为

<!-- 你觉得应该发生什么 -->

## 实际行为

<!-- 实际发生了什么 -->

## 环境信息

- **IDE**: (例如 Android Studio Koala 2024.1.1 / IntelliJ IDEA 2024.3)
- **IDE 版本号**: (Help → About 里复制完整版本号)
- **Hermes Chat 插件版本**: (Settings → Plugins → Hermes Chat → 版本号)
- **Hermes Agent 后端版本**: (dashboard 启动横幅里看)
- **操作系统**: (Win11 23H2 / macOS 14.5 / Ubuntu 24.04)
- **WSL**: (用 WSL 吗? WSL 版本? `wsl --status` 粘贴一下)
- **JBR / JDK**: (Help → About → JBR 版本)

## 关键日志

**强烈建议附上以下两条之一** — 没日志我们大概率排查不了:

```bash
# IDE 日志: Help → Diagnostic Tools → Debug Log Settings
# 加一行: #com.hermes.agent.jetbrains
# 然后 Help → Show Log in Explorer, 把 idea.log 末尾 200 行贴上来

# 或者 dashboard 端日志
journalctl -u hermes-dashboard -n 200 --no-pager    # 如果用 systemd
# 或 tail -200 hermes-dashboard.log                  # 如果手动启
```

## 截图 / 录屏

<!-- 如果是 UI 类 bug, 截图比文字直观一百倍 -->

## 可能的相关配置

<!-- 你是怎么启 dashboard 的? 有没有改 host/port/token? -->
<!-- 你 Settings → Tools → Hermes Chat 里 endpoint 填的什么? -->

## Checklist

- [ ] 我已搜索过 [issues](https://github.com/PLACEHOLDER_OWNER/PLACEHOLDER_REPO/issues) 确认没人报过
- [ ] 我用的是插件最新版本
- [ ] 我附上了日志或截图
