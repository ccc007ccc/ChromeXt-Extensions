# ChromeXt-Extensions 兼容状态

更新：2026-10-09。此表记录实现与证据，不是功能承诺。计划见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)，行为要求见 [WEBEXTENSIONS_SPEC.md](WEBEXTENSIONS_SPEC.md)。

当前没有经过实机验收的 WebExtensions 运行时；不能据此安装器或上游 stub 推导 Chrome 扩展、MV3 或 Tampermonkey 已可用。

## 状态定义

| 状态 | 含义 |
| --- | --- |
| 未实现 | 只有需求或计划，无可用实现 |
| 进行中 | 正在实现，尚未达到阶段验收 |
| 未验证 | 尚无对应环境或扩展包的验证记录 |
| 实验 | 有验证原型，未达到对第三方扩展开放条件 |
| 部分支持 | 明确列出的子集有证据，其他语义/上下文仍缺失 |
| 已验证 | 指定版本、上下文和测试范围通过，不代表整个命名空间都支持 |
| 不支持 | 当前产品不提供该能力；应报告原因或拒绝启用 |
| 待实测 | 已有静态检查或实现证据，尚无目标设备结果 |

“待实测”可作为实现状态的验证补充。`已验证` 必须同时给出证据，不以函数存在、返回成功或单纯 APK 构建成功为依据。

本轮构建和测试结果见 [VALIDATION.md](VALIDATION.md)。正式 UI 必须位于 Chrome 内，参考 [NATIVE_UI.md](NATIVE_UI.md)；当前独立管理 Activity 不算原生扩展 UI 已完成。

## 平台与基础能力

| 项目 | 状态 | 证据与限制 |
| --- | --- | --- |
| 派生基线 | 已确认 | 上游 `chromium-compat`，`0a6253bb975f3bd868df1521cba039c40b9003ad` |
| LSP API | 已迁移；Hook 待实测 | 仅现代 `libxposed:api:102.0.0`；最低/目标 API 102。当前手机框架实际为 API 101，需升级后验收；见 [迁移记录](LSP_API_MIGRATION.md) |
| 目标 Android | 待实测 | Android 16 为首要目标，完整模块验收尚未记录 |
| 目标 Chrome | 待实测 | 官方 `154.0.8037.127`；静态 APK 检查发现部分所需接口，不等于挂钩成功 |
| 内置用户脚本/GM 关闭 | 进行中 | M0 要求；入口、执行和桥接均需核验 |
| 上游扩展原型 | 不支持 | `LocalFiles.kt`/`extension.js` 只提供有限原型与 stub，不能启用为正式引擎 |
| ZIP 导入与管理 | 已实现包管理基础 | MV2/MV3 包元数据、审阅、原子导入、持久列表与卸载；不执行扩展，完整文件选择器流程待实机验收 |
| MV2 内容脚本/background | 未实现 | 安装器解析 MV2 不代表可执行 |
| MV3 内容脚本/service worker | 未实现 | 需要隔离及后台宿主技术实验 |
| `chrome-extension://` 来源 | 未实现 | 原生实现或替代来源的语义差异需先验证 |
| Chrome 网上应用店/CRX3 | 未实现 | ZIP 导入不包含商店接入、签名校验和自动更新 |
| 其他 Chrome 版本/浏览器 | 未验证 | 不继承上游所列兼容范围 |

## 核心 API 矩阵

上下文：C = 隔离内容脚本，E = 扩展页面（popup/options/管理页），B = 后台页面或 worker。实际公开范围仍需服从 Chrome 154 的 API/context 规则；表中上下文为计划核对范围。

同一行的方法将在实施时拆开记录验证结果，不能因其中一个方法通过就把整行标为已验证。以下仅是首批核心清单，未列出的 API 不表示已支持或已从完整目标中排除。

