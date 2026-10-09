# Chrome 内原生扩展 UI

状态：设计规范，尚未实现正式 UI。目标版本为官方 Android Chrome 154.0.8037.127。

## 用户要求与验收边界

正式界面集成在 Chrome 内，参考 Chromium Android 桌面构建的扩展交互。用户应从浏览器工具栏/菜单打开扩展，管理权限、查看错误、使用扩展弹窗和设置；正常操作不跳转到模块的独立应用。

M1 的 ExtensionManagerActivity 是包管理验证工具。现有 Chrome 菜单通过 ExtensionManagerLauncher 打开它，**这不算 Chrome 内原生管理界面已经完成**。它可长期保留为诊断、恢复和备用入口，但不能代替本规范。

“原生集成”包括所属 Chrome 窗口、当前标签页、返回键、主题、焦点、无障碍、手机与宽屏布局，以及扩展 API 的相应行为；仅外观相似不构成完整验收。

## Chromium 参考基线

所有来源固定到同一版本，避免把上游更新后的组件误当作手机 APK 的现有能力：

- [扩展 UI 模块](https://chromium.googlesource.com/chromium/src/+/refs/tags/154.0.8037.127/chrome/browser/ui/android/extensions/)
- [BUILD.gn](https://chromium.googlesource.com/chromium/src/+/refs/tags/154.0.8037.127/chrome/browser/ui/android/extensions/BUILD.gn)
- [ExtensionUi.java](https://chromium.googlesource.com/chromium/src/+/refs/tags/154.0.8037.127/chrome/browser/ui/android/extensions/java/src/org/chromium/chrome/browser/ui/extensions/ExtensionUi.java)

ExtensionUi 接口会在没有 C++ 扩展系统时仍然编译。真正的 ExtensionUiBackendImpl、ExtensionsMenuBridge、ExtensionInstallDialogBridge 和 ExtensionDeveloperPrivateBridge 等实现受 enable_extensions_core 编译条件控制。源码可供移植交互和 UI 结构，不能由类名存在推断完整实现已经包含在普通 Chrome APK 中。

UI 适配层要连接本项目运行时。依赖原生扩展系统的 JNI 调用需要由已实现的能力替换；不能直接调用缺失的 backend，也不能返回空结果掩盖未实现功能。复制 Chromium 源码时保留相应版权及许可证说明。

## 界面组成

| 界面 | 目标行为 | 第一阶段实现方向 |
| --- | --- | --- |
| 工具栏与菜单 | 扩展入口、已固定扩展图标、当前页状态 | 在实际 Chrome Toolbar/Menu 生命周期接入；先验证菜单和管理面板，再增加固定图标 |
| 扩展列表 | 图标、名称、状态、固定、启停、详情 | Chrome Activity 内的原生 View/面板；遵循 Chrome 主题与窗口归属 |
| 安装与权限 | 安装前展示声明及实际支持状态；权限增量单独确认 | Chrome 内对话框与系统文件选择器；安装审阅结果绑定包摘要 |
| 扩展 popup | 执行扩展自己的 HTML/CSS/JS，关联触发它的 tab | Chrome 内受隔离的扩展页面宿主；宽屏锚定弹窗，手机适配底部面板或浏览器内页面 |
| options / dashboard | 运行扩展自己的设置与管理页面 | Chrome 内页面/标签宿主；准确实现来源、资源、权限和关闭行为 |
| 全局扩展管理 | 列表、权限、错误、卸载和开发者功能 | 浏览器内管理界面；只有内部页面路由可用后，才考虑 chrome://extensions 地址兼容 |
| 错误与兼容状态 | 用户能看到具体不支持的能力和失败原因 | 原生界面展示运行时结果，不把“已导入”显示成“已启用” |

扩展自己提供的 HTML 页面仍是 Web 内容，不会改写成用户脚本。原生 View 用于浏览器侧控制界面，扩展页面使用独立受控的 WebContents/页面宿主。普通 WebView 或替代 origin 如用于实验，必须记录其与 Chrome 扩展上下文的差异。

## 跨进程存储和权限

M1 包存储位于模块应用的私有目录。LSPosed 代码在 Chrome 内运行时使用 Chrome 的 UID，不能直接读取另一个应用的私有文件。

正式接入前实现受认证的 IPC/Broker：

1. 模块进程管理包、元数据和安装事务；Chrome 进程管理浏览器 UI、tab 与执行上下文。
2. 每次 IPC 操作核对系统提供的调用 UID、对应包及目标用户/profile。不能信任请求中自报的包名、扩展 ID 或路径。
3. 仅提供明确的列表、安装事务、权限决策和资源读取接口；资源以校验过的 ID/相对路径或受控文件描述符传递。
4. 普通网页和扩展代码不能获得管理器 Binder、任意文件读取或任意 CDP 执行能力。
5. 服务断开、进程死亡、窗口销毁和活动重新创建时，取消或恢复待决操作；不保留失效的 Chrome Activity。

文件选择器是系统 UI，可以由浏览器侧发起。选中 URI 后通过受控文件描述符交给安装服务，并保持 M1 的大小限制、审阅摘要、暂存与原子提交。不通过放宽文件权限、开放任意路径 ContentProvider 或复用旧远端管理站点解决跨 UID 问题。

## 实施顺序

1. **浏览器内面板实验**：在 Chrome 154 内打开原生管理面板，验证深浅色主题、返回键、旋转和当前窗口归属；不执行扩展。
2. **认证包存储桥**：在面板中读取 M1 包列表，完成权限审阅、导入和卸载；对伪造调用方、跨用户和越权路径做反向测试。
3. **工具栏与菜单**：参考 desktop Android 组件补充扩展按钮、固定图标、状态更新和窄屏适配。Hook 不可用时明确报告兼容错误。
4. **popup/options**：在隔离上下文和资源来源实验通过后，接入真实扩展页面与消息通路。
5. **完整行为对齐**：随运行时实现权限、后台、错误页面及内部管理页；与固定版本 Chromium 参考行为逐项比较。

前两项前移到 M2 起始阶段，与运行时的 context/origin 技术实验并行推进。

## 必须通过的设备验收

- 普通扩展管理流程始终属于当前 Chrome 窗口，不启动独立模块 Activity。
- 切换标签、关闭 tab、返回、旋转、后台恢复和深浅色切换不留下错误归属的弹窗。
- 两个窗口及普通/隐身上下文不混用扩展状态；未实现隐身时明确拒绝。
- 未授权应用、普通网页和扩展内容不能调用安装/卸载或读取其他扩展数据。
- popup 关闭与 runtime Port、消息、焦点的行为一致；不以常驻隐藏界面伪造后台生命周期。
- 同一个标准扩展包无需改写源码即可接受 UI 与运行时的联合验收。

本规范不宣称现阶段已经复现 Chromium 的完整扩展 UI，也不把重新注册一个 Activity、改写地址栏字符串或取消 DEBUG 条件视为实现完成。
