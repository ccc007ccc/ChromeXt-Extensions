# Chrome 154 运行时实验 A/B

日期：2026-10-09。实现：[`72331c1`](https://github.com/ccc007ccc/ChromeXt-Extensions/commit/72331c1)，0.3.0-dev。环境：Android 16、官方 Chrome 154.0.8037.127（Chromium revision `4ed32028bcb2d00bc07a8880eb85aca80c9c5ec6`）、LSPosed 2.2.1 (7912) / API 102。

结论：执行世界与部分生命周期已有可重复的实机证据；扩展来源/注册和 MV3 后台宿主仍未建立。M2/M3 门槛尚未完整通过，任意第三方扩展执行继续关闭。

## 可重复输入与工具

- [两份 manifest/source fixture](../tests/runtime-fixtures/) 保持标准扩展目录结构。当前由探测脚本读取内容脚本，在 CDP 创建的两个隔离世界中执行；并非由模块扩展运行时安装和启动。
- [probe-runtime.mjs](../tools/probe-runtime.mjs) 自动启动仅在本机监听的测试 HTTP 服务，设置临时 ADB forward/reverse，创建自己的标签页，只对该 target 及子 frame 操作，结束后关闭该 target 并移除端口映射。
- [cdp-probe-client.mjs](../tools/cdp-probe-client.mjs) 是 Node 测试传输，提供请求编号、会话匹配、超时和关闭错误。它不是已完成的 Android 生产传输，不能替代模块原有 WebSocket 的后续改造。
- 执行命令见 [DEVICE_TESTING.md](DEVICE_TESTING.md)，原始结果见 [runtime-probe.json](evidence/2026-10-09/runtime-probe.json)。

## 实验 A 的结果

| 检查 | 实际结果 | 证据边界 |
| --- | --- | --- |
| 请求、响应和持续事件 | 通过 | 9 个执行上下文、页面事件和 2 个 session |
| `document_start` | 通过测试页面 | 顶层、同源 iframe、跨源 iframe 的首段脚本立即派发事件，两个预先注册的隔离世界监听器均已存在；不是导航完成后补注入 |
| 世界隔离 | 通过测试边界 | A/B 各自的全局值互不可见，MAIN 看不到它们；MAIN/B 无法调用只注册在 A 世界的 binding |
| 宿主身份 | 通过探测 | A 的 payload 自称 B，浏览器提供的 session/context uniqueId/frameId 仍将事件定位到 A。生产 Context Registry/API Broker 尚待实现 |
| OOPIF | 实际观察到 | 跨源 frame 使用第二个 session，在恢复执行前安装世界与 binding |
| 动态 frame | 通过 | 创建时早期注入；删除后登记移除，使用旧 uniqueContextId 被浏览器拒绝 |
| SPA | 通过 | `pushState` 保持 document 身份并收到 `navigatedWithinDocument` |
| 重定向、旧调用 | 通过 | 旧上下文不可再执行；跨导航的未决 Promise 请求返回 `Inspected target navigated or closed` |
| 刷新 | 通过 | 新 document 身份，首段脚本再次观察到两个世界均已准备 |
| 返回与 BFCache | 观察到实际恢复 | `pageshow.persisted=true`，页面及标记恢复；尚未覆盖扩展端口、后台和权限撤销的恢复语义 |

这些结果支持继续实现 Android 的生产会话/上下文适配层。仍需补齐任意新标签的首次导航拦截、后台冻结恢复、renderer 丢失和 Chrome 进程重启，以及生产身份失效与权限检查。不能把这批探测当作实验 A 的完整退出验收。

## 实验 B 的结果与决策

普通网页的跨源读取反例被 CORS 阻断，未关闭浏览器的 CSP/CORS 检查。随后在专用 target 导航到未注册的测试地址 `chrome-extension://aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/popup.html`：

- 直接导航和启用限定 URL 的 `Fetch` 拦截后重试，均返回 `net::ERR_ABORTED`。
- `Fetch.requestPaused` 数为 0，fixture 的 popup JS 未加载，页面仍保留原测试 HTTP origin。
- 尚未建立扩展注册、独立页面来源、CSP/资源可访问性规则或 MV3 worker host；没有用 iframe、普通 worker 或常驻 tab 代替后台。

因此，**仅用 CDP Fetch 为未注册扩展 URL 提供文件的方案不成立于这次实验**。这不证明所有原生实现路径均不可行：未注册 ID 在具备扩展系统的浏览器中也可能被拒绝。下一步必须检查固定版本 APK 的扩展注册/宿主后端及相应编译开关，明确需要新增的原生能力，再验证来源、扩展页面与 classic/module MV3 worker。替代 HTTP origin 如被考虑，须单独列出 location、CSP、权限、导航及真实扩展兼容差异。

## 额外协议描述查询的崩溃记录

在初次 10 项检查通过后，补加 `GET /json/protocol` 枚举探测。该请求期间连接关闭，设备记录 Chrome 主进程 `SIGTRAP` 原生崩溃；`libchrome.so` Build ID 为 `64855ac038c8e015dc30743c123443c6c9fb538b`。见 [记录](evidence/2026-10-09/protocol-discovery-failure.json)。

未做关闭模块的对照实验，当前不能将根因归属为独立 Chrome 缺陷。已从探测脚本移除此请求；模块 HTTP helper 也只允许 `/json` 和 `/json/version`。恢复 Chrome 后，10 项已定义检查重新全部通过，自己的测试 target 正常关闭。协议 schema 后续从固定版本源码离线核对，根因放到隔离设备/配置中定位，不在用户浏览器上重复触发。

## 下一阶段实施边界

1. 在 Android 端实现生产 CDP 请求关联、事件路由、会话代次和 context registry，覆盖首次导航与进程恢复。
2. 验证原生扩展注册、资源及页面宿主；只有来源边界通过后才接入 popup/options 和扩展 API。
3. MV3 worker 启停、唤醒、无 DOM 和事件语义单独验收。当前没有 worker 支持声明。
4. 保持官方 Chrome 与现有用户数据；`storage.sync`、Google 浏览器同步回归和 Tampermonkey 专项仍依原 spec 单独推进。
