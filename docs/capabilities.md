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

## 3. 已删除的 Mock 网盘

`provider:mock` 模块已**整体删除**（`provider/mock/` 目录与 `settings.gradle.kts` 的
`include(...)` 均已移除），因此本表不再有 Mock 的能力声明。

删除理由与能力声明直接相关：该 Provider 会**伪造账号与文件**。它能如实声明自己的缺口，
但仍然会在 APK 内凭空造出「可浏览的网盘」；一旦随 release 版本发布，用户就可能把这些假数据
当成自己真实网盘的内容。**一个会伪造账号和文件的 Provider 不能从 release APK 可达。**

它过去承担的「离线覆盖不支持分支」职责，现在由录制响应体驱动的 `provider:p115` HTTP 测试和
各 feature 模块的 Compose 状态分支测试承担。

---

## 4. 如何读这张表写 UI

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

## 5. 能力声明的自检清单（新增 Provider 时）

- [ ] 每个 `SUPPORTED` 都有 `API_REFERENCE.md` 的 `✅` 级依据，或本项目的实测记录
- [ ] `📖` 级依据一律标 `UNVERIFIED`
- [ ] 声明 `UNSUPPORTED`/`UNVERIFIED` 的操作，方法返回 `Unsupported` 且**不发请求**
- [ ] `idempotentWrites` 按最坏情况填（任一写接口不幂等 → `false`）
- [ ] 不确定的布尔型能力用可空字段留 `null`，不要猜
- [ ] 跑共享契约套件：`ProviderContractRules` 会校验声明与行为一致
