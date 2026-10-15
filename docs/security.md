# 安全与隐私

> 本文说明三类敏感信息如何被隔离、哪些做法被**类型系统**强制、以及当前架构下**无法**由纯客户端解决的限制。

---

## 1. 三类信息，三种生命周期

项目要求 §16.3 的核心：文件标识、媒体 URL、授权凭证不可互换。本工程用**不同的 Kotlin 类型**而非注释来保证。

| 类别 | 类型 | 生命周期 | 持久化 | 泄露后果 |
|---|---|---|---|---|
| **文件标识** | `FileId`, `CloudFileKey` | 永久 | ✅ 允许（元数据缓存、最近记录） | 仅暴露「存在某个文件」 |
| **资源访问地址** | `MediaResource.url`、`DownloadSource.url`、`PreviewResource.url`、`OfficeViewer.url/token`、`MediaSubtitleTrack.url` | 分钟级 | ❌ **禁止** | **持有 URL 即可取到内容，无需任何凭据** |
| **授权凭证** | `P115Credential`（`USERSESSIONID` / `UID` / `CID` / `SEID` / `KID`） | 长期 | ⚠️ 仅加密存储 | 完全账号接管 |

### 1.1 强制手段

1. **类型不可互换。** `MediaResource` 不是 `String`，无法赋给 `FileItem.id`。
2. **`TransferTask` 里没有 URL 字段。** 传输任务只保存 `CloudFileKey`；下载前由 `prepareDownload` 现取链接。签名 URL 在**类型层面**就无法被写进任务记录。
3. **`DownloadSource` / `PreviewResource` 不是 `@Serializable`。** 要持久化必须显式转换，那一步会被评审拦下。
4. **`FileId` 编码不含 URL**，并由契约测试断言。

---

## 2. 凭据存储

### 2.1 115 凭据的特殊性

`API_REFERENCE.md` §2.1 Step 3 记录了一个会决定集成成败的细节：

> `UID` / `CID` / `SEID` / `KID` **不是通过 `Set-Cookie` 响应头下发的**，而是放在响应体 JSON 的 `data.cookie` 中。

因此：

- `P115HttpClient` **刻意不安装 OkHttp 的 `CookieJar`**。装了反而会给人「会话已经处理好了」的错觉，而实际上一个 cookie 都没拿到。
- `P115AuthProvider.persistSession` 显式读取 `data.cookie`。
- 有测试固定这一点：`qr flow extracts credentials from the response body`。

### 2.2 存储要求

```kotlin
interface P115CredentialStore {
    suspend fun save(accountId: AccountId, credential: P115Credential)
    suspend fun load(accountId: AccountId): P115Credential?
    suspend fun delete(accountId: AccountId)
    suspend fun listAccountIds(): List<AccountId>
}
```

- **生产实现必须使用 Android 加密存储**（`androidx.security:security-crypto` 的 `EncryptedSharedPreferences`，密钥由 Keystore 持有）。
- `InMemoryP115CredentialStore` **刻意不注册进生产依赖图**，因此误配置的构建无法悄悄退回明文存储 —— 它只在测试中显式传入。
- `P115Credential.toString()` 被重写为不输出任何凭据值：

```kotlin
override fun toString(): String =
    "P115Credential(userId=$userId, uid=${uid.take(6)}…, session=***, seid=***)"
```

即使有人不小心把它插进日志，也不会泄露。

### 2.3 明确禁止的做法（本工程均未采用）

| 做法 | 为何禁止 |
|---|---|
| 把 token 写进 Room 普通业务表 | 明文落盘；且普通表会进备份 |
| 把 `SEID` 放进 `CloudAccount` | `CloudAccount` 是**展示模型**，会流经 UI 层与日志 |
| 用 `SharedPreferences` 明文存凭据 | 与 Room 同理 |
| 硬编码客户端密钥 | 源码可反编译；混淆不是加密 |
| 记录完整签名 URL | query 里就是凭据（`k=`、`s=`） |

### 2.4 安全密钥（115 的 `rb/clean`）

§8.4 实测：彻底删除**强制要求账号安全密钥**，且失败响应的 `errno` 是 `0`，只能靠 `error: "密钥错误"` 文本识别。

处理：
- 映射为 `CloudError.AdditionalVerificationRequired`（`verificationKind = "115.safe_key"`），UI 据此弹输入框；
- 由 `purgeFromTrash(trashIds, safeKey)` **逐次传入**，**绝不持久化**；
- 是否已设置密钥由 `GET /user/security_key_status`（§2.3 `✅`）查询，未设置时不弹框。

---

## 3. 日志脱敏

`core:common` 的 `LogRedaction` 是唯一被认可的构造日志行方式：

```kotlin
LogRedaction.redactUrl("https://cdn.115.com/a.mp4?t=123&k=secret")
// → "https://cdn.115.com/a.mp4?<redacted>"     整个 query 被丢弃

LogRedaction.redactId("3289386448728834697")
// → "328938…(19)"                              可关联但不可使用
```

规则：

