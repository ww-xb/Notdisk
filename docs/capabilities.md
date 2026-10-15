# 能力声明表

> 本表是 `CloudCapabilities` 的**人类可读版本**，也是它的依据。
> 代码位置：`provider/*/…/*CloudProvider.kt` 的 `capabilities()`。
> 由测试固定：`capabilities declare the documented and the unverified accurately`。

---

## 1. 为什么是三态

```kotlin
enum class CapabilitySupport { SUPPORTED, UNVERIFIED, UNSUPPORTED }
```

项目要求明确：**不能因为公共接口存在某方法，就默认真实 Provider 支持该方法**。
二态（支持/不支持）会强迫在两件坏事里选一件：假装支持（UI 显示一个必然失败的入口），或假装不支持（把已经实现且验证过的功能藏起来）。

三态让「文档有、但本项目尚未验证」可以被如实表达。

| 状态 | 含义 | UI 应当 |
|---|---|---|
| `SUPPORTED` | 厂商文档已实测（`✅`）**且**本工程实现了完整请求/响应/错误处理 | 正常显示入口 |
| `UNVERIFIED` | 厂商文档有（多为 `📖` 源码级），或本项目未端到端验证 | 隐藏，或显示为禁用并附说明 |
| `UNSUPPORTED` | 厂商没有该接口，或本工程有意不提供 | 不显示入口 |

**能力是提示而非保证。** 声明 `SUPPORTED` 的操作在运行时仍可能失败（权限变化、风控、文件状态）。Provider 必须诚实返回错误。

---

## 2. 115 网盘

依据：`API_REFERENCE.md`（实测日期 2026-10-14）。
图例：`✅` = 该文档标注为实测；`📖` = 仅源码分析。

### 2.1 账号

| 能力 | 值 | 依据 |
|---|---|---|
| `interactiveFlows` | `{QR_CODE, MANUAL_CREDENTIAL}` | §2.1 扫码登录为 `✅`；`MANUAL_CREDENTIAL` 为粘贴会话 Cookie，两者都**不需要开发者凭据** |
| `silentRefresh` | `UNSUPPORTED` | 115 的 Cookie 会话没有文档化的刷新机制 |
| `multipleAccounts` | `true` | 凭据按登录产生，可并存 |
| `logout` | `SUPPORTED` | §2.5 登出接口 |
| `storageQuota` | `SUPPORTED` | §9.3 `files/index_info` `✅` |

**`MANUAL_CREDENTIAL` 为何现在被接受**：早期实现直接以 `Unsupported` 拒绝该流程，理由之一是
「手工输入难以安全存储」—— 这一半是错的：粘贴的 Cookie 与扫码得到的凭据进入的是**同一个**
Android Keystore 支持的加密存储（`P115EncryptedCredentialStore`），静态存储的安全性并无差别。
用户从浏览器开发者工具复制 `Cookie` 请求头粘贴进来；`P115CookieParser` 解析它，要求
`UID` / `CID` / `SEID` / `KID` 四项（`USERSESSIONID` 可选），随后凭据**先**向
`my.115.com/?ct=ajax&ac=nav` 校验，**通过后才写入**加密凭据存储，账号行也才随之创建 ——
因此一个失效或被截断的 Cookie 不会留下一个「看似已绑定、首次点击才失败」的账号。
该流程返回 `Completed` challenge（凭据已产生，**没有需要轮询的东西**）。

**OAuth 为何不在 `interactiveFlows` 里**：§2.4 的 OAuth 流程标记为 `📖`，且需要本项目尚未获得的 `app_id`。配置入口已就绪（`P115Configuration.oauthClientId`），未配置时 `oauthSupport = UNSUPPORTED`，UI 隐藏入口而**不是**弹出一个必然失败的按钮。

### 2.2 文件系统

| 能力 | 值 | 依据 |
|---|---|---|
| `listChildren` | `SUPPORTED` | §3.1 `✅` |
| `getFile` | `SUPPORTED` | §3.2 `✅`（+ §3.3 兜底） |
| `getPath` | `SUPPORTED` | §3.2 的 `paths` 数组 |
| `search` | `SUPPORTED` | §3.4 `✅` |
| `serverSideBreadcrumb` | `true` | §3.1 响应自带 `path[]` |
| `paging` | `OFFSET_LIMIT` | §3.1 的 `offset` + `limit` |
| `serverSideSortFields` | `NAME, MODIFIED_TIME, SIZE, CREATED_TIME, TYPE` | §3.1 的 `o` 参数取值 |
| `categoryFilter` | `SUPPORTED` | §9.1 的 `type` 参数（0–7）与统一 `FileCategory` 一一对应 |
| `suffixFilter` | `SUPPORTED` | §3.1 的 `suffix` 参数 |

