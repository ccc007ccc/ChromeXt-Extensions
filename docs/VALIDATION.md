# 首轮实现与验证记录

日期：2026-10-09。对应首次提交的 M0/M1 源码及开发预览 0.1.0-dev。

## 本轮实现范围

- 基于 ChromeXt chromium-compat 建立派生项目和独立应用 ID。
- 移除内置 GM 运行时、用户脚本安装/管理、旧脚本数据库访问和旧扩展 stub；保留基础浏览器桥接与调试代码。
- 实现 MV2/MV3 ZIP 包检查、权限声明展示、审阅摘要校验、原子导入、持久列表及卸载。
- 提供临时独立管理 Activity 和标准 Page Marker 扩展 fixture。
- 记录 Chrome 内原生 UI、隔离上下文、扩展页面、后台和浏览器 API 的后续实施要求。

**当前扩展不能执行。** APK、包管理可用或某个 manifest 能被解析，均不代表完整扩展支持已经实现。

## 自动验证

环境：SteamOS 宿主；全部构建、生成器与测试在 Distrobox dev 内运行。JDK 21、Gradle 8.12、Android SDK Platform 35 / Build Tools 35.0.0。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| extension-core JVM 测试 | 22 项通过 | 正常包、清单/资源、路径与 ZIP 结构、解压限制、审阅一致性、持久化、卸载、崩溃暂存清理与并发锁等 |
| browser bridge Node 测试 | 16 项通过 | 实际 scripts.js 资产的锁、错误 token、旧管理地址、无 GM/安装接口，以及保留的 Eruda/事件通信 |
| Android lintDebug | 0 错误、1 警告 | 剩余为上游浏览器资源名称反射的 DiscouragedApi；未关闭该检查 |
| assembleDebug | 通过 | 独立应用 ID 和可启动管理组件 |
| assembleRelease | 通过 | R8/资源缩减，输出未签名 APK |
| APK 资产检查 | 通过 | Debug/Release 均不含 GM.js、editor.js、editor.css、旧 extension.js |
| Tampermonkey 5.5.1 实际包检查 | 通过 | 读取从官方包解出的本地 MV3 ZIP；状态仍为 NOT_RUNNABLE_RUNTIME_PENDING，没有执行扩展 |

~~~sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :extension-core:test :app:lintDebug :app:assembleDebug :app:assembleRelease
distrobox enter -n dev -- node --test tests/bridge.test.mjs
~~~

## Android 设备验证

基线设备：Android 16，已安装官方 Chrome 154.0.8037.127。测试不读取浏览历史或账号数据。

已完成：

1. 安装并启动 io.github.ccc007ccc.chromextextensions 的管理 Activity，确认名称、中文预览说明和“当前不能运行扩展”的状态。
2. 在 Android/ART 上通过 debug-only PackageStoreSmokeInstrumentation，无需打开 Activity 或操作 Chrome 前台界面。
3. 设备测试验证只读 inspect 不建立仓库、同包独立导入、重新实例化仓库后读回两条记录、恶意路径拒绝、卸载隔离及管理组件注册。
4. 测试包内包含如果执行就抛错的 JavaScript；整个包管理测试不执行它。测试使用独立 cache 目录并在结束后清理。

设备返回：

~~~text
PASS: inspect without writes; distinct imports; persisted reload; traversal rejection; isolated removal; manager component. No extension code executed.
traversal_rejection=INVALID_ARCHIVE
INSTRUMENTATION_CODE: -1
~~~

Android 14+ 的 ZipFile 可以在项目路径检查之前拒绝危险路径，因此设备上返回 INVALID_ARCHIVE + ZipException；JVM 路径检查返回 UNSAFE_PATH。设备测试只接受这两种明确拒绝方式，并核对仓库及外部路径没有被修改。

安装本项目 debug APK 后，可在选定设备上运行以下无界面测试：

~~~sh
adb shell am instrument -w -r io.github.ccc007ccc.chromextextensions/org.matrix.chromext.manager.PackageStoreSmokeInstrumentation
~~~

该组件只存在于 debug 变体，release 不包含它。

## 尚未验证或实现

- 系统文件选择器从选 ZIP 到确认导入的完整手工流程尚未完成验收；设备前台被用户使用时停止了交互操作。
- LSPosed 模块未由本次工作启用，Chrome 菜单 Hook、实际页面桥接及原账号同步尚未进行启用后的设备回归。
- 当前菜单目标仍是临时独立 Activity；正式 Chrome 内 UI 按 NATIVE_UI.md 推进，尚未完成。
- 扩展权限未授予，内容脚本、popup/options、MV3 worker、浏览器 API、Tampermonkey 实际执行及扩展同步均未实现。
- JVM 模拟桥测试不替代 Android Hook/renderer 集成测试。

## 本地构建产物

以下是本轮本地构建文件，CI 重新构建的容器、签名和构建元数据可能令摘要不同：

