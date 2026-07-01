<!--
感谢你给 Hermes Chat 提交 PR.
请把下面所有 section 填好; 不填的会被打回.
-->

## 这个 PR 解决了什么问题?

<!--
  - 关联的 issue: Closes #123 / Fixes #456
  - 或者: 描述一下为什么要做这个改动
-->

## 改动概要

<!--
  一两句话讲清楚做了什么, 让 reviewer 不用读全部 diff 就知道意图.
-->

## 改动类型

<!-- 勾选所有适用的 -->

- [ ] Bug fix (改了一个坏掉的行为)
- [ ] New feature (加了新功能)
- [ ] Breaking change (改了一个会让现有用户配置 / 行为变化的东西)
- [ ] Refactor (没功能变化的代码整理)
- [ ] Documentation (只动了文档)
- [ ] CI / build (只动了 CI / Gradle 配置)

## 你做了什么验证?

<!--
  至少跑过 ./gradlew test 全套过 + 改动的代码有对应测试.
  如果是 UI 改动, 描述在哪个 IDE 版本手测过.
-->

- [ ] `./gradlew test --offline` 全过
- [ ] `./gradlew buildPlugin --offline` 出 ZIP 成功
- [ ] 在 IDEA / Android Studio 里手测过 (哪个版本?)
- [ ] 我加了新测试覆盖这个改动 (如果没有, 请说明为什么不加)
- [ ] 我更新了 `CHANGELOG.md` 的 [Unreleased] 段

## Checklist

- [ ] 我的代码遵循项目现有风格 (看 `AGENTS.md`)
- [ ] 我在 `gradle.properties` / `*.gradle.kts` 没改无关配置
- [ ] 我没引入新依赖, 或者新依赖的 licence 是 Apache-2.0 兼容的 (Apache-2.0 / MIT / BSD / 等)
- [ ] 我没提交 IDE/Gradle 缓存目录 (`.idea/`, `.gradle/`, `build/`)
- [ ] 我没提交 Windows temp 路径下的孤儿文件 (那种 `C:\Users\...` 开头的)
- [ ] 我的 commit message 遵循 [Conventional Commits](https://www.conventionalcommits.org/) (格式: `feat(scope): summary`)

## 截图 / 录屏 (UI 改动时)

<!-- UI 改动必须有截图 -->

## 关联的 issue / 讨论

<!-- 链接到任何相关的 issue 或 discussion -->