| API / 方法或事件 | 上下文 | 阶段 | 当前状态 / 必要验收 |
| --- | --- | --- | --- |
| `runtime.id`、`getManifest()`、`getURL()` | C/E/B | M2 | 未实现；身份、清洁 manifest 及资源 URL |
| `runtime.sendMessage()`、`onMessage` | C/E/B | M2 | 未实现；发送者、异步响应、无接收者及权限 |
| `runtime.connect()`、`onConnect` | C/E/B | M2 | 未实现；端口隔离及连接路由 |
| `Port.postMessage()`、`disconnect()`、`onMessage`、`onDisconnect` | C/E/B | M2 | 未实现；导航、禁用及后台丢失后的断连 |
| `runtime.lastError`、Promise/callback 错误 | 对应 API | M2 起 | 未实现；按方法核对目标版本的错误语义 |
| `runtime.openOptionsPage()` | E/B | M2 | 未实现；实际打开扩展自己的设置页 |
| `runtime.onInstalled`、`onStartup` | B | M3 | 未实现；安装/更新/浏览器启动与 worker 唤醒 |
| `storage.local.get/set/remove/clear/getBytesInUse` | C/E/B | M2 | 未实现；持久化、隔离、配额和失败原子性 |
| `storage.onChanged` | C/E/B | M2 | 未实现；区域、差异与跨上下文事件 |
| `storage.session`、访问级别相关方法 | 按授权 | M3 | 未实现；生命周期与内容脚本访问控制 |
| `storage.sync` | 按授权 | M5 | 未实现；不映射为 local，不自动接入 Google 同步 |
| `i18n.getMessage()`、`getUILanguage()` | C/E/B | M2 | 未实现；locale fallback、占位符及字符串结果 |
| `action.setIcon/setTitle/setBadgeText/setBadgeBackgroundColor` | E/B | M2 起 | 未实现；tab 范围和 Android 显示适配 |
| `action.setPopup()`、`onClicked` | E/B | M2 起 | 未实现；弹窗与点击事件的对应行为 |
| `scripting.executeScript()`、`insertCSS()`、`removeCSS()` | E/B | M4 | 未实现；目标 frame/document、world 与权限 |
| `scripting.registerContentScripts/getRegisteredContentScripts/updateContentScripts/unregisterContentScripts` | E/B | M4 | 未实现；动态注册及持久化 |
| `userScripts.register/getScripts/update/unregister` | E/B | M4 | 未实现；真实扩展授权、注册与执行 |
| `userScripts` 世界配置及消息能力 | 按版本规则 | M4 | 未实现；先核对 Chrome 154 schema，不能借旧 GM 桥替代 |
| `tabs.query/get/create/update/remove` | E/B | M4 | 未实现；稳定 tab 标识、受限字段与 Android tab model |
| `tabs.sendMessage()`、`connect()` | E/B | M4 | 未实现；frame/document 定向和接收方边界 |
| `tabs.onCreated/onUpdated/onActivated/onRemoved` | E/B | M4 | 未实现；实际浏览器事件与销毁处理 |
| `webNavigation.onBeforeNavigate/onCommitted/onDOMContentLoaded/onCompleted` | E/B | M4 | 未实现；多 frame、重定向及事件时序 |
| `webNavigation.onHistoryStateUpdated/onReferenceFragmentUpdated` | E/B | M4 | 未实现；SPA 和 fragment 导航 |
| `webNavigation.getFrame/getAllFrames` | E/B | M4 | 未实现；OOPIF、document 与父子关系 |
| `permissions.contains/getAll/request/remove`、`onAdded/onRemoved` | 按方法规则 | M4 | 未实现；用户手势、optional 权限及即时撤销 |
| `alarms.create/get/getAll/clear/clearAll`、`onAlarm` | E/B | M3 | 未实现；后台唤醒与 Android 延迟 |
| `offscreen.createDocument/closeDocument/hasDocument` | 按版本规则 | M4 | 未实现；理由、单例约束和独立生命周期 |
| `declarativeNetRequest.updateDynamicRules/getDynamicRules` | E/B | M4 | 未实现；实际规则匹配/执行和配额 |
| `declarativeNetRequest.updateSessionRules/getSessionRules` | E/B | M4 | 未实现；会话生命周期和请求处理 |
| `declarativeNetRequest.updateEnabledRulesets/getEnabledRulesets` | E/B | M4 | 未实现；静态规则集和状态恢复 |
| `webRequest` 所需方法/事件及 blocking 能力 | E/B | M4 起 | 未实现；需固定 MV2/MV3 权限限制与网络层方案 |
| `cookies.get/getAll/set/remove`、`onChanged` | E/B | M4/M5 | 未实现；cookie store、域/路径、SameSite 及权限 |
| 扩展页面/后台跨源 `fetch`/XHR | E/B | M4 | 未实现；host 授权、重定向及浏览器网络语义 |
| `contextMenus.create/update/remove/removeAll`、`onClicked` | E/B | M4/M5 | 未实现；Android 菜单及 frame 信息 |
| `downloads`、`bookmarks`、`history`、`notifications`、`commands` 等 | 待 API 清单细化 | M5 | 未实现；命名空间整体不能算作覆盖单元 |