### 2.3 文件操作

| 能力 | 值 | 依据 / 理由 |
|---|---|---|
| `createFolder` | `SUPPORTED` | §5.1 `✅` |
| `rename` | `SUPPORTED` | §5.2 `✅`（`/files/batch_rename`） |
| `move` | `SUPPORTED` | §5.3 `✅` |
| `copy` | `SUPPORTED` | §5.4 `✅` |
| `delete` | `SUPPORTED` | §8.2 `✅`（移入回收站） |
| `batchOperations` | `SUPPORTED` | §5.3/§5.4/§8.2 的 `fid[i]` 形式 |
| `share` | **`UNVERIFIED`** | §12.2 仅 `📖`；§15.4 未列为已验证 |
| `upload.supported` | **`UNVERIFIED`** | §6 全节 `📖`；§15.4「上传 OSS 直传的实际回调 —— 未实测」 |
| `upload.maxFileSizeBytes` | `200 MiB` | §6.3 `UPLOAD_CONFIG_H5.size_limit` |
| `upload.resumable` | `false` | §6.1 提到 plupload 分片，但可恢复性未文档化 |
| `upload.serverSideDuplicateDetection` | `false` | §5.9 有重复检测接口，但**上传流程本身**未验证 |
| `download.supported` | `SUPPORTED` | §7.1 `✅` |
| `download.directLink` | `true` | `file_url` |
| `download.directLinkExpires` | `true` | §7.1「短时效签名链接…不要缓存」 |
| `download.folderArchive` | `UNSUPPORTED` | §7.2 是页面级端点，非 JSON API |
| `trash.supported` | `SUPPORTED` | §8.1 `✅` |
| `trash.purge` | `SUPPORTED` | §8.4 `✅` |
| `trash.purgeRequiresSecret` | `true` | §8.4：`rb_pass` / 安全密钥 |
| `idempotentWrites` | **`false`** | §5.4 的 `copy` 不幂等：超时后重试会产生第二份副本 |

**为何上传与分享刻意不实现**：这两个操作有真实副作用（写入账号 / 把文件发布到公网 URL），而协议仅来自前端源码分析。在未验证的协议上实现它们，是本工程可做出的风险最高的行为。因此：声明 `UNVERIFIED`，方法返回 `CloudError.Unsupported` 并附中文说明，**不发送任何请求**（有测试断言 `requestCount == 0`）。

### 2.4 媒体

| 能力 | 值 | 依据 / 理由 |
|---|---|---|
| `videoStreaming` | `SUPPORTED` | §10.1 + §10.2 均为 `✅` |
| `audioStreaming` | `UNVERIFIED` | §10.4 的音频端点仅列出，未验证响应 |
| `protocols` | `{HLS}` | §10.2 实测产物是 m3u8 |
| `rangeRequests` | **`null`（未确定）** | §10.2 未文档化 range 语义；HLS 按分片取，字节范围不是容器的属性 |
| `urlExpires` | `true` | CDN 分片 URL 带 `se`/`ck`，生命周期未文档化，但可通过重新请求 `files/video` 刷新 |
| `urlRefresh` | `SUPPORTED` | 重新请求 `files/video` 即可 |
| `subtitles` | `SUPPORTED` | §10.1 的 `subtitle_info` |
| `multipleRenditions` | `SUPPORTED` | §10.1 的 `definition_list` / `definition_index` |
| `separateAudioTracks` | `SUPPORTED` | §10.2 实现建议 2：独立视频轨 + `audio/{N}.m3u8` |
| `playbackProgress` | **`UNVERIFIED`** | §10.3 的上报端点未定位；§15.4 列为未验证 |
| `requiresRequestHeaders` | `true` | §1.4 / §10.2：CDN 校验 `Referer`/`Origin`/`User-Agent` |

`rangeRequests = null` 是刻意的：该字段可空，正是为了让「不知道」可以被表达，而不是被迫猜一个值。

### 2.5 预览

