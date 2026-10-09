# ChromeXt-Extensions 实施计划

更新：2026-10-09。规范见 [WEBEXTENSIONS_SPEC.md](WEBEXTENSIONS_SPEC.md)，当前支持状态见 [COMPATIBILITY.md](COMPATIBILITY.md)。

本计划围绕完整 WebExtensions 运行时推进。关闭内置油猴功能是第一阶段；后续真正的 Tampermonkey 通过扩展 API 运行，不恢复 ChromeXt 自带的 GM 引擎。

## 阶段总览

| 阶段 | 交付目标 | 当前状态 | 进入下一阶段的门槛 |
| --- | --- | --- | --- |
| M0 | 派生仓库、产品入口、关闭内置 GM、能力边界文档 | 已实现；构建/自动检查通过，Hook 实机回归待测 | 内置脚本入口/执行/桥接均被阻断，构建与检查有记录 |
| M1 | 安全 ZIP/manifest 安装、注册及管理 | 核心与临时 UI 已实现；文件选择器端到端待测 | 安装、禁用、卸载、异常输入和持久化通过验收 |
| M2 | Chrome 内原生 UI、隔离内容脚本、消息、存储、popup/options | 未实现 | 技术实验 A 及资源隔离门槛通过，最小扩展端到端通过 |
| M3 | MV3 后台 worker、事件及恢复 | 未实现 | 技术实验 B 的 worker 部分通过，终止/唤醒/重启通过 |
| M4 | Tampermonkey 所需浏览器 API 和真实扩展验收 | 未实现 | 原版 Tampermonkey 5.5.1 的完整专项通过 |
| M5 | API 覆盖扩展、分发更新、同步和 Android 稳定性 | 未实现 | 固定 API 清单与真实扩展矩阵达到已声明支持范围 |

可以在 M1 安装器开发期间并行做两个技术实验，以尽早发现架构限制。需要隔离保证的扩展执行必须等待门槛通过；不能因安装器已经可用而提前开放。

## M0：建立可持续派生项目

任务：

1. 基于 `0a6253bb975f3bd868df1521cba039c40b9003ad` 建立 `ccc007ccc/ChromeXt-Extensions` 和 `feature/webextensions-runtime`，保留上游版权与许可证。
2. 调整项目名称、说明及必要入口，让用户能区分派生版本和上游项目。应用 ID/资源包的变化必须整体核对，不能只改显示名称后假定支持与上游同时安装。
3. 关闭 `.user.js` 导入、安装提示、管理入口、自动执行以及内置 `GM_*`/`GM.*` 权限调用；同时关闭旧扩展 stub 原型的运行入口。
4. 保留后续 Browser Adapter 所需的底层浏览器挂钩/CDP 基础；不能因旧代码文件名含 `UserScript` 就误删整个浏览器连接路径。
5. 提交本规范、阶段计划和兼容状态的初始版本。

验收：

- 正常页面导航、刷新和新开 tab 不执行已存储的内置用户脚本。
- `.user.js` URL、文件分享、页面菜单及原脚本管理入口都不能触发安装或执行；直接调用旧桥也不能绕过开关。
- 原脚本数据不被主动删除；关闭运行与删除数据分别处理。
- 旧扩展原型不能借调试或前端入口继续执行不安全的资源服务/stub。
- 构建及静态入口检查通过；实际设备结果单独登记，未实测不得写成已通过。

退出条件：上述变更可审查、可构建；剩余设备回归和已知限制在矩阵中清楚可见。

## M1：安装与管理基础

任务顺序：

1. 定义安装记录、稳定 ID、包版本、manifest 摘要、权限声明、状态和失败原因的数据模型。
2. 实现 ZIP 流式检查、资源限制、路径规范化、暂存及原子提交。
3. 实现 MV2/MV3 manifest 的基础类型及资源校验，输出按字段定位的错误。
4. 接入 Android 文件选择器和本地管理界面，显示来源、版本、权限和“当前无法运行”的具体原因。
5. 提供导入、列表、详情、禁用和卸载；启用操作必须经过能力检查，运行时尚未具备时明确拒绝。
6. 记录后续更新、权限增量确认和 CRX3 签名校验所需的数据，不提前宣称商店安装或自动更新可用。

