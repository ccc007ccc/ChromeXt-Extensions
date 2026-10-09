# 设备自动验收

全部构建和脚本运行在 Distrobox `dev`。安装 0.3.0-dev Debug 模块及测试 APK，在提供 API 102 的 LSPosed 中启用模块；更新模块后重启 Chrome。测试 APK 没有桌面入口，不是 Xposed 模块。

```sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/deck/Code/Toolchains/android-sdk ./gradlew :app:assembleDebug :ipc-probe:assembleDebug
adb -s DEVICE install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE install -r ipc-probe/build/outputs/apk/debug/ipc-probe-debug.apk
```

以下命令将 `DEVICE` 换成 `adb devices` 列出的设备。若 `dev` 的 PATH 不含 adb，给脚本加 `--adb /home/deck/Code/Toolchains/android-sdk/platform-tools/adb`。

## 固定文件的原生管理回归

让 Chrome 保持前台普通窗口，运行一次命令：

```sh
distrobox enter -n dev -- python3 tools/run-device-probe.py --serial DEVICE --native-ui --report /tmp/chromext-native-ui.json
```

脚本从实际 Chrome 菜单进入原生面板，使用 Chrome 缓存中的固定 fixture 文件，自动验证取消审阅、再次审阅、导入为未启用状态、权限详情、移除和关闭。它复用正常导入的审阅/提交逻辑，**默认跳过系统文件选择器**，不把选择器的查找速度混入每轮回归。

Debug 的固定 fixture 操作由 Android 特权 `DUMP` 权限保护，不接受任意路径或扩展代码；测试进程仅在调用期间借用该权限，随后释放。Release 构建会移除整个浏览器诊断入口，APK 检查会验证这一点。

- `--prepare`：需要时先打开模块应用，再打开 Chrome。用于设备的关联启动限制及初始准备，不属于被验收的 Chrome 菜单操作。
- `--restart-chrome`：仅在模块 APK 更新后加，用于加载新代码，不应在每轮固定文件回归中反复重启。
- `--thaw-test-uid`：部分 OEM 会冻结无 Activity 的 instrumentation 进程。此选项需 root，只在本轮测试期间解除**测试 APK UID** 的冻结，不修改 Chrome 或全局电池策略。
- `--system-picker`：额外跑一次真实系统选择器，测试 ZIP 自动放在 `Download/ChromeXt-tests`，完成或失败后清理。这个选择器驱动不是默认回归；当前记录中单次选包/审阅/取消成功，但重复搜索曾超时，不记为完整验收通过。

控件通过资源 ID/可访问性节点操作；前台离开 Chrome 或已知系统选择器时停止，不使用盲点屏幕坐标。失败记录包含步骤、受限控件诊断和模块状态，不采集其他应用内容。`am instrument` 的进程退出码不能代表测试通过，脚本要求明确的 `INSTRUMENTATION_CODE: -1`，并写入 JSON 的 `passed` 字段。

## 未授权调用反例

```sh
distrobox enter -n dev -- python3 tools/run-device-probe.py --serial DEVICE --report /tmp/chromext-untrusted.json
```

测试 APK 用不同于 Chrome/模块的 UID，依次调用 version、list、describe、review、commit、cancel、remove、openResource。全部必须在处理包或 ZIP 输入前返回 `SecurityException`；即使测试 APK 与模块使用同一开发签名，也不能被放行。

## 运行时 A/B 实验

```sh
distrobox enter -n dev -- node tools/probe-runtime.mjs --serial DEVICE --output /tmp/chromext-runtime.json
```

需要 Node 24。脚本只创建自己的测试标签页和 frame，并在结束后移除自己的 target/ADB 映射。本次实际覆盖和未通过门槛见 [RUNTIME_EXPERIMENTS.md](RUNTIME_EXPERIMENTS.md)。不要额外向受测手机请求 `/json/protocol`，相关崩溃已记录，schema 改为离线核对。

常规开发顺序是构建/单元检查、固定文件回归和认证反例，再运行有改动的技术实验。系统选择器、旋转、主题和多窗口的验收各自单列，不反复插进同一个导入循环。
