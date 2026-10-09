# ChromeXt-Extensions：WebExtensions 运行时规范

版本：0.1 · 更新：2026-10-09 · 状态：实施草案，尚未实现扩展运行时。

项目：<https://github.com/ccc007ccc/ChromeXt-Extensions>

开发分支：`feature/webextensions-runtime`

上游基线：ChromeXt `chromium-compat`，`0a6253bb975f3bd868df1521cba039c40b9003ad`。

实施顺序见 [IMPLEMENTATION_PLAN.md](IMPLEMENTATION_PLAN.md)，实际支持情况以 [COMPATIBILITY.md](COMPATIBILITY.md) 为准。本文中的“必须”“应”是验收要求，不表示当前实现已经满足。

## 1. 产品目标

用户要求的是在现有官方 Android Chrome 上增加完整 Chrome 扩展支持，并关闭 ChromeXt 自带的油猴/用户脚本功能。项目的长期目标是运行真实、未经改写的 WebExtensions 扩展包，包括扩展自己的管理界面、内容脚本、后台、权限及浏览器 API；不能把几段网页注入脚本或一个 `window.chrome` 对象作为目标的替代品。

内置的 `.user.js` 安装、脚本自动执行及 `GM_*`/`GM.*` 桥接必须关闭。未来实现 `chrome.userScripts` 是为真正的 Tampermonkey 等扩展提供浏览器 API，和恢复 ChromeXt 内置脚本管理器是两件事。最终的 Tampermonkey 验收必须使用真实扩展自身的安装、管理及执行流程。

正式扩展 UI 必须集成在 Chrome 内，参考 Chromium Android 桌面构建的扩展入口、菜单、弹窗、权限提示及管理交互。Chrome 中点击“扩展”后跳转独立模块应用，仅属于 M1 临时验证工具，不满足正式 UI 验收。模块独立应用最终只承担模块配置、诊断及备用维护入口。详见 [Chrome 内原生 UI 设计](NATIVE_UI.md)。

“完整支持”是长期完成标准，不能用于描述 M0、安装器完成或少数演示扩展可运行的状态。应建立 Chrome 154 公开扩展 API 清单，逐步核对 API、事件、执行上下文、manifest 字段和生命周期语义。桌面 UI 或平台专有能力需要明确的 Android 实现方案或公开记录的限制；不能把尚未实现的能力从统计范围中静默移除。

## 2. 当前事实与目标平台

| 项目 | 当前结论 |
| --- | --- |
| 首要目标 | Android 16、官方 Chrome `154.0.8037.127` |
| 浏览器基础 | 保留官方 Chrome 应用、账号与现有浏览器数据，不以替换浏览器为当前实施前提 |
| 现有能力证据 | API 102 已在目标 Chrome 加载；原生包管理、认证 IPC 和 10 项 CDP 实验有实机结果。实验不等于扩展运行时完成，见 [验证记录](VALIDATION.md) |
| 上游扩展原型 | `LocalFiles.kt` 读取目录及 `background.page`，`extension.js` 加载 `StubChrome`；不构成可用的 WebExtensions 引擎 |
| 普通 Android Chrome | 不能假设已包含桌面 Chrome 的完整扩展后端；通过 Xposed/CDP 构建兼容层的可行性仍需实际验证 |
| 当前交付阶段 | M0/M1 基础已实现；M2 原生包管理与认证通信已实现，A/B 实验已取得部分结果；正式内容脚本/API/页面宿主及 M3–M5 尚未实现 |
| 其他浏览器/版本 | 尚未认证，不能继承上游 README 的兼容结论 |

扩展运行时失效时应禁用相关扩展能力并给出原因。不得为了继续执行扩展而破坏 Chrome 的普通浏览、账号登录或数据完整性。

## 3. 必须先验证的两个技术实验

### A. 独立执行上下文与页面生命周期

目标：证明模块能在目标 Chrome 上，为每个扩展、标签页及 frame 建立可靠的内容脚本上下文。

实验必须覆盖：

- 模块实际加载、目标发现、CDP 请求/响应及事件持续接收。
- `document_start` 在网页首段脚本之前生效；不能仅凭导航结束后的注入判定通过。
- `ISOLATED` 世界与网页主世界的全局对象隔离，网页不能调用扩展特权桥；需要 `MAIN` 世界的显式 API 调用不能泄露特权。
- 顶层 frame、同源及跨源 iframe、OOPIF、新建/销毁 frame、重定向、SPA 导航、刷新和返回前进缓存。
- 从浏览器提供的 target/session/execution context 信息建立身份，导航后使旧身份和待决调用失效；不能相信 JavaScript 请求中自报的 `extensionId`、`tabId` 或 `frameId`。