验收输入：

| 类别 | 必测情况 |
| --- | --- |
| 正常包 | 最小 MV2/MV3 manifest、嵌套资源、UTF-8 文案、重复安装的身份处理 |
| manifest | 缺失/损坏 JSON、错误字段类型、非法版本、缺失入口、未支持的必需能力 |
| 路径 | `../`、绝对路径、反斜杠绕过、符号链接、重复规范化路径、文件与目录冲突 |
| 资源限制 | 超限条目、单文件/总体解压超限、虚假 ZIP 元数据、异常压缩比、中断导入 |
| 原子性 | 导入失败不留下已安装条目，替换失败不损坏旧版本，重启后列表一致 |
| UI 状态 | 导入成功与运行成功分开显示；禁用/卸载立即反映，错误可定位且可重试 |

退出条件：正常包能安全持久导入和管理；恶意/损坏输入不会逃出安装目录、消耗无界资源或留下半安装状态。此时仍不能宣称扩展能执行。

## M2：第一个真实 WebExtension

前置：规范中的技术实验 A 通过；技术实验 B 的来源、资源及页面隔离部分通过。

任务：

1. 为 CDP 补充请求关联、错误/超时、target/session 路由、context 注册与失效处理。
2. 建立扩展隔离世界、内容脚本匹配器和 document/frame 生命周期；实现准确的 `document_start/end/idle`。
3. 实现 `runtime.id/getManifest/getURL`、消息/端口及 `storage.local` 和相关事件。
4. 按 [Chrome 内原生 UI 设计](NATIVE_UI.md) 接入工具栏/菜单和浏览器内扩展管理，参考 Chromium desktop Android。M1 独立应用跳转不作为正式实现。
5. 加入 Chrome 内 popup/options 资源宿主、`action` 的必要行为和最小 `i18n`；同步实现模块私有包存储与 Chrome 宿主之间的认证通信。
6. 为多个扩展、网页伪造请求、未授权 frame/域名及资源读取编写有实际安全意义的反向测试。

演示扩展必须是标准 manifest 包，含真实 content script、popup、options、消息和存储，不依赖 ChromeXt 专用 API。使用至少两个独立扩展证明隔离；其中一个尝试越权读取另一扩展的数据或冒充其身份，必须失败。

退出条件：目标设备上的端到端行为通过，保存日志和版本信息。仅声明所实现的 API 与上下文；后台 worker 仍单独标为未支持。

## M3：MV3 后台与事件

前置：技术实验 B 的 worker 部分通过并形成架构决策。若没有可用宿主，先记录缺失原生能力；不能将 iframe 改名为 worker 后继续宣称完成。

任务：

1. 支持 `background.service_worker`、classic/module 脚本加载、错误报告及运行上下文限制。
2. 实现启动时监听器注册、空闲终止、事件唤醒和进程重启恢复。
3. 实现 `runtime.onInstalled/onStartup`、后台消息、`alarms`，以及需要的 `storage.session` 行为。
4. 处理 worker 消失时的待决 RPC、端口和事件投递；避免重连造成重复业务动作。
5. 为 MV2 background page/scripts/event page 单独记录进度，不以 MV3 通过推导 MV2 已支持。

验收：主动终止 worker 后，由消息和 alarm 再次唤醒；状态由持久存储恢复；没有 DOM 依赖；Chrome 进入后台、进程退出和重新启动后按声明语义恢复。测试结果需区分 Android 延迟限制与实现错误。

## M4：真实 Tampermonkey 与浏览器能力

任务顺序：