| 能力 | 值 | 依据 |
|---|---|---|
| `image` | `SUPPORTED` | §11.2 `✅` |
| `thumbnail` | `SUPPORTED` | §11.2（`_100`/`_480`/`_800`/`_1440`/`_0`） |
| `text` | `SUPPORTED` | §11.3；本工程走下载直链读取 |
| `pdf` | `SUPPORTED` | §11.1 的 WPS 通道 |
| `officeDocument` | `SUPPORTED` | §11.1 `✅`（WPS 云文档 `url` + `token`） |
| `supportedExtensions` | 见 `P115FileMapper.PREVIEWABLE_EXTENSIONS` | 由扩展名推导，非厂商声明 |

注意：§11.1 实测「不支持的格式」返回错误码 `4011`。这不是能力缺失（115 **有**预览能力），而是该文件不支持。因此代码把它转成**成功的** `PreviewResult(kind = Unsupported)`，见 `docs/api-mapping.md` §6。

---

## 3. 百度网盘

依据：`docs/baidu-api-mapping.md`（底层逆向文档位于 `/data/workspace/baidu_reverse/docs/`，实测日期 2026-10-14）。
图例：`✅` = 文档标注实测（或 bundle 完整字面量 + 调用点）；`⚠️` = 文档标注为**推断**。

### 3.1 账号

| 能力 | 值 | 依据 |
|---|---|---|
| `interactiveFlows` | `{MANUAL_CREDENTIAL}` | §1.5 / §2.4 —— 粘贴浏览器会话 Cookie，**不需要任何开发者凭据** |
| `silentRefresh` | `UNSUPPORTED` | 百度未文档化任何刷新机制；`BDUSS` 由用户重新粘贴替换 |
| `multipleAccounts` | `true` | 凭据按登录产生，可并存 |
| `logout` | `SUPPORTED` | §2.5 |
| `storageQuota` | `SUPPORTED` | §9.2 `✅` |

**为什么只声明一条流程**：§2.2 的扫码链路有 10 步，其中**第 7 步**
（`passport.baidu.com/v3/login/main/qrbdusslogin`）需要 `sig`，而 `sig` 由 Passport 前端私有
JavaScript（`alg=v3` + `shaOne`）计算。文档明确「自研 APP 若要完整复刻需逆向 passport SDK」。
第 7 步正是**唯一产生 `BDUSS`** 的一步 —— 没有它，前 6 步没有任何意义。

所以扫码登录的*大部分*可复刻，但**整个流程**不可复刻，因此整个流程都不能声明。
声明一个走不到最后的流程，正是「UI 显示必然失败的入口」这条禁令所指的情况。

**⚠️ 一个必须记住的陷阱（已在代码中处理）**：`/api/loginStatus` 对**未登录**请求返回
`{"errno":0,"isLogin":0}` —— 一个**成功的信封**。只检查 `errno` 会把未登录的粘贴当成有效会话。
代码同时检查 `isLogin`（`a cookie that is not logged in is rejected` 断言了它）。

### 3.2 文件系统

| 能力 | 值 | 依据 |
|---|---|---|
| `listChildren` | `SUPPORTED` | §3.2 `✅` |
| `getFile` | `SUPPORTED` | §3.5 `✅`（`filemetas`，含元素级 `errno`） |
| `getPath` | `SUPPORTED` | **由 id 直接推导，零请求**（id 即绝对路径） |
| `search` | `SUPPORTED` | §3.4 `✅`（`recursion=1`，**删除** `dir`） |
| `serverSideBreadcrumb` | `false` | §3.2 的列表响应**不带**面包屑数组（与 115 的 `path[]` 不同）；UI 用 `getPath`，此处精确 |
| `paging` | `OFFSET_LIMIT` | §3.2 的 `page` + `num` |
| `serverSideSortFields` | `NAME, MODIFIED_TIME, SIZE` | §3.2 只文档化三个 `order` 取值 |
| `categoryFilter` | `SUPPORTED` | §3.3 的 `category`（1/2/3/4/6/7） |
| `suffixFilter` | `UNSUPPORTED` | §3.2/§3.4 都没有后缀参数 |

**`categoryFilter` 的映射不是恒等映射**：`5` 在百度不是文件类型（它是权限位 `CREATE_DIR`），
`6` 是「其他」而不是「压缩包」。因此 `ARCHIVE` → `6`、`SOFTWARE` → `7`、`BOOK` → `4`。
这是对厂商更粗粒度的分类法的近似，已在此处和代码注释中标注。

