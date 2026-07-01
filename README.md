# Hermes Chat — JetBrains 插件

**[English](README.en.md)** | 中文

VSCode-Chat 风格的侧边栏, 跟本地运行的
[Hermes Agent](https://hermes-agent.nousresearch.com) dashboard 对话. 适用于
**Android Studio, IntelliJ IDEA, 以及所有基于 IntelliJ Platform 的 IDE**.

```
┌─────────────────────────────┐
│ ● Hermes v0.6.3 — connected │  ← 状态栏
├─────────────────────────────┤
│                             │
│  [JCEF: dashboard /chat]    │  ← 内嵌聊天面板
│                             │
├─────────────────────────────┤
│ Model: [claude-opus-4 ▼]    │  ← 底部工具栏
└─────────────────────────────┘
```

> 完整英文文档: [README.en.md](README.en.md) (WSL 配置、build / install 详细步骤、roadmap)

## 这是什么?

Hermes Chat 是**薄薄一层 IDE 外壳** — 所有智能 (LLM 对话、skills、memory、cron、subagent)
都在 [Hermes Agent 主项目](https://hermes-agent.nousresearch.com) 里. 插件做的事只有一件:
把 dashboard 的聊天界面嵌进 IDE 右栏, 让你在 IDE 里直接跟 Hermes 对话, 不必切终端.

## 跟 IDE 自带 AI 比起来?

| 能力 | Android Studio AI | Hermes Agent |
|---|---|---|
| 跨 session 记忆 | ❌ 每个项目独立 | ✅ 持久化, 可搜索 |
| 自定义 skills | ❌ | ✅ `~/.hermes/skills/` |
| 自主 subagent | ❌ | ✅ `delegate_task` |
| 定时任务 (cron) | ❌ | ✅ `hermes cron` |
| 多 LLM provider | ⚠️ 有限 | ✅ 任何模型, 任何 provider |
| Telegram/Discord bridge | ❌ | ✅ |
| 社区 skill hub | ❌ | ✅ `hermes skills install` |

## 安装

### 方式 1: 从源码 build (推荐, 最新)

```bash
cd clients/jetbrains
./gradlew buildPlugin
# 产物: build/distributions/hermes-chat-*.zip
```

把这个 ZIP 装到 IDE: `Settings → Plugins → ⚙ → Install Plugin from Disk…`,
然后重启 IDE.

### 方式 2: live development

```bash
./gradlew runIde
# 起一个沙箱 IntelliJ, 插件直接生效, 改代码 → 自动重载
```

需要 JDK 21. Android Studio Panda4 自带, 其他 IDE 装 Temurin 21.

## 配置

1. 终端起 dashboard: `hermes dashboard`
2. 复制启动 log 里的 session token
3. IDE: `Settings → Tools → Hermes Chat`
   - **Endpoint**: `http://127.0.0.1:9119` (默认)
   - **Session token**: 粘贴上一步的 token
   - **Default model**: 从下拉选
4. 点 **Test connection** 验证

## ⚠️ WSL 用户 — 装之前先看这个

**WSL 2 跑在自己的 Hyper-V 虚拟机里, 内部的 `127.0.0.1` 跟 Windows 看到的不是同一个.**
如果你的 `hermes dashboard` 跑在 WSL, 但 IDE 在 Windows 侧, 直接连 `127.0.0.1` 会**静默失败
("unreachable")**.

**最简方案 (适合 95% 用户)** — Windows portproxy 到 WSL, IDE 端用 `127.0.0.1`:

```powershell
# 一次性, 管理员 PowerShell
$wslIp = (wsl hostname -I).Trim().Split()[0]
netsh interface portproxy add v4tov4 `
    listenaddress=127.0.0.1 listenport=9119 `
    connectaddress=$wslIp connectport=9119
New-NetFirewallRule -DisplayName "Hermes Dashboard (WSL)" `
    -Direction Inbound -LocalPort 9119 -Protocol TCP -Action Allow
```

```bash
# WSL 里正常起
hermes dashboard
```

IDE Settings 里 endpoint 保持默认 `http://127.0.0.1:9119`. WSL 重启后如果连不上,
重跑上面的 PowerShell (WSL IP 可能变了). 详细对比 + 备选方案看
[README.en.md § WSL](README.en.md#-wsl-2-users--read-this-before-configuring).

## 兼容性

- IntelliJ IDEA 2024.2 → 2025.2
- Android Studio Koala (2024.1.1) → Meerkat (2025.x)
- JetBrains Runtime 21+
- Hermes Agent 0.6.0+ (dashboard SPA 是聊天的"真相源")

## 贡献

想贡献? 看 [CONTRIBUTING.md](CONTRIBUTING.md) (中文) /
[CONTRIBUTING.en.md](CONTRIBUTING.en.md) (English). 简版:

1. Fork → 克隆 → 建分支
2. `./gradlew test --offline` + `./gradlew runIde` 验证
3. 改代码 + 加测试 (没测试的 PR 不收)
4. 更新 `CHANGELOG.md` `[Unreleased]` 段
5. 提 PR, 模板会自动加载

## 安全

发现安全漏洞? **不要**开 public issue. 看 [SECURITY.md](SECURITY.md) 走私密报告
流程.

## License

[Apache-2.0](LICENSE). Copyright 2024-2026 Hermes Agent.
