# Contributing to Hermes Chat (JetBrains Plugin)

[English version →](CONTRIBUTING.en.md)

感谢你愿意贡献! 这个文档讲清楚怎么提 issue / 改代码 / 提 PR.

## 0. 适用范围

这个 repo **只是 JetBrains IDE 插件的源码**. Hermes Agent 的核心功能
(LLM 对话、skills、memory、cron、subagent) 都在
[hermes-agent](https://hermes-agent.nousresearch.com) 主项目里. 如果你的需求
是改后端能力, 去主项目提 issue / PR, **不要**在插件这里提.

## 1. 报 bug

按 [Bug report 模板](.github/ISSUE_TEMPLATE/bug_report.md) 填. **关键**:
附上日志 (`idea.log` 末尾 200 行 + 启动时 dashboard 日志), 没日志大概率查不出来.

## 2. 提议新功能

按 [Feature request 模板](.github/ISSUE_TEMPLATE/feature_request.md) 填.
先想清楚"为什么需要", 再想"怎么做". 插件边界 ≠ 后端能力.

## 3. 改代码 — 提 PR 流程

### 3.1 准备

1. **Fork** 这个 repo
2. **Clone** 你的 fork 到本地
3. 创建分支: `git checkout -b feat/your-feature` 或 `fix/issue-123`
4. **不要**在 main 上直接改

### 3.2 开发

```bash
# 在 IDEA / Android Studio Panda4 里打开
# 必须是 JDK 21 (Panda4 自带, 或 `gradle.properties` 里配置)
./gradlew test --offline          # 跑现有测试, 确认环境没问题
./gradlew runIde                  # 起一个沙箱 IntelliJ, 实时看效果
./gradlew buildPlugin --offline   # 出可分发 ZIP
```

### 3.3 风格 & 架构

- **看 `AGENTS.md`**: 这个文件列了项目的架构不变量 (PersistentStateComponent 边界、
  EDT 线程规则、JCEF dispose 模式、跨 OS dispatch). **违反 AGENTS.md 的 PR 会被打回**.
- **看 `references/` 下的 skill references**: 每个常见坑 (listener leak、WSL token、
  cross-OS strategy) 都有专门一节.
- **不要引入新依赖**除非确实必要. 新依赖的 license 必须是 Apache-2.0 兼容.
- **commit message**: 遵循 [Conventional Commits](https://www.conventionalcommits.org/),
  格式 `feat(scope): summary` / `fix(scope): summary` / `chore(scope): summary`.

### 3.4 测试

**没有测试的 PR = 不收**. 你改的代码必须有对应测试覆盖:

- 纯函数 / 策略选择 / 边界值 → JUnit 5 单元测试 (无 IDE fixture)
- UI 行为 / Disposable 树 / IDE 集成 → `BasePlatformTestCase` (有 IDE fixture)
- 跨 OS 行为 → 注入式 probes (别直接 mock `wsl.exe` 这种系统调用)

参考 `src/test/kotlin/com/hermes/agent/jetbrains/dashboard/` 下的现有测试.

### 3.5 CHANGELOG

更新 `CHANGELOG.md` 的 `[Unreleased]` 段, 写清楚你的改动. 格式参考现有章节.
**这是 release 时的"what's new"源**.

### 3.6 提 PR

1. 推到你 fork: `git push -u origin feat/your-feature`
2. 在 GitHub 上开 PR, 模板会自动加载
3. 关联 issue: 写 `Closes #123` (会触发 PR 合并时自动关 issue)
4. 等 CI 跑过 + reviewer approve

### 3.7 commit 拆分 (建议)

按 memory / 项目惯例, **生产代码 + 测试拆 2 commit**:

- Commit 1: 生产代码 + `AGENTS.md` / skill reference 更新
- Commit 2: 测试代码

这样 `git revert <commit-1>` 能干净回退功能, 不污染测试 history.

## 4. Review 流程

- 维护者会尽量 7 天内响应
- Review 重点: 是否符合 `AGENTS.md`、是否有测试、commit message 是否规范
- CI 必须过 (`.github/workflows/ci.yml` 跑 `./gradlew test` + `buildPlugin` + `verifyPlugin`)

## 5. 行为准则

这个项目还在建立社区准则阶段. 暂时参考: **专业、就事论事、不带人身攻击**.
重大问题处理会按 Apache-2.0 社区的常规做法.

## 6. License

你提交的代码按 Apache-2.0 协议贡献, 跟主项目 license 一致. 提 PR 即表示你同意
你的贡献也按 Apache-2.0 license 发布.