## Manifest 与运行语义

| 能力 | 当前状态 | 门槛 |
| --- | --- | --- |
| Manifest 基础解析 | 进行中 | 字段类型/版本/入口文件验证，不执行扩展 |
| 未支持的必需能力报告 | 进行中 | 阻止启用并列出原因；optional 能力单独处理 |
| `content_scripts` 匹配与时机 | 未实现 | `matches/exclude_matches/run_at/all_frames` 等逐项验收 |
| ISOLATED / MAIN world | 未实现 | 技术实验 A；MAIN 不获得特权桥 |
| popup / options | 未实现 | 私有来源、真实资源、权限与生命周期 |
| MV2 background page/scripts/event page | 未实现 | 按类型单独验收 |
| MV3 classic/module service worker | 未实现 | 技术实验 B；事件驱动的启动/终止/唤醒 |
| CSP / `web_accessible_resources` | 未实现 | 私有资源默认不可读取，不能通配 CORS |
| 多扩展/多 frame/OOPIF | 未实现 | 身份、消息、数据和导航边界 |
| 隐身执行 | 不支持 | 后续需独立授权和隔离模型；当前不得默认运行 |
| Chrome Google 浏览器同步 | 待实测 | 保持原浏览器功能是回归项，不代表扩展同步 |
| 扩展 `storage.sync` / 安装列表同步 | 未实现 | 独立提供者、授权与冲突设计 |

## 真实扩展矩阵

| 扩展 | 当前状态 | 完成判据 |
| --- | --- | --- |
| 最小标准 WebExtension fixture | 未实现 | 内容脚本、消息、popup/options、存储及隔离反例通过 |
| MV3 worker fixture | 未实现 | worker 终止、事件唤醒、错误及重启恢复通过 |
| Tampermonkey 5.5.1 | 不支持 | 当前运行时未实现；需真实原包及自身管理流程通过完整专项，仅页面能打开不算支持 |
| 其他公开扩展 | 未验证 | 后续逐包记录来源、版本、校验值、许可和功能结果 |

未来 `chrome.userScripts` 的实现用于真实 Tampermonkey 等扩展，不代表重新启用 ChromeXt 的内置用户脚本/GM 功能。

## 完整 API 清单的后续工作

当前表不是完整 Chrome API inventory。M2 开始应从固定 Chromium/Chrome 154 版本的公开扩展 schema 和文档生成可审查清单，至少记录：

- 来源版本/commit、生成命令、生成时间及人工补充来源。
- 命名空间、方法、事件、属性、参数/返回类型、manifest 字段和版本限制。
- 权限、上下文、MV2/MV3、平台/策略限制和依赖能力。
- 实现状态、对应代码、测试范围、设备版本及已知语义差异。

覆盖率的分母和排除原因必须可见。采用 Android UI 适配的能力、平台确实不适用的能力和未实现的能力分别记录，不能通过缩小清单宣称“全部兼容”。

## 验收记录

本文档创建时尚未写入本轮构建及设备验收结果。主实施线程在完成检查后追加实际记录，不将计划预填为通过。

建议每条记录使用以下字段：

```text
日期 / 代码提交：
阶段 / 能力：
验证级别：静态 / 单元测试 / 集成测试 / 实机 / 真实扩展
环境：容器、Android、Chrome、模块构建类型及 Xposed 框架版本
扩展包：来源、版本、SHA-256（适用时）
步骤 / 预期 / 实际结果：
证据：日志、命令输出或测试报告路径
限制 / 未执行项：
```

动态设备结论必须来自实际运行。APK 接口存在、Gradle 构建成功和 manifest 导入成功都不能替代该结论。