### 3.3 文件操作

| 能力 | 值 | 依据 / 理由 |
|---|---|---|
| `createFolder` | `SUPPORTED` | §3.7 `✅`（`/api/create?a=commit`，响应 `{errno,name,path}` 已实测） |
| `rename` | `SUPPORTED` | §3.6 `✅` —— **唯一**在 bundle 中有完整 URL 字面量的 `opera` |
| `move` | `UNVERIFIED` | §3.6 `⚠️` —— 仅由 `actionType:"move"` 透传推断 |
| `copy` | `UNVERIFIED` | §3.6 `⚠️` —— 仅由 `actionType:"copy"` 透传推断 |
| `delete` | `UNVERIFIED` | §3.6 `⚠️` —— 仅由 `doManage("delete",…)` 推断 |
| `batchOperations` | `UNVERIFIED` | 同上 |
| `share` | `UNVERIFIED` | §6.1 —— 实测响应要求绑定手机号，**成功响应从未被观测** |
| `upload` | `UNVERIFIED` | §4 文档完整，但会写入真实账号，且分片目标来自 60 秒有效的主机池 |
| `download` | **`UNSUPPORTED`** | §5.2 —— 签名需要执行**服务端实时下发的 JavaScript** |
| `trash.*` | `SUPPORTED` | §3.10 `✅`（四个操作均有源码字面量，列表端点已实测） |
| `purgeRequiresSecret` | `false` | 百度回收站没有密码（与 115 的 `rb_pass` 不同） |
| `idempotentWrites` | `false` | §3.6：`copy` 默认 `ondup:newcopy`，重试会产生**第二份副本** |

**为什么 `move`/`copy`/`delete` 是 `UNVERIFIED` 而不是 `SUPPORTED`**：这不是保守，
而是**依据强度**。文档明确记录 bundle 里**没有集中的 `opera` 枚举** —— `rename` 与 `import`
有完整字面量，其余是从 `actionType` 透传推出来的。代码是完整实现的、按文档请求形状测过的；
缺的是「厂商确实接受这三个字符串」的**证据**。三态声明存在的意义正是让这种区别可以被表达。

### 3.4 媒体

| 能力 | 值 | 依据 / 理由 |
|---|---|---|
| `videoStreaming` | `SUPPORTED` | §7.3 `✅`（两个阶段均实测） |
| `audioStreaming` | `UNSUPPORTED` | §7.6 —— 音频走 **dlink**，不走 `/api/streaming`，而 dlink 本工程无法签名 |
| `protocols` | `{HLS}` | §7.3 第二阶段返回 `application/x-mpegURL` |
| `rangeRequests` | `true` | §7.4 实测 `Range: bytes=0-1023` → `HTTP 206` + `Accept-Ranges: bytes` |
| `urlExpires` | `false` | 播放 URL 就是 API 调用本身，不是带生命周期的签名 URL；可重发即刷新 |
| `urlRefresh` | `SUPPORTED` | 同上 |
| `subtitles` | `UNVERIFIED` | §7.5 的清单端点已实测，但消费它要解析 HLS `#EXT-X-MEDIA` 并映射音轨，本工程未做 |
| `multipleRenditions` | `SUPPORTED` | §7.3.1 的 1080/720/480/360 阶梯 |
| `separateAudioTracks` | `UNSUPPORTED` | §7.6 —— 视频转码器的 `multitrack_list` 命名规则对百度未文档化 |
| `playbackProgress` | `UNVERIFIED` | §7 —— 进度保存在厂商的 `localStorage`，**没有**上报端点 |
| `requiresRequestHeaders` | **`false`** | §7.4 实测分片 URL 自带签名，**不需要** Cookie/UA/Referer |

**`requiresRequestHeaders = false` 是一个安全结论，不只是配置差异**：它意味着播放器
**不会**被交给账号 Cookie 去转发给 CDN。115 的场景需要 `Referer`/`Origin`/`UA`，百度不需要 ——
把凭据交给一个不需要它的第三方主机是没有收益的泄露。

