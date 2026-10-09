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
