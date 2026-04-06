# CMP Kit

面向 **Compose Multiplatform (CMP)** 的 IntelliJ/Android Studio 插件，专注提升 `composeResources/` 资源访问效率：

- **`Res.*.*` 一键跳转声明**（Ctrl/Cmd + 点击 / Go to Declaration）
- **`Res.string.*` 字符串预览折叠**（把资源引用折叠成真实文案，默认自动折叠）

> 仅对 `composeResources/` 内资源生效，并自动排除 `build/` 目录下的生成物，避免误跳与误解析。

## 功能

### `Res.*.*` 跳转声明

在 Kotlin 代码里对以下形态提供“转到声明”能力：

- **字符串**：`Res.string.<key>` → 跳转到 `composeResources/**/strings.xml` 内对应的 `<string name="<key>">`
- **图片**：`Res.drawable.<name>` → 跳转到 `composeResources/**/drawable*/<name>.<ext>`
- **字体**：`Res.font.<name>` → 跳转到 `composeResources/**/font*/<name>.<ext>`

图片扩展名支持：`xml/png/jpg/jpeg/webp/svg`  
字体扩展名支持：`ttf/otf`

### 字符串预览折叠（Folding）

将 Kotlin 代码中的字符串资源引用折叠为字符串值预览（带引号），效果类似 Android 资源引用折叠：

- `Res.string.some_key` → `"实际文案"`
- `stringResource(Res.string.some_key)` → `"实际文案"`
- `getString(Res.string.some_key)` → `"实际文案"`

解析来源：

- 在项目范围查找 `composeResources/**/strings.xml`
- 优先使用 `values/`，其次再尝试 `values-xx/` 等限定目录

性能策略：

- 索引不可用（Dumb Mode）或 IDE quick pass 阶段不做解析，避免卡顿

## 适用范围与限制

- **适用**：Compose Multiplatform 的 `composeResources/` 目录结构与 `Res.*.*` 访问方式
- **不处理/不会折叠**：
  - 非 `composeResources/` 下的 `strings.xml`（刻意不支持，防止误命中）
  - `Res` 以外的自定义资源入口（当前只识别 `Res.string.xxx` 的固定形状）

## 兼容性

- **目标 IDE**：Android Studio（通过 IntelliJ Platform Gradle Plugin 的 `androidStudio(...)` 配置）
- **sinceBuild**：`252.25557`（见 `gradle.properties`）
- **构建环境**：JDK 21、Kotlin 2.3.x

> 如果你需要支持更老的 IDE / IntelliJ IDEA，请调整 `platformVersion` 与 `sinceBuild` 并通过 `verifyPlugin` 验证。

## 安装

当前仓库暂无 Marketplace 发布信息，你可以用以下方式本地安装：

- **从源码运行/调试**：见下方“开发与调试”
- **从构建产物安装**：
  - 运行 `buildPlugin` 生成插件包

```bash
# Windows (PowerShell / CMD)
.\gradlew.bat buildPlugin

# macOS / Linux
./gradlew buildPlugin
```

  - IDE 中选择 **Settings/Preferences → Plugins → ⚙ → Install Plugin from Disk...**

## 开发与调试

```bash
# 运行 IDE（开发调试）
# Windows
.\gradlew.bat runIde
# macOS / Linux
./gradlew runIde

# 运行测试
# Windows
.\gradlew.bat test
# macOS / Linux
./gradlew test

# 兼容性校验
# Windows
.\gradlew.bat verifyPlugin
# macOS / Linux
./gradlew verifyPlugin

# 打包插件
# Windows
.\gradlew.bat buildPlugin
# macOS / Linux
./gradlew buildPlugin
```

本项目使用 `org.jetbrains.intellij.platform` Gradle 插件，更多任务说明见文档：
[`tools-intellij-platform-gradle-plugin`](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)

## 项目结构

核心实现位于：

- `src/main/kotlin/com/ciyin/cmpkit/ResourceGotoDeclarationHandler.kt`：`Res.*.*` 跳转声明
- `src/main/kotlin/com/ciyin/cmpkit/ComposeResStringFoldingBuilder.kt`：字符串预览折叠
- `src/main/kotlin/com/ciyin/cmpkit/ComposeResourceUtils.kt`：资源定位与字符串解析

插件描述与扩展点注册位于：

- `src/main/resources/META-INF/plugin.xml`

## 贡献

欢迎 PR / Issue。建议在提交前：

- 运行 `verifyPlugin` 确认兼容性
- 覆盖常见资源结构（`values/`、`values-zh/`、`drawable-night/` 等）

## 参考

- [IntelliJ Platform SDK 文档](https://plugins.jetbrains.com/docs/intellij)
- [Plugin Configuration File（plugin.xml）](https://plugins.jetbrains.com/docs/intellij/plugin-configuration-file.html)