**`errno 133`（广告闸门）如何被表达**：它是一个 `CloudError.Unsupported`，
但 `capability` 字段指向**窄**能力 `media.streaming.adToken`，而不是宽能力 `media.videoStreaming`。
这个区分是必需的：共享契约套件规定「宽能力可以有窄的洞，但洞必须可识别」。
不带 `capability` 的 `Unsupported` 会被（正确地）判定为「声明了流媒体却做不到」，
而那会掩盖真实情况 —— 流媒体可用，只是这个文件在广告闸门后面。
代码会自动用闸门给出的 `adToken` 完成第二阶段（深度上限 2，因为文档只描述两个阶段）。

### 3.5 预览

| 能力 | 值 | 依据 / 理由 |
|---|---|---|
| `image` | `SUPPORTED` | §8.2 —— `thumbs` 随**列表响应**下发，零额外请求 |
| `thumbnail` | `SUPPORTED` | 同上；`size`/`quality` **不在签名内** |
| `text` | `SUPPORTED` | §8.5 `✅`（`picdocpreview`） |
| `pdf` | `UNVERIFIED` | §8.1 路由到 `pdf-plugin`（平台阅读器）；分类正确，取字节需 dlink |
| `officeDocument` | `UNVERIFIED` | §8.4 —— `/disk/docview` 302 到**独立 OAuth 应用**，本工程无法完成该授权流程 |
| `supportedExtensions` | 见 `BaiduFileMapper.PREVIEWABLE_EXTENSIONS` | 由 §8.1 的注册表转录，非猜测 |

**为什么 Office 文档的分类结果是「无 viewer 材料的 `OfficeDocument`」**：
`PreviewKind.Unsupported` 是错的（文件**是**可预览的），而伪造一个
`OfficeViewer(url, token)` 会宣称一个走不通的入口。前者误导，后者撒谎 ——
所以取中间那个诚实的形状。

**签名 URL 绝不进入模型**：`thumbs` 与 `docpreview` 的 URL 带 `sign`/`expires`，
是短时效凭据，而 `FileItem` **会被缓存**。因此映射只把「URL 存在」记成布尔值。
`signed urls never leak into the file model` 断言了模型里不含 `sign=` 与 `http`。

---

## 4. 已删除的 Mock 网盘

`provider:mock` 模块已**整体删除**（`provider/mock/` 目录与 `settings.gradle.kts` 的
`include(...)` 均已移除），因此本表不再有 Mock 的能力声明。

删除理由与能力声明直接相关：该 Provider 会**伪造账号与文件**。它能如实声明自己的缺口，
但仍然会在 APK 内凭空造出「可浏览的网盘」；一旦随 release 版本发布，用户就可能把这些假数据
当成自己真实网盘的内容。**一个会伪造账号和文件的 Provider 不能从 release APK 可达。**

它过去承担的「离线覆盖不支持分支」职责，现在由录制响应体驱动的 `provider:p115` HTTP 测试和
各 feature 模块的 Compose 状态分支测试承担。

---

## 5. 如何读这张表写 UI

```kotlin
val caps = registry.capabilitiesOf(providerId).getOrNull() ?: return

// 入口是否显示
if (caps.operations.upload.supported == CapabilitySupport.SUPPORTED) { /* 显示上传按钮 */ }

// 带上限提示
caps.operations.upload.maxFileSizeBytes?.let { limit ->
    if (file.size > limit) showTooLarge(limit)
}

// 排序选项：只显示服务端真正支持的
val sortOptions = FileSortField.entries.filter { it in caps.fileSystem.serverSideSortFields }
```

**不要**把 `UNVERIFIED` 当作 `SUPPORTED` 使用。它是「可能有，但本项目没证明」，用户点击后失败比不显示入口更糟。

---

## 6. 能力声明的自检清单（新增 Provider 时）

- [ ] 每个 `SUPPORTED` 都有逆向文档的 `✅` 级依据（实测，或 bundle 完整字面量 + 调用点），或本项目的实测记录
- [ ] `⚠️` 级（推断）依据一律标 `UNVERIFIED`
- [ ] 声明 `UNSUPPORTED`/`UNVERIFIED` 的操作，方法返回 `Unsupported` 且**不发请求**
- [ ] `idempotentWrites` 按最坏情况填（任一写接口不幂等 → `false`）
- [ ] 不确定的布尔型能力用可空字段留 `null`，不要猜
- [ ] 跑共享契约套件：`ProviderContractRules` 会校验声明与行为一致
