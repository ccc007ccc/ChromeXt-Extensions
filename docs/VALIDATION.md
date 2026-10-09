# 实现与验证记录

本文按版本保留历史结果。最新为下文的 [0.3.0-dev 验收](#030-devchrome-原生包管理与运行时实验)；旧记录中的“Chrome 尚未加载”和“原生管理界面尚未实现”是当时状态，已由最新实机结果更新。

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

初次迁移验收时，手机安装的是 LSPosed IT v2.0.4-it (7748)，读取其框架 DEX 后确认 `XposedInterface.LIB_API = 101`，当时不满足模块的 API 102 要求。包管理测试没有被记为 Hook、菜单、页面桥接或 Google 同步回归通过。用户随后更新框架，补充结果如下；正式扩展运行时、Chrome 内 UI 和 Tampermonkey 执行仍按原里程碑推进。

### 框架升级后补充

- 代码提交 [`9f0d78c`](https://github.com/ccc007ccc/ChromeXt-Extensions/commit/9f0d78c777585b3896dcffb9b45e511d0a644e20) 的 [GitHub CI](https://github.com/ccc007ccc/ChromeXt-Extensions/actions/runs/37883185478) 已全部通过，包括 47 项测试、Lint、两种 APK 构建及 APK 入口/依赖检查。
- 用户更新并重启到 LSPosed 2.2.1 (7912)。新框架 DEX 与运行中 daemon 均确认 API 102，daemon 正常运行且不在安全模式。
- 只读检查框架配置确认模块已启用，作用域为用户 0 的 `com.android.chrome`；本次检查没有修改框架数据库。
- 本次后台检查时 Chrome 主进程尚未运行，未取得模块在 Chrome 进程中的加载及 Hook 日志。已请用户打开 Chrome 后继续验收；菜单、页面桥接和账号同步回归仍待实测。

## 0.3.0-dev：Chrome 原生包管理与运行时实验

日期：2026-10-09。源码提交：[`72331c17b8d3c1f92d01e6ad977cce81bc16bec5`](https://github.com/ccc007ccc/ChromeXt-Extensions/commit/72331c17b8d3c1f92d01e6ad977cce81bc16bec5)，versionCode 3。本节补充并更新前述框架升级后尚未取得 Chrome 加载证据的状态。

交付范围是 **M2 的原生包管理、认证 IPC 和运行时技术实验**，不代表 M2 已完成。已保存的扩展继续处于未启用状态；内容脚本/API 的生产运行时、popup/options 和后台宿主尚未完成，Tampermonkey 仍不支持。

### 环境与自动验证

构建在 SteamOS 宿主的 Distrobox `dev` 中进行：JDK 21、Gradle 9.5.1、AGP 9.2.1、Kotlin 2.3.10、SDK Platform 37.0 / Build Tools 37.0.0。设备为 Android 16、官方 Chrome 154.0.8037.127、LSPosed 2.2.1 (7912) / API 102，模块作用域为用户 0 的 `com.android.chrome`。Chrome revision 为 `4ed32028bcb2d00bc07a8880eb85aca80c9c5ec6`。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| extension-core JVM 测试 | 33 项通过 | 包检查/持久化、审阅令牌的调用方绑定、过期/容量/一次使用、摘要复核与安全资源读取 |
| app JVM 测试 | 11 项通过 | API 102 Hook 适配，以及认证调用方的包/签名/Android 用户边界 |
| browser bridge Node 测试 | 16 项通过 | 保留桥接行为及内置 GM/旧安装入口持续关闭 |
| 自动测试合计 | 60 项通过 | 44 项 JVM + 16 项 Node；不包含下述设备与运行时检查 |
| lintDebug | 0 错误、7 警告 | 完整报告保留在本地构建目录与 CI artifact |
| Debug / Release / ipc-probe 构建 | 通过 | 模块 Debug、R8/资源缩减 Release 和独立 UID 的测试 APK |
| API 102 APK 检查 | 通过 | 现代入口/配置/作用域、入口保留、无旧 API 或打包的框架 API 类 |
| Release 诊断隔离 | 通过 | `--forbid-diagnostics` 确认特权浏览器诊断和测试组件未进入 Release |

汇总见 [build-summary.json](evidence/2026-10-09/build-summary.json)。复现命令：

~~~sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/deck/Code/Toolchains/android-sdk ./gradlew :extension-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :ipc-probe:assembleDebug
distrobox enter -n dev -- node --test tests/bridge.test.mjs
distrobox enter -n dev -- python3 tools/check-xposed-apk.py app/build/outputs/apk/debug/app-debug.apk
distrobox enter -n dev -- python3 tools/check-xposed-apk.py --forbid-diagnostics app/build/outputs/apk/release/app-release-unsigned.apk
~~~

### Chrome 与 Binder 实机结果

- Chrome 进程内诊断确认 API 102、0.3.0-dev、浏览器和菜单 Hook 已加载。自动操作真实菜单，管理面板属于当前 Chrome 窗口，入口不再跳转独立模块 Activity。
- 固定测试文件回归通过：打开菜单/面板、审阅并取消、再次审阅并导入、显示未启用状态、查看声明权限、移除和关闭。固定 ZIP 在 Chrome 缓存中生成，经过正常审阅/提交路径；本项明确跳过系统文件选择器。见 [native-manager.json](evidence/2026-10-09/native-manager.json)。
- 独立测试 APK 的 UID 与 Chrome/模块不同；version、list、describe、review、commit、cancel、remove、openResource 共 8 个 Binder 操作均在读取包或 ZIP 输入前抛出 `SecurityException`。见 [untrusted-binder.json](evidence/2026-10-09/untrusted-binder.json)。
- `PackageStoreSmokeInstrumentation` 再次通过只读检查、独立导入、持久化重载、路径穿越拒绝、卸载隔离、管理组件及无框架环境的日志调用；返回明确的 `INSTRUMENTATION_CODE: -1`，未执行包内扩展代码。

设备命令与自动化方式见 [DEVICE_TESTING.md](DEVICE_TESTING.md)。日常回归复用固定文件，不反复搜索系统选择器；只在更新模块 APK 后重启 Chrome。

ColorOS 起初限制了模块服务的关联启动，打开模块应用后检查通过；UI 已提供对应启动失败提示。OEM 冻结无 Activity 的测试进程时，脚本可临时解冻测试 APK 的 UID，不修改 Chrome 或全局电池策略。

### 运行时 A/B 实验

两份标准 manifest/source fixture 在专用测试标签页中完成 10 项检查：请求/持续事件、顶层及同源/跨源 frame 的首段脚本前注入、A/B 与 MAIN 世界隔离、伪造 payload 仍由宿主上下文识别、SPA 文档身份、动态 frame 创建/销毁、重定向后的旧上下文/未决调用拒绝、刷新后早期注入、返回恢复以及普通网页 CORS 限制。实际观察到 9 个执行上下文、2 个 session（包含 OOPIF）和 `pageshow.persisted=true` 的 BFCache 恢复。

这些是 Node/CDP 探测结果，尚不是 Android 生产扩展运行时。任意新标签首次导航、后台/冻结恢复、renderer 丢失与 Chrome 进程重启尚未完成实验 A 的退出验收。

实验 B 中，未注册的 `chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/popup.html` 无论直接导航还是启用限定 URL 的 Fetch 拦截，都返回 `net::ERR_ABORTED`，拦截事件数为 0，popup JavaScript 未加载。该结果否定了本次仅依靠 CDP Fetch 为未注册扩展来源提供资源的尝试；没有证明全部原生实现路径均不可行。扩展注册/来源、资源与页面宿主及 MV3 worker 仍是下一阶段门槛。

详细方法、限制与决策见 [RUNTIME_EXPERIMENTS.md](RUNTIME_EXPERIMENTS.md)，逐项证据及 fixture SHA-256 见 [runtime-probe.json](evidence/2026-10-09/runtime-probe.json)。

附加 `GET /json/protocol` 查询期间，Chrome 在本地时间 `2026-10-09 14:50:39.266 +08:00` 记录了原生 `SIGTRAP`。未做关闭模块的对照，不能独立归因。该请求已从脚本移除；模块 HTTP helper 仅允许 `/json` 和 `/json/version`。恢复 Chrome 后，既定 10 项检查再次全部通过并关闭自身测试标签页。后续离线核对固定 Chromium revision 的协议 schema，不在本设备重复该查询。Build ID 与栈偏移见 [protocol-discovery-failure.json](evidence/2026-10-09/protocol-discovery-failure.json)。

### 尚未完成的设备验收

- 系统文件选择器已完成一次“选包 → 权限审阅 → 取消”；没有把它记为“选择器 → 确认导入”的完整矩阵通过。此前重复搜索自动化超时属于测试脚本效率/定位问题，不能据此判断文件选择器不可用。
- 文件选择返回时的暂停/恢复、旋转、多窗口、主题切换及完整无障碍矩阵尚未验收。
- 无痕或未知 profile 的请求在实现中默认拒绝；无痕 UI 和普通/无痕切换的完整设备矩阵尚未验收。
- Google 登录与浏览器同步尚未进行专项回归；没有读取账号或同步数据来推导通过。扩展 `storage.sync`、安装列表同步仍未实现。
- 任意第三方扩展执行、完整页面桥接回归、popup/options、MV2/MV3 后台及 Tampermonkey 专项均不能由本轮结果推导为通过。

### 本地产物摘要

以下对应上述源码提交的本地构建；CI 使用不同构建元数据/签名时摘要可能不同。

| 产物 | SHA-256 |
| --- | --- |
| app/build/outputs/apk/debug/app-debug.apk | 3c117317e0962215c050a373794b89b34b1788ffd9aa88028d2802427a8ea67e |
| app/build/outputs/apk/release/app-release-unsigned.apk | 72a244bac3d08b4f0cc47ec036f439876364e00f7d7364e18d476c115d4351c4 |
| ipc-probe/build/outputs/apk/debug/ipc-probe-debug.apk | 90fdd2c7767b73b7e7cbfc549717d69cf63426023cec91cadec3972c1b16d228 |
