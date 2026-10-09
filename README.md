# ChromeXt-Extensions

为官方 Android Chrome 开发 WebExtensions 扩展运行时的 LSPosed 项目。

本项目的目标是加载真实浏览器扩展，保留官方 Chrome 的 Google 登录与浏览器同步。项目不提供内置油猴脚本安装器或 GM 运行时；将来通过扩展安装 Tampermonkey 是扩展兼容工作的一部分。

**当前为开发预览，不具备完整扩展执行能力。** 扩展包管理器可导入、查看声明权限及移除 ZIP 包；保存包不代表可以运行。运行时和各项 API 的实际状态见[兼容矩阵](docs/COMPATIBILITY.md)。

## 规范与计划

- [WebExtensions spec](docs/WEBEXTENSIONS_SPEC.md)：产品目标、架构、权限与生命周期合同。
- [实施计划](docs/IMPLEMENTATION_PLAN.md)：M0–M5 的交付与验收条件。
- [兼容矩阵](docs/COMPATIBILITY.md)：已实现、未实现和待实机验证的能力。
- [Chrome 内原生 UI](docs/NATIVE_UI.md)：参考 Chromium Android 桌面端的正式界面方案。
- [验证记录](docs/VALIDATION.md)：本轮构建、自动测试和设备验证的实际范围。
- [现代 LSP API 迁移](docs/LSP_API_MIGRATION.md)：API 102 的入口、Hook、构建与框架要求。

开发分支：feature/webextensions-runtime。首个浏览器基线为 Android 16 上的官方 Chrome 154.0.8037.127。

Google 浏览器同步与扩展的 chrome.storage.sync 是不同功能；后者需要由扩展运行时单独实现。

正式扩展管理、工具栏入口及弹窗以集成到 Chrome 内为目标。当前独立管理应用只是 M1 包管理验证工具，不代表该 UI 目标已经完成。

## 开发预览

构建并安装 APK 后，从应用列表打开 **ChromeXt-Extensions**，选择扩展 ZIP，查看清单和声明权限后导入。扩展始终显示“未启用 · 扩展运行时尚未就绪”，当前不能启用。

- ZIP 根目录必须包含 manifest.json。
- 支持读取 Manifest V2/V3 包；校验包内路径、资源与大小限制。
- 导入不会执行包内 JavaScript，也不会授予扩展权限。
- 扩展包保存在本应用私有目录中，与上游用户脚本数据库分离。
- 内置用户脚本执行、安装、管理与 GM 接口已移除；不会导入或删除上游旧脚本。
- 包管理不需要启用 LSPosed。Chrome 内的管理入口与保留的调试功能需要 LSPosed；当前不应将其视为完整扩展支持版本。

应用 ID 为 io.github.ccc007ccc.chromextextensions，与原版 ChromeXt 不同。Kotlin 源码暂保留上游命名空间以减少移植差异。两个模块不要同时作用于同一个 Chrome 进程。

## 构建

需要 JDK 21、Android SDK Platform 37.0 / Build Tools 37.0.0。项目使用 AGP 9.2.1、Gradle 9.5.1 和 AGP 内置 Kotlin；API 依赖为 Maven Central 的 `io.github.libxposed:api:102.0.0`，仅用于编译。

APK 最低支持 Android 8.0（API 26）。Chrome 内的模块功能要求提供 **现代 Xposed API 102** 的框架；旧 Xposed API 82 和现代 API 100/101 不再作为加载目标。独立包管理界面仍可在没有框架的情况下使用。

~~~sh
./gradlew :extension-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
node --test tests/bridge.test.mjs
python3 tools/check-xposed-apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
~~~

开发机为 SteamOS 时，使用 dev Distrobox 容器运行构建、依赖安装、生成器与测试：

~~~sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/deck/Code/Toolchains/android-sdk ./gradlew :extension-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
distrobox enter -n dev -- node --test tests/bridge.test.mjs
distrobox enter -n dev -- python3 tools/check-xposed-apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
~~~

也可在未提交的 local.properties 中设置 sdk.dir。调试 APK 输出到 app/build/outputs/apk/debug/app-debug.apk；release 构建默认不签名。

## 来源与许可证

派生自 [JingMatrix/ChromeXt](https://github.com/JingMatrix/ChromeXt)，继承 GPL-3.0 许可证及上游作者署名。开发起点是上游 chromium-compat 分支的 0a6253bb975f3bd868df1521cba039c40b9003ad，来自尚未合并的 [PR #306](https://github.com/JingMatrix/ChromeXt/pull/306)。

上游的网页 Hook、CDP 传输及调试基础可供复用；其调试版扩展原型并不是已完成的 WebExtensions 引擎。本项目不以此宣称支持任意 Chrome 商店扩展。
