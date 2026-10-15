## Notdisk v0.3.0

本次新增第二个真实网盘接入：**百度网盘**。产物为 debug 签名，**Android 15+ / minSdk 35**。

### 1. 新增百度网盘（`provider:baidu`）

依据对 `pan.baidu.com` 网页版的实测抓取文档。**10 个端点已实现并测试，97 个新测试**。

这是「新增网盘不修改公共流程」这条架构约束的第一次实战检验：接入过程中
`core:provider-api`、`core:model`、`core:domain`、任何 feature 模块与播放器**都没有改动一行**，
`ProviderRegistryModule` 也没有。唯一的注册动作是 `provider/baidu/.../di/BaiduProviderModule.kt`
里的一次 `@IntoSet`。

**登录方式：粘贴浏览器 Cookie，不需要任何开发者凭据。** 浏览器登录百度网盘后，从开发者工具复制
`Cookie` 请求头粘贴进来即可（需含 `BDUSS`）。凭据会**先向百度校验、通过后才**写入本机
Android Keystore 加密存储，因此失效的 Cookie 不会留下一个「看似已绑定、首次点击才失败」的账号。

### 2. 三个必须与 115 不同的设计决定

第二个网盘的价值不在于又多了一个入口，而在于它证明了契约抽象的是**行为**而不是**协议**。
三处差异值得单独说明：

**2.1 `FileId` 编码的是绝对路径，不是 `fs_id`。**

百度 Web API 是**按路径寻址**的：`filemetas` 的 `target`、`filemanager` 的 `filelist`、
`streaming` 的 `path` 全部收绝对路径。`fs_id` 存在且稳定，但这些端点不接受它。
所以百度的文件标识形如 `file:file:baidu:/我的文件/a.txt`。

好处是**同一文件无论从列表、分类、搜索还是详情发现，标识逐字节相同** —— 这正是 115 接入踩过
`pickcode` 坑的地方（同一文件两个身份）。另一个好处是 `getPath` 变成**零请求**的纯推导。

代价也如实记录：**重命名或移动会让被改名项及其子树的标识失效**。这是正确的结果
（旧标识确实不再指向任何东西），但意味着缓存要按路径前缀失效。

**2.2 播放不需要鉴权头 —— 因此刻意不发。**

115 的 CDN 要求 `Referer`/`Origin`/`User-Agent`。百度实测相反：分片 URL 自带签名，
带不带 Cookie 都返回 `HTTP 200`。所以百度的 `MediaResource.headers` **是空 map**：
把账号 Cookie 交给播放器转发给一个不需要它的第三方 CDN，是没有收益的凭据泄露。

**2.3 百度没有「还有下一页」字段。**

115 用 `offset` + `limit` 且有 `count`；百度用 `page` + `num`，文档通过 grep 整个抓包确认了
**没有**任何 `has_more` 字段（只有 `/api/search` 有）。所以结束判定只能依据**实际返回条数** ——
短于 `num` 的一页就是最后一页。这条规则不会死循环，共享契约套件断言了它。

### 3. 三个协议陷阱，都已在代码中处理并测试

| 陷阱 | 不处理的后果 | 测试 |
|---|---|---|
| **成功只认 `errno === 0`**；缺失或非数字的 `errno` 一律折算为 `-1`（厂商自己的拦截器就是这么做的） | 一个畸形响应被当成成功，向用户展示错误数据 | `an envelope with no code at all is not treated as success` |
| **`"errno":""` 在真实响应中出现**，而 JS 里 `Number("") === 0` | 空字符串被意外当成成功 | `an empty string errno is not coerced into success` |
| **`/api/categorylist` 返回 `info[]`，`/api/list` 返回 `list[]`**；`/api/filemetas` 的**每个元素自带 `errno`** | 分类页空白；一个批量的部分失败被报成整体失败 | `the category listing is read from the info array`、`an element level error inside a successful envelope is honoured` |

另外两处：`task_id` 是 **64 位整数**（厂商自己用正则从原始文本重取以避开 53 位浮点），
`errno 132` 带 `authwidget` 表示「需要身份验证」而非权限不足 —— 两者都有专门的测试。

### 4. 刻意不实现的能力，以及每一个的**具体**理由

项目规则要求「不能因为公共接口存在某方法，就默认支持」。百度这边有三处是**边界**而不是遗漏：