| 产物 | SHA-256 |
| --- | --- |
| app/build/outputs/apk/debug/app-debug.apk | 9295c9dc549d7919893569f067e8601ed5d1d39916e2346f397c04799d42ee64 |
| app/build/outputs/apk/release/app-release-unsigned.apk | 6399c9117cf631f7baaa8644b90ce30da69424bda7588f9b067db738c86a24e4 |

后续阶段须追加自己的真实验证记录，不将本轮包管理结果扩展为运行时兼容声明。

Tampermonkey 静态检查所用本地 ZIP 摘要为 88b9af2cd43e96f9a6a676735a5d36a3cd9a6b5d3ccbe48a773579ef75808925。该第三方包没有提交到本仓库。

## 现代 LSP API 102 迁移验收

日期：2026-10-09。对应 0.2.0-dev（versionCode 2），迁移设计与框架要求见 [LSP_API_MIGRATION.md](LSP_API_MIGRATION.md)。本节追加新结果，不覆盖上面的 0.1.0-dev 历史记录。

环境：SteamOS 宿主、Distrobox `dev`；JDK 21、Gradle 9.5.1、AGP 9.2.1、Kotlin 2.3.10、SDK Platform 37.0、Build Tools 37.0.0。应用最低 Android API 26、targetSdk 35，模块最低/目标 Xposed API 102。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| API 发布版本核对 | 通过 | Maven Central 最新稳定版为 `102.0.0`；三份核心 API 源码与指定 JingMatrix 仓库当前提交一致 |
| Hook JVM 测试 | 9 项通过 | 前后顺序、调用上下文、构造/静态调用、空结果、返回值保留、不可变参数替换、动作拦截、原异常传播及保护模式注册 |
| extension-core JVM 测试 | 22 项通过 | 原有包检查、导入、持久化、卸载和失败路径 |
| browser bridge Node 测试 | 16 项通过 | 原有保留桥接行为及内置 GM/安装路径继续关闭 |
| lintDebug | 0 错误、4 警告 | 私有 `Application.attach` 反射、保留 targetSdk 35、固定 Gradle 版本、上游资源名称反射；未增加屏蔽规则 |
| 全新 app 构建 | 通过 | 清理 app 构建目录后生成 Debug 与 R8/资源缩减 Release，均为 0.2.0-dev |
| Debug/Release APK 检查 | 通过 | 现代 META-INF 入口/配置/作用域；公开无参入口与生命周期仍在 DEX 中；无旧 API 类型引用或旧 manifest 元数据；未打包框架 API 类 |
| 测试组件隔离 | 通过 | Debug 包包含 PackageStoreSmokeInstrumentation，Release 包不包含 |
| Android 无界面测试 | 通过 | 在已有安装上更新 Debug APK，包管理原有检查及未注入框架时的日志调用均通过 |

最终构建及 APK 检查命令：

~~~sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/deck/Code/Toolchains/android-sdk ./gradlew :app:clean :extension-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
distrobox enter -n dev -- node --test tests/bridge.test.mjs
distrobox enter -n dev -- python3 tools/check-xposed-apk.py app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release-unsigned.apk
~~~

报告分别位于 `app/build/reports/tests/testDebugUnitTest`、`extension-core/build/reports/tests/test` 和 `app/build/reports/lint-results-debug.html`。CI 已使用相同测试及 APK 检查，并固定可安装 Platform 37.0 的 Command-line Tools 22.0。

手机上更新安装成功。无界面 instrumentation 额外确认独立应用 classloader 中不存在 `XposedModule`，而日志调用仍能正常执行；测试不打开 Chrome 或管理 Activity，使用独立临时目录。

~~~text
PASS: inspect without writes; distinct imports; persisted reload; traversal rejection; isolated removal; manager component; logging without framework API. No extension code executed.
traversal_rejection=INVALID_ARCHIVE
INSTRUMENTATION_CODE: -1
~~~

本轮本地产物摘要（CI 产物可能因构建/签名/版本控制元数据不同而变化）：

| 产物 | SHA-256 |
| --- | --- |
| app/build/outputs/apk/debug/app-debug.apk | daecd85c76764b983ed83b51add0cf05e60823dee20aea0dca647065ab3f72ea |
| app/build/outputs/apk/release/app-release-unsigned.apk | 40c5027cf40e2027dd2aa72686243a8dfb026999848210417f1025b642c35943 |

**框架设备验收尚未通过。** 手机安装的是 LSPosed IT v2.0.4-it (7748)，读取其框架 DEX 后确认 `XposedInterface.LIB_API = 101`，不满足本模块的 API 102 要求。本轮未升级系统框架或启用 Chrome 作用域，不能把包管理测试记为 Hook、菜单、页面桥接或 Google 同步回归通过。后续须在 API 102 框架下重启 Chrome 后补测；正式扩展运行时、Chrome 内 UI 和 Tampermonkey 执行仍按原里程碑推进。