1. 固定 Tampermonkey 5.5.1 测试包的来源与校验值，提取 manifest 和真实 API 调用作为需求清单。
2. 实现所需 `scripting`、`userScripts`、`tabs`、`webNavigation`、`permissions`、`offscreen` 和浏览器 UI 适配。
3. 实现其实际使用的网络能力，包括需要的 `webRequest`/DNR、cookie/重定向语义及跨源权限；按方法、事件和规则类型验收。
4. 完成 popup/dashboard/设置、扩展自身脚本安装管理、存储、菜单和重启恢复流程。
5. 执行 [规范中的 Tampermonkey 专项](WEBEXTENSIONS_SPEC.md#8-tampermonkey-兼容专项)，记录未改写的扩展包和测试脚本版本。

网络权限测试必须包含请求被真正阻断/修改的服务器侧证据，以及未授权域名、重定向目标和 frame 的反向测试。仅有 API 函数名或 DNR 规则存储不算通过。

退出条件：完整专项通过且没有关键 API stub。若 dashboard 可以打开而脚本注册或网络失败，只能记录相应子项通过，不能标记“支持 Tampermonkey”。

## M5：扩大兼容并满足发布要求

任务：

- 生成和维护固定 Chrome 154 API/schema 清单，补齐当前核心矩阵未覆盖的命名空间、manifest 字段、方法、事件和上下文。
- 扩展 `bookmarks`、`history`、`downloads`、`cookies`、`contextMenus`、`commands`、`notifications`、开发者工具等能力；具体顺序由真实扩展需求和风险决定，状态逐项登记。
- 增加不同类型真实扩展的回归包：页面修改、内容拦截、标签管理、下载/书签及开发工具。每个包记录来源、版本、校验值和允许分发方式。
- 完善 CRX3、更新签名、权限增量、失败回滚、备份恢复及扩展数据迁移。
- 验证官方 Chrome 账号与浏览器同步继续工作；另行设计和实现 `storage.sync`，公开同步提供者和一致性限制。
- 完善普通/隐身范围、Android 多用户、后台限制、renderer 崩溃、设备重启及 Chrome 升级回归。
- 测量冷启动、导航开销、内存、电量和资源释放；制定基于真实设备数据的预算。

退出条件：发布声明能逐项对应已通过的兼容矩阵。只有核心 API 清单、语义差异、真实扩展和稳定性均达到完整目标时，才可以将产品称为完整扩展支持。

## 验证与开发环境

宿主是不可变 SteamOS。常规构建、依赖安装、生成器和测试统一使用现有 Distrobox `dev`；保持容器名称不变。在仓库目录执行：

```sh
distrobox enter -n dev -- env JAVA_HOME=/usr/lib/jvm/java-21-openjdk ANDROID_HOME=/home/deck/Code/Toolchains/android-sdk ./gradlew :extension-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

构建要求以仓库 Gradle 配置为准。缺失依赖在 `dev` 中处理，不在 SteamOS 宿主安装开发环境。Ubuntu/Debian/Fedora 兼容容器用于对应 Linux 分发验收，不替代当前 Android 构建环境。

验证分层：

1. 核心单元测试：重点验证 ZIP、manifest、权限决策、状态持久化及错误路径。
2. 运行时集成测试：标准扩展 fixture + 自有测试网页，覆盖隔离、消息、事件和资源访问。
3. 实机验收：固定 Android/Chrome/模块版本，记录 Xposed 加载、功能操作及故障日志。
4. 真实扩展回归：未经源码改写的扩展包及对应测试脚本。

每项验收记录日期、提交 SHA、构建类型、Android/Chrome 版本、测试包 SHA-256、操作步骤、预期、结果和证据路径。未执行、静态通过、自动化通过、实机通过分别记录；不能互相替代。

## 当前下一步

M0 的移除工作及 M1 的包管理基础已提交为首轮实现，验收范围见 [验证记录](VALIDATION.md)。模块框架 API 已按新要求迁移到 [现代 libxposed API 102](LSP_API_MIGRATION.md)；先在提供 API 102 的框架上完成 Chrome Hook 回归，再推进 Chrome 内原生 UI/认证存储桥及两项运行时技术实验。它们共同决定 M2/M3 的宿主方案，完整扩展执行仍未实现。