| 能力 | 状态 | 理由 |
|---|---|---|
| **下载** | `UNSUPPORTED` | 下载签名是 `base64_utf16(new Function("return " + sign2)()(sign3, sign1))`，其中 **`sign2` 是服务端实时下发的 JavaScript 源码**。文档明确「无法静态硬编码」。Kotlin 无法求值，而伪造一个 `sign` 会得到 `errno 113`「页面已过期」—— 一个把客户端的能力缺失归咎于用户会话的错误。 |
| **上传** | `UNVERIFIED` | 协议文档完整（6 个端点、全部常量、MD5 变换），但分片目标来自一个 60 秒有效的主机池，且它**写入真实账号**。写错会得到损坏或重复的文件，而不是用户能处理的错误。 |
| **分享创建** | `UNVERIFIED` | 实测响应要求账号先绑定手机号，且**成功响应从未被观测**。在一个成功形态未知的协议上对外发布文件，是可用操作里风险最高的一个。 |
| **扫码登录** | 不声明 | 10 步链路中**第 7 步**（唯一产生 `BDUSS` 的一步）需要 Passport 私有 JS 计算的 `sig`。大部分步骤可复刻，但**整个流程**不可 —— 所以整个流程都不声明。声明一个走不到最后的流程，正是「UI 显示必然失败的入口」。 |

前三者都返回可识别的 `Unsupported` 且**不发送任何请求**（有测试断言请求数不变）。

### 5. 顺带修掉的一个真实缺陷

`/api/filemetas` 是**多目标**端点。当一个文件不存在时，它返回的是：

```json
{"errno": 12, "info": [{"errno": -9}]}
```

即**外层是「部分失败」的成功信封，元素级才是真正的原因**。第一版实现把外层 `errno 12` 当成
整体失败，于是「查一个不存在的文件」会报「部分文件已存在于目标文件夹」—— 一个与事实无关的消息。

修正方式是在信封解析器上开一个**显式的** `partialFailureCodes` 参数，由多目标端点的调用方声明
「这个码意味着我需要检查每个元素」。这比在解析器里硬编码 `12` 更好：它让「谁负责检查元素」
成为调用点可见的义务。测试 `an element level error inside a successful envelope is honoured`
覆盖了它。

### 6. 测试

`./gradlew test` —— **499 个测试全绿，0 失败**（v0.2.2 为 402；新增 97 个，全部在 `provider:baidu`）。

| 组成 | 数量 | 覆盖 |
|---|---|---|
| 共享契约规则 | 29 | 与 115 跑**同一套** `ProviderContractRules` |
| HTTP 层 | 48 | 信封语义、分页、逐字段映射、错误码、两阶段播放握手、能力边界 |
| Cookie 解析 | 12 | 含 `Cookie:` 前缀、curl 包装、制表符分隔的开发者工具表格、值内含 `=` |
| 标识编解码 | 8 | 含难缠路径（`=`/`:`/`&`/空格/emoji）与跨 Provider 拒绝 |

两个 Provider 跑同一套契约，是这套规则作为「共享契约」的意义所在：它断言的是行为
（类型化失败、分页终止、能力与行为一致、`PreviewKind` 不含 URL），不是厂商语义。

另外 `./gradlew test --rerun-tasks` 的实际执行总数为 **854**（含 Android 模块的 debug + release 双变体）。

### 7. 发布流程

发布说明继续从仓库根目录的 `RELEASE_NOTES.md` 读取；哈希与来源 commit 仍由 CI 现场生成。
此次版本号 `0.2.2 → 0.3.0`（`versionCode 4 → 5`）。

---

### 已知限制（如实说明）

- **真实账号联调未进行**：本开发环境没有百度账号，所有 ✅ 项验证到「文档给出的响应形状能被
  正确解析并映射」这一层。逐项联调清单见 [`docs/baidu-api-mapping.md`](docs/baidu-api-mapping.md) §9.2。
- **`move`/`copy`/`delete` 声明为 `UNVERIFIED`**：这三个 `opera` 值在厂商 bundle 中**没有完整字面量**，
  是从 `actionType` 透传推断的。代码是完整实现的；缺的是「厂商确实接受这三个字符串」的证据。
  联调时若返回 `errno 2`，即说明推断的字符串不对。
- **回收站的元素级字段从未被观测**（文档中该账号回收站为空），字段名来自厂商自己的 Vue 模板。
  映射因此只读确认过的拼写，读不到 id 的条目会被跳过而不是给一个合成 id。