输出：可重现的测试扩展、测试网页、日志、目标版本和架构决策记录。未通过时不得开放任意第三方扩展执行。

### B. 扩展来源与后台宿主

目标：证明扩展资源、扩展页面及 MV3 worker 可以在独立且稳定的安全边界中工作。

实验必须覆盖：

- 扩展之间及扩展与网页之间的 origin 隔离；扩展 URL、相对资源、CSP、`web_accessible_resources` 和跨源请求规则。
- `chrome-extension://<id>/` 的原生实现可能性；若只能使用替代来源，记录对 `location.origin`、URL 判断、CSP、导航和真实扩展的影响，不得将 URL 字符串替换视为等价实现。
- popup/options/background 的可信上下文，以及管理界面和扩展页面的权限分离。
- MV3 classic/module service worker 的启动、终止、重新唤醒、无 DOM 环境及事件注册。隐藏 tab、iframe、普通 Web Worker 均不能直接算作扩展 service worker。
- 浏览器切至后台、renderer 丢失、Chrome 进程重启后的恢复。

若普通 Chrome 的现有接口不能提供所需语义，应在架构决策中列出需要新增的原生能力、可接受的兼容适配和确实不能实现的部分。保持完整扩展目标，同时公开阻塞项；不能以常驻页面和空 API 伪造通过。

## 4. 架构边界

以下是职责划分，不表示已经存在同名代码。

| 组件 | 职责与约束 |
| --- | --- |
| 安装与注册中心 | 校验包、生成稳定身份、维护版本/启用/权限/兼容状态，原子安装与更新 |
| 能力注册中心 | 按浏览器版本、manifest、API 方法、事件、上下文登记真实能力及原因 |
| Browser Adapter | 包装 Chrome 挂钩与 CDP；统一 tab/frame/document 生命周期，避免 API 实现直接依赖混淆类名 |
| Context Registry | 由宿主登记扩展身份、profile、tab/frame/document、执行世界及会话代次 |
| API Broker | 对每次调用验证上下文、权限和目标；处理请求关联、回调、事件、超时及会话销毁 |
| 资源服务 | 校验规范化路径、来源和可访问性，设置准确 MIME/CSP；不提供通配跨域读取 |
| 扩展宿主 | 分别管理 popup/options、MV2 background 和 MV3 worker；实现生命周期而非仅加载 HTML |
| 存储与同步 | 分扩展、浏览器 profile、普通/隐身范围隔离；区分持久存储、会话存储和远端同步 |
| 管理界面 | 正式 UI 位于 Chrome 内，参考 Chromium desktop Android；展示权限、实际兼容状态、错误及启用/禁用/卸载。M1 独立 Activity 只用于临时验证 |

可以复用上游的浏览器发现和底层通信经验，不能把旧用户脚本桥作为新的权限模型。旧原型中的远程 `StubChrome`、无鉴权资源服务和宽泛 CORS 不能作为生产实现的基础。

## 5. 安装、manifest 与扩展身份

### 5.1 安装流程

首阶段接受本地 ZIP，通过 Android 文件选择器导入。导入、校验、权限确认、启用是可区分的状态。后续增加开发者解包目录、CRX3 校验、更新和商店获取；M1 完成不等于 Chrome 网上应用店安装已经可用。

安装器必须：

- 在受控暂存目录解包，拒绝绝对路径、路径穿越、反斜杠绕过、符号链接、重复/冲突路径和非法文件名。
- 限制压缩包大小、实际解压总量、单文件大小、条目数及异常压缩比；按实际读取量计数，不能仅信任 ZIP 头声明。
- 校验根目录 `manifest.json`、JSON 类型、版本、入口文件及资源路径；错误包含字段或条目位置。
- 将校验通过的数据原子提交；失败保留旧版本和已有设置，清理暂存文件。
- 不执行包中代码来“探测是否能安装”。包、manifest 文案和图标始终按不可信输入处理。

扩展 ID 在升级和重启后保持稳定。签名包遵循可验证的发布者身份；普通 ZIP 使用已记录的安装身份。仅在 manifest 中自报 `key` 或相同名称，不能授权覆盖已有扩展。更新时需检查身份、权限变化及回滚条件。

