# 现代 LSP API 迁移

日期：2026-10-09。开发预览版本：0.2.0-dev。

## 依赖与加载要求

按 [JingMatrix/libxposed-api](https://github.com/JingMatrix/libxposed-api) 当前的 102.0.0 版本迁移，使用其 README 指定的 Maven Central 坐标 `io.github.libxposed:api:102.0.0`。核对时仓库 HEAD 为 `39cac0845771547c9c67a3e3ce255af110a54a0e`；发布包的 `XposedInterface`、`XposedModule` 和 `XposedModuleInterface` 源码与该提交一致。

运行时只接受现代 API 102 或以上。`module.prop` 的最低和目标 API 均为 102，入口也检查框架实际返回的 API 版本。源码、依赖和 APK 均不再保留旧 Xposed API 的加载路径。

API 通过 `compileOnly` 使用，不打进 APK；仅 JVM 回调测试的运行类路径额外包含 API。独立管理应用的日志只依赖 Android 与 Kotlin，注入进程在 `onModuleLoaded` 中接入框架日志。

## 迁移内容

| 部分 | 新实现 |
| --- | --- |
| Java 入口 | `MainHook : XposedModule()`，公开无参数构造函数 |
| 模块初始化 | `onModuleLoaded` 接入 Hook/日志，并从 `moduleApplicationInfo.sourceDir` 获取模块资源路径 |
| 浏览器类加载 | `onPackageReady` 使用最终应用 classloader；只处理进程的首个包，避免后续加载其他包时重复注册 |
| Chromium 初始化 | 保留 `WindowAndroid(Context, …)` 构造回调，在获得浏览器 Context 后安装各功能 Hook |
| WebView 初始化 | 等待 `Application.attach(Context)` 完成，获取真实应用 Context 后安装对应 Hook，并解除该一次性回调 |
| Hook | 使用 `hook(Executable).setPriority(...).setExceptionMode(PROTECTIVE).intercept(...)` |
| 参数修改 | 复制 `Chain.args` 后传给 `proceed(args)`；不修改新版只读参数列表 |
| 拦截返回值 | 拦截器直接返回结果；未处理的调用通过 `proceed()` 继续 |
| 后置回调 | 只观察成功调用，保留原结果；宿主原异常继续传播，清理操作使用 `finally` |
| 解除 Hook | 框架提供的 `HookHandle.unhook()` |
| 模块声明 | `META-INF/xposed/java_init.list`、`scope.list`、`module.prop`，说明使用 `android:description` |
| Release | 保留入口类名和无参数构造；对实际压缩 APK 检查类定义、生命周期和打包内容 |

现代 API 不再提供 Zygote 初始化入口。本项目资源通过应用进程中的模块 APK 路径注入，未使用旧框架资源 Hook。默认推荐作用域仍只有官方 Chrome，其他继承的浏览器适配仍待验证。

本轮不启用 API 102 热重载：现有浏览器对象、监听器、CDP 连接和线程尚无完整的卸载协议，因此 `autoHotReload=false`，保留框架默认的拒绝热重载行为。更新模块后应重启作用域应用。

## 构建与验证方法

发布 AAR 声明 `minCompileSdk=37` 和 `minSdk=26`。构建升级到 SDK Platform 37.0、Build Tools 37.0.0、AGP 9.2.1、Gradle 9.5.1、Kotlin 2.3.10 和 JDK 21；应用 `targetSdk` 保持 35，避免把系统行为迁移混入本轮修改。

全部构建、依赖安装和测试在 Distrobox `dev` 中运行，命令见 [README](../README.md#构建)。

- JVM 回调测试覆盖调用顺序、返回值、静态/构造调用、参数替换、动作拦截和原异常传播。它们不模拟 ART 安装 Hook，也不能替代框架设备测试。
- `tools/check-xposed-apk.py` 读取 Debug/Release APK 的模块声明与 DEX，确认入口可被框架实例化，不含旧 API 引用，也未打包框架 API 的类定义。
- 保留扩展包核心、JavaScript 桥接、Lint、Debug/Release 构建及 Android 无界面包管理测试。

实际结果记录在 [VALIDATION.md](VALIDATION.md)，未完成的框架实测单独列出。

## 设备框架核对

迁移初次验收时，手机框架为 `LSPosed IT v2.0.4-it (7748)`。读取其已安装 `framework.dex` 后，`XposedInterface.LIB_API` 的实际值为 **101**，不满足模块的加载要求。框架显示版本不能代替 API 版本检查。

用户随后更新并重启到 **LSPosed 2.2.1 (7912)**。已核对新框架 DEX 的 `LIB_API=102`，并通过运行中 daemon 的 `lspctl --json status` 确认 `apiVersion=102`、`safeMode=false`。该设备现已满足框架要求；模块已启用，作用域为用户 0 的 `com.android.chrome`。

0.3.0-dev 已在该框架与官方 Chrome 154.0.8037.127 上取得 API 102 加载和菜单 Hook 的实际证据，菜单可打开 Chrome 所属的原生包管理面板，认证 IPC 与固定文件管理回归通过。结果与限制见 [VALIDATION.md](VALIDATION.md#030-devchrome-原生包管理与运行时实验)。这更新了迁移初次验收时未取得 Chrome 加载日志的状态；完整 WebExtensions 运行时与扩展兼容仍未完成。