- **URL**：丢弃整个 query string（签名参数在那里）与 userinfo。
- **标识符**：保留前 6 位（便于跨日志关联）并附长度。
- **永不记录**：`Authorization` 头、`Cookie` 头、请求/响应体。
- `HttpLoggingInterceptor` 在生产配置里只允许 `BASIC` 级别；`BODY` 在 `NetworkConfig` 的 KDoc 中被明确禁用（body 可能含凭据）。

---

## 4. 网络层

| 项 | 做法 | 理由 |
|---|---|---|
| HTTPS | 全部厂商端点使用 `https://` | 见 `P115Hosts` |
| 超时 | 分 connect / read / write / call，可配置 | 115 的扫码状态端点被服务端节流到约 30 秒一次（§2.1），短 read timeout 会产生假失败 |
| 重定向 | 跟随 | 播放必需：`video_url` 只是逻辑入口，真实流在 CDN（§10.2） |
| 自动重试 | **默认关闭**，需显式 `executeWithRetry` | 见 §5 |
| CookieJar | **不安装** | 见 §2.1 |

---

## 5. 写操作与重试安全

`idempotentWrites = false`（115）。依据具体而非保守：

| 接口 | 重试后果 |
|---|---|
| `POST /files/copy` | **多出一份副本**（§5.4） |
| 上传 | 115 按文件名去重，可能产生重复文件（§6） |
| `POST /files/move` | 无害（已在目标目录） |
| `POST /rb/delete` | 无害（已在回收站） |

策略：
- 网络层不自动重试任何写操作；
- `TransferTask.autoRetryable`：下载 `true`（重取字节区间永远安全），上传 `false`；
- 上传重试必须由用户发起。

---

## 6. 隐私

### 6.1 不采集、不上报

本工程**没有**自有后端，也**不包含**任何分析、埋点或崩溃上报 SDK。所有数据只存在于设备与厂商服务器之间。

### 6.2 不持久化短时效资源

以下内容**一律不落盘**：

- `file_url`（下载直链）、`thumb.115.com` 签名 URL、`origin_url` / `source_url`；
- CDN 分片与字幕 URL；
- WPS 预览的 `url` + `token`；
- 安全密钥。

缓存只保存**稳定**信息：文件标识、名称、大小、时间、SHA1（缩略图定位需要）。

### 6.3 `pickcode` 的定位

`pickcode` 既不是 URL 也不是账号凭据 —— 它单独无法取到任何字节。但它也不属于文件身份（会变、可能缺失），因此：

- 存在 `P115PickcodeCache`，按 `(accountId, fileId)` 分区，**仅内存**，进程结束即失效；
- 不写入持久化缓存。

---

## 7. ⚠️ 纯客户端架构的限制（如实报告）

项目要求 §6.3 要求：若某认证流程必须依赖安全服务端凭据，应**明确报告架构限制**，而不是偷偷加后端或用不安全替代。

### 7.1 OAuth `client_secret`

`API_REFERENCE.md` §2.4 的 OAuth 流程只给出：

```
Token: POST passportapi.115.com/app/1.0/1.0/token?grant_type=authorization_code&client_id=<app_id>&code=<code>
刷新 : ?grant_type=refresh_token&refresh_token=<rt>&client_id=<app_id>
```

**关键观察**：文档给出的两个请求都只带 `client_id`，**没有 `client_secret`**。这说明 115 采用的是公共客户端（public client）模式。

结论：
- 若 115 确认按公共客户端处理，则纯客户端实现**没有架构障碍**，配合 PKCE 即可，无需后端。
- 若实际要求 `client_secret`，那么**纯客户端无法安全满足** —— 任何硬编码或混淆都会被逆向。届时正确做法是报告该限制，而不是硬编码密钥或私搭后端。
- 当前状态：`P115Configuration.oauthClientId = null`，`oauthSupport = UNSUPPORTED`，UI 隐藏 OAuth 入口，`notes` 给出中文说明。**没有伪造任何客户端凭据。**

### 7.2 需要项目方提供

| 项 | 用途 | 缺失影响 |
|---|---|---|
| 115 `app_id` + 回调地址 | OAuth 登录 | 无法启用 OAuth；扫码登录仍然可用（**不需要开发者凭据**） |
| 测试账号 | 真实联调 | 见 `docs/api-mapping.md` §10.2 |

---

## 8. 安全检查清单

新增 Provider 时应逐项确认：

- [ ] 凭据只进加密存储，不进 Room / DataStore / 日志 / `CloudAccount`
- [ ] 凭据类的 `toString()` 被重写为脱敏输出
- [ ] 所有签名 URL 类型的字段都**不**参与序列化
- [ ] 日志中的 URL 一律经 `LogRedaction.redactUrl`
- [ ] 未在源码中硬编码任何 token / secret / app_secret
- [ ] 写操作的 `idempotentWrites` 按最坏情况声明，且未自动重试
- [ ] 若某流程需要服务端密钥，已在此文档中如实记录，而非用不安全方案绕过
- [ ] 传输任务记录中不含任何 URL