### 5.2 支持判定

为 MV2 与 MV3 分别校验 manifest 规则；优先推进 MV3，MV2 的 background page/scripts/event page 单独记录支持状态。

声明了尚未实现的必需能力时，必须拒绝启用并列出原因。可以保留为“已导入，因兼容性禁用”的条目，不能展示成“已运行”。不支持的 optional permission 可以保持未授权并告知扩展不可获取。

仅解析 manifest 无法证明代码不会调用其他 API。运行时遇到未支持的方法、事件或上下文，应按可诊断的错误路径报告；不能返回空对象、假成功或永不派发的事件作为实现。未知普通 manifest 字段可以按目标 Chrome 规则给出警告，不应把所有未知字段都误判为致命错误。

## 6. 权限与隔离

每次特权调用由宿主验证至少以下信息：扩展 ID、安装/启用状态、浏览器 profile、普通/隐身范围、执行上下文类型、当前 document、已授予权限和目标资源。

- 网页提供的消息、DOM 事件、全局变量和任意 localhost 请求不能证明扩展身份。敏感桥只能面向由宿主验证的扩展上下文。
- 内容脚本依扩展分别使用隔离世界。不同扩展不得读取对方的 JS 全局、存储或消息。
- `host_permissions`、`permissions`、optional permissions、用户暂缓授予和撤销均需真实执行。
- `activeTab` 绑定实际用户手势、指定 tab 和有效导航范围，不能转化为永久 `<all_urls>`。
- `matches`、`exclude_matches`、`all_frames`、`match_about_blank`、`match_origin_as_fallback` 等按声明范围生效，跨源 frame 不继承无限权限。
- 扩展私有资源不能被任意网站读取；对外资源必须经过对应 manifest 的 `web_accessible_resources` 规则。
- 保持网页 CSP、浏览器安全检查和其他扩展的边界；不能通过全局关闭 CSP/CORS 实现某个扩展 API。
- 默认不在隐身环境执行。后续提供用户授权和相应隔离模型；未实现时明确拒绝该能力。
- 扩展不能通过 API Broker 获得任意 shell、任意文件路径、任意 CDP 命令或管理器操作权限。

禁用或卸载必须停止新注入、后台任务及事件投递，断开端口并撤销授权。对已产生的页面 DOM 修改应说明恢复需要刷新，不能声称可以自动撤销任意 JavaScript 副作用。

## 7. 核心运行语义

### 7.1 内容脚本与消息

内容脚本必须遵循实际匹配范围、执行时间、注入顺序、CSS/JS 类型及 frame/document 生命周期；同一扩展不应因重复挂钩而在同一 document 重复注入。

`runtime.sendMessage`、`runtime.connect`、`tabs.sendMessage` 等必须提供正确的发送者和接收目标，支持无接收方、异步响应、端口断开和页面消失的行为。消息序列化、Promise/callback、`runtime.lastError` 的可见时间按固定目标版本验证，不能用一个返回 Promise 的通用包装假定兼容所有方法。

### 7.2 扩展页面与存储

popup、options 和独立管理页运行扩展自己的 HTML/JS/CSS，并享有其上下文允许的 API。移动端弹窗须由 Chrome 内 UI 承载，参考 desktop Android 的 anchor/popover 与手机窄屏适配；关闭、焦点、tab 关联、主题和事件语义必须明确。独立应用 Activity 或外部网页不能代替这项验收。

`storage.local` 提供分扩展持久存储、配额和 `onChanged`；`storage.session` 的清除及访问级别单独实现。扩展更新和 Chrome 重启后应按区域语义恢复，不能把不同扩展映射到共享键空间。

### 7.3 MV3 后台

MV3 后台必须遵循事件驱动的 worker 生命周期，支持脚本/module 加载、顶层事件注册、启动失败报告、终止及重新唤醒。事件传递顺序、重连重复投递、待决响应和任务中断必须有明确处理。

`alarms`、`runtime.onInstalled`、`runtime.onStartup`、消息和浏览器事件按对应阶段实现。Android 休眠和后台限制下的延迟与丢失必须实测；不得靠无限保活规避所有生命周期问题。

## 8. Tampermonkey 兼容专项

首个复杂扩展目标为 Tampermonkey `5.5.1`。验收前记录包来源、版本、manifest、包 SHA-256 及许可证/分发条件，保持扩展包未经源码改写。开发用最小扩展通过不能代替该专项。

需要核对其真实 manifest 和调用路径，重点包括：`userScripts`、`scripting`、`offscreen`、`tabs`、`webNavigation`、网络相关 API/DNR、后台 worker、权限以及扩展管理页面。以实际版本的调用结果维护差距清单，不假设实现几个命名空间即可启动。

专项验收至少包括：

1. 通过扩展管理器安装真实包，显示能力与权限，后台成功启动且没有用 stub 掩盖的错误。
2. 打开 Tampermonkey 自身的 popup、dashboard 和设置页面。
3. 通过 Tampermonkey 自身流程安装、启用、修改、禁用和删除一个测试用户脚本。
4. 测试不同注入时机、frame、内容隔离、持久化及脚本菜单。
5. 测试经权限许可的跨源网络、重定向、cookie 与请求修改路径；用拒绝权限和未授权域名作为反例。
6. 重启 Chrome、后台恢复和 worker 被终止后，验证脚本注册和设置仍正确。
7. 同时确认 ChromeXt 内置 `.user.js` 安装和 GM 引擎仍关闭。

`userScripts` 实现必须有扩展权限和执行世界控制；网络实现不能把扩展请求授权扩大成所有网页均可跨源访问。DNR 的匹配、优先级、规则类型及实际请求拦截需要网络层证据，不能只维护一份规则 JSON 就称为支持。

## 9. Google 同步、数据与更新

保留官方 Chrome 的 Google 登录、书签/历史/密码等浏览器同步能力，是宿主回归验收项。测试采用既有账号状态及用户提供的测试数据，不需要读取账号凭据或把 Chrome 私有数据库直接当扩展 API。

扩展的 `chrome.storage.sync` 是另一项能力，需要独立决定同步提供者、授权、配额、离线队列、冲突和恢复语义。不能将 `storage.local` 重命名为 `storage.sync`，也不能因为 Chrome 已登录 Google 就宣称模块可接入 Chrome 扩展同步。扩展安装列表的跨设备同步同样单独登记。

Chrome 升级后重新执行能力探测。挂钩不匹配时给出受影响功能和受测版本，不静默向未知内部方法继续写入。模块升级、扩展升级和数据迁移均需保证失败可回滚；不主动删除原 ChromeXt 的用户脚本数据。

## 10. 完成与发布标准

- 每阶段都有可重复的正向和反向验收，结果写入兼容矩阵并关联代码版本。
- 单元测试、构建成功、静态 APK 检查与真实设备验收分开记录。
- 安装器版本只能声明安装器能力；实验运行时只能声明通过的 API/扩展/Chrome 版本。
- 完整扩展支持声明需要 API 清单的覆盖核对、关键语义测试、真实扩展测试和生命周期回归共同支撑。
- 未通过的能力保持禁用或明确报告不支持。不存在“为了让安装成功而默认返回成功”的兼容策略。

## 11. 参考资料

- [Chrome 扩展文档](https://developer.chrome.com/docs/extensions/)
- [Chrome 扩展 API 参考](https://developer.chrome.com/docs/extensions/reference/api)
- [Manifest 参考](https://developer.chrome.com/docs/extensions/reference/manifest)
- [内容脚本与隔离世界](https://developer.chrome.com/docs/extensions/develop/concepts/content-scripts)
- [权限声明](https://developer.chrome.com/docs/extensions/develop/concepts/declare-permissions)
- [扩展 service worker 生命周期](https://developer.chrome.com/docs/extensions/develop/concepts/service-workers/lifecycle)
- [消息传递](https://developer.chrome.com/docs/extensions/develop/concepts/messaging)
- [chrome.userScripts](https://developer.chrome.com/docs/extensions/reference/api/userScripts)
- [chrome.storage](https://developer.chrome.com/docs/extensions/reference/api/storage)
- [chrome.declarativeNetRequest](https://developer.chrome.com/docs/extensions/reference/api/declarativeNetRequest)
- [Chrome DevTools Protocol](https://chromedevtools.github.io/devtools-protocol/)
- [Tampermonkey 文档](https://www.tampermonkey.net/documentation.php)
- [ChromeXt 上游基线](https://github.com/JingMatrix/ChromeXt/tree/0a6253bb975f3bd868df1521cba039c40b9003ad)

在线文档会更新。实施时应记录核对日期和目标 Chromium/Chrome 版本，并尽可能保存对应版本的 schema 或源文件引用，不能将最新在线文档自动视为 Chrome 154 的精确行为。
