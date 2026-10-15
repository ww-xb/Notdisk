# 系统架构

> 本文说明**为什么**这样分层，以及哪些约束是**由测试强制**的，而不只是口头约定。

---

## 1. 设计目标与推导出的约束

项目的核心目标是「先接一家真实网盘验证完整架构，之后新增网盘只需新增 Provider」。
这个目标推导出四条硬约束，它们贯穿全部代码：

| # | 约束 | 强制的机制 |
|---|---|---|
| 1 | UI 不认识任何具体网盘 | UI 模块只依赖 `core:domain` 与 `core:model`；**不依赖任何 provider 模块** |
| 2 | 播放器不认识任何具体 Provider | 播放器只消费 `MediaResource`；资源解析在 `MediaPlaybackCoordinator` |
| 3 | 新增网盘不修改公共流程 | 分发只经 `ProviderRegistry`；代码中**没有** `when (providerId)` |
| 4 | 文件标识 / 媒体 URL / 授权凭证三者不可互换 | 三种不同的 Kotlin 类型，见 §5 |

第 3 条不是靠自觉：`ProviderRegistry.require()` 返回 `CloudResult`，因此「未注册的 Provider」是**类型化的错误**而不是崩溃，也不需要调用方枚举已知厂商。

---

## 2. 模块与依赖方向

```text
                         app  (MainActivity, 导航, Hilt 图)
                          │
        ┌─────────────────┼──────────────────┬────────────────┐
        ▼                 ▼                  ▼                ▼
  feature:accounts  feature:files     feature:player   feature:preview
  feature:settings
        │                 │                  │                │
        └─────────────────┴────────┬─────────┴────────────────┘
                                   ▼
                            core:designsystem      (纯 UI，不知道「网盘」是什么)
                                   │
                                   ▼
                              core:domain          (UseCase；纯 Kotlin，无 Android 依赖)
                                   │
                    ┌──────────────┴───────────────┐
                    ▼                              ▼
            core:provider-api                  core:model
            (CloudProvider 等契约)          (FileItem / MediaResource …)
                    ▲                              ▲
                    │                              │
            provider:p115 ─────────────────────────┘
            (真实接入)
                    │
                    ▼
               core:common   (CloudResult / CloudError / CloudLogger)
```

依赖方向**单向向下**。需要注意的三点：

- **`core:domain` 是纯 Kotlin JVM 模块**，不是 Android 模块。它编译不过任何 Android API，这在编译期就杜绝了「Domain 层偷偷用 Context」。
- **`core:designsystem` 不依赖 `core:model`**。它接收原始类型（字符串、布尔、尺寸）而不是 `FileItem`，因此 UI 组件无法知道「文件来自哪个网盘」。
- **Provider 只有一个**：`provider:p115`。此前的 `provider:mock`（`mock`）模块已**整体删除**，`settings.gradle.kts` 中的 `include(...)` 也已移除。删除原因不是它无用，而是它会在 APK 内伪造账号与文件：一个能凭空造出账号与文件列表的 Provider 一旦进入 release 构建，就存在被真实用户当成真实数据的风险。公共契约与平台无关这一点，现在由 `provider:p115` 接入 `core:provider-testing` 的共享规则来检验。

---

## 3. 统一 Provider 契约

契约由五个子接口组成，每个 Provider 全部实现：

```kotlin
interface CloudProvider {
    val id: ProviderId
    val displayName: String
    val auth: AuthProvider
    val files: FileSystemProvider
    val operations: FileOperationProvider
    val media: MediaProvider
    val preview: PreviewProvider
    fun capabilities(): CloudCapabilities
}
```

### 3.1 三条贯穿所有签名的不变量

**① 账号上下文显式传入。** 每个远程调用都取 `AccountId`，Provider 内部**没有**「当前账号」这种环境状态。原因很实际：同一 Provider 的两个账号必须能并发操作，任何环境状态都会让它们互相污染。

**② 失败是返回值，不是异常。**

```kotlin
sealed interface CloudResult<out T> {
    data class Success<out T>(val data: T) : CloudResult<T>
    data class Failure(val error: CloudError) : CloudResult<Nothing>
}
```

- 不抛异常处理预期失败；
- 不用 `null` 表示失败（`null` 是**领域概念**，例如「该文件没有缩略图」）；
- **取消永远向上传播**：`CancellationException` 绝不被吞进 `Failure`。这条在 `P115HttpClient` 和 `HttpExecutor` 中都有专门处理（OkHttp 把取消报成 `IOException("Canceled")`，必须识别并转回 `CancellationException`）。

**③ 厂商类型不越界。** 没有 DTO、没有 `okhttp3.Response`、没有厂商 SDK 对象穿过契约。原始 JSON 只在 `provider:p115` 内部的 `api/` 包里出现。

### 3.2 能力声明（`CloudCapabilities`）

能力是三态而非布尔：

```kotlin
enum class CapabilitySupport { SUPPORTED, UNVERIFIED, UNSUPPORTED }
```

- `SUPPORTED` — 文档已实测 **且** 本工程实现了完整请求/响应/错误处理。
- `UNVERIFIED` — 厂商文档有，但未端到端验证。UI 保守处理（隐藏或禁用并说明）。
- `UNSUPPORTED` — 确实没有。

三态的存在是为了让「不知道」可以被表达。二态会强迫在「假装支持」和「假装不支持」之间二选一，两者都是撒谎。

复杂能力用配置对象而非布尔，例如：

```kotlin
data class UploadCapabilities(
    val supported: CapabilitySupport,
    val maxFileSizeBytes: Long?,      // 115: 200MB (UPLOAD_CONFIG_H5.size_limit)
    val resumable: Boolean,
    val serverSideDuplicateDetection: Boolean,
)
```

---

## 4. 契约由测试强制，而非由文档约定

`core/provider-testing` 里有一套 `ProviderContractRules`（29 条规则）。`provider:p115` 接入的就是它：

```kotlin
class P115ProviderContractTest : ProviderContractRules(P115ContractFixture.build())
```

这套规则抓到了本工程中若干**真实缺陷**，这是它存在的最好理由：

| 规则 | 抓到的真实问题 |
|---|---|
| `contract_get_file_matches_listing` | 115 的 `FileId` 把 `pickcode` 编进了身份，导致同一文件有两个 id → 缓存与媒体刷新错乱 |
| `contract_pagination_terminates` | 一个 `observeTransfer` 风格的流不终止会让 `collect` 永久挂起 |
| `contract_undeclared_operations_fail_closed` | 能力声明与实际行为不一致（声明 `UNSUPPORTED` 却返回成功） |
| `contract_preview_kind_has_no_urls` | 早期设计把厂商 URL 塞进了 `PreviewKind` |
| `contract_unknown_id_is_typed_failure` | `ParseError` 被用在「响应正常但没有该节点」的场景，误导用户 |

### 4.1 关于 `UNVERIFIED` 的一致性语义

规则对三态的处理必须一致，否则会自相矛盾：

| 声明 | 规则要求 |
|---|---|
| `SUPPORTED` | 调用**不得**返回 `CloudError.Unsupported`——除非该错误指明了**更窄的能力**（例如「115 能下载文件，但不能打包下载文件夹」） |
| `UNSUPPORTED` | 调用**必须**返回 `CloudError.Unsupported`（fail closed，不能悄悄做别的事） |
| `UNVERIFIED` | 两种结果都可接受——「未验证」的字面含义就是如此 |

把 `UNVERIFIED` 归入「必须失败」会迫使 Provider 少报真实能力，因此规则刻意不这样做。

---

## 5. 三类信息的严格分离

这是项目要求 §16.3 的核心，也是**用类型强制**的：

| 类别 | 类型 | 生命周期 | 可否持久化 | 泄露后果 |
|---|---|---|---|---|
| **文件标识** | `FileId` / `CloudFileKey` | 永久 | ✅ 可（缓存、最近记录） | 仅暴露「有个文件」 |
| **媒体/下载 URL** | `MediaResource.url`, `DownloadSource.url`, `PreviewResource.url` | 分钟级 | ❌ **不可** | 任何人凭 URL 即可取到内容 |
| **授权凭证** | `P115Credential`（`USERSESSIONID`/`SEID`/…） | 长期 | ⚠️ 仅加密存储 | 完全账号接管 |

强制手段：

1. **类型不同**。`MediaResource` 不是 `String`，不能被赋给 `FileItem.id`。
2. **`TransferTask` 里没有 URL 字段**。任务只存 `CloudFileKey`；传输前由 `prepareDownload` 现取。签名 URL 在类型层面就无法被写进任务记录。
3. **`DownloadSource` / `PreviewResource` 刻意不是 `@Serializable`**。要持久化它们必须显式做一次转换，而那是代码评审会拦下的动作。
4. **日志脱敏**。`LogRedaction.redactUrl` 直接丢掉整个 query string；`P115Credential.toString()` 被重写为不输出任何凭据。
5. **`FileId` 编码里不含 URL**，并由契约测试断言（`!item.id.value.startsWith("http")`）。

---

## 6. 播放器与 Provider 的解耦

流程（项目要求 §9.1）：

```text
用户点媒体文件
      ↓
MediaPlaybackCoordinator          ← 唯一知道「如何刷新」的地方
      ↓
ResolveMediaResourceUseCase
      ↓
ProviderRegistry.require(providerId)
      ↓
对应 Provider 的 MediaProvider
      ↓
统一 MediaResource
      ↓
Media3 / ExoPlayer
```

播放器（`feature:player`）**只**接收 `MediaResource`。它不知道 `ProviderId` 是什么，也无法构造厂商 URL。校验方式很直接：`feature:player` 的依赖里没有 `provider:p115`，因此写不出网盘判断逻辑。

### 6.1 链接过期与刷新

`MediaResource` 携带：
- `url` — 签名地址（**秘密**，短时效）
- `headers` — CDN 要求的 `Referer`/`Origin`/`User-Agent`（**不是**账号凭据）
- `expiresAt` — 厂商声明的过期时间（可能为 `null`，表示未声明）
- `refreshable` — 是否可刷新
- `refreshToken: MediaRefreshToken?` — **只含稳定标识**（provider + account + file + 质量档位），因此可安全保留整个播放会话

过期处理：
1. 播放器遇到可识别的过期错误；
2. 协调器检查 `refreshable`；
3. 可刷新 → 用 `refreshToken` 重新解析，并把播放位置恢复到原处；
4. 不可刷新 → `CloudError.ResourceUrlExpired(refreshable = false)`，UI 给出下载或外部打开的替代路径。

`isExpired(skewMillis, nowMillis)` 的**时钟可注入**是刻意的：过期是播放中时序最敏感的部分，读系统时钟的方法无法用虚拟时钟测试。

### 6.2 115 的特殊性已被建模，而非被特殊处理

115 的转码产物是**独立视频轨 + `audio/{N}.m3u8`**（`API_REFERENCE.md` §10.2）。这不是 `if (provider == p115)`，而是统一模型里的两个字段：

```kotlin
val renditions: List<MediaRendition>   // 清晰度档位
val audioTracks: List<MediaAudioTrack> // 独立音轨
```

任何有同样特征的网盘（分离音视频的 HLS）都能填这两个字段，播放器无需改动。

---

## 7. 预览架构

```kotlin
suspend fun preview(
    accountId: AccountId,
    fileId: FileId,
    resolveAccess: Boolean = true,
    hint: PreviewHint = PreviewHint.Empty,
): CloudResult<PreviewResult>
```

两个设计决定值得说明：

**① `resolveAccess = false` 必须零网络请求。**
列表渲染不能每行发一次预览请求（200 行目录 → 200 次请求）。分类只依据调用方已有的 `PreviewHint`。

**② 因此必须有 `PreviewHint`。**
115 的 `FileId` 只含 `cid`/`fid`/`pickcode`，**不含文件名**，没有 hint 就无法本地分类。hint 是咨询性的：Provider 可以忽略它，但绝不能因为缺失而失败。

**③ 「无预览」与「预览失败」是两件事。**

| 情况 | 返回 | UI |
|---|---|---|
| Provider 完全没有预览能力 | `CloudError.Unsupported` | 不显示预览入口 |
| 有预览能力，但这个文件不行（115 的 `4011`） | **成功**的 `PreviewResult(kind = Unsupported)` | 「可下载后用本地应用打开」 |
| 预览地址过期 | 成功 + `expiresAt`；刷新失败则 `ResourceUrlExpired` | 自动刷新，失败才提示 |

---

## 8. 写操作的安全策略

### 8.1 重试只在幂等时自动进行

`FileOperationCapabilities.idempotentWrites` 对 115 是 `false`。理由具体而非保守：

- `POST /files/move` 重试一次 → 无害（已在目标目录）；
- `POST /files/copy` 重试一次 → **多出一份副本**；
- 重试上传 → 115 按文件名去重，可能产生重复文件。

因此：
- 网络层**默认不重试**，只有显式调用 `executeWithRetry` 才启用，且只对幂等请求；
- 传输层：下载 `autoRetryable = true`（重取字节区间永远安全），上传 `false`；
- 上传失败要重试必须由**用户**发起。

### 8.2 批量操作报告逐项结果

```kotlin
data class BatchOperationResult(
    val succeeded: List<CloudFileKey>,
    val failed: List<BatchItemFailure>,
    val vendorTaskId: String?,   // 115 的 move_proid，异步任务
)
```

把部分成功压成布尔会掩盖数据丢失。115 的 `fid[i]` 批量接口正是可能「5 个成功 3 个」的接口。

### 8.3 不支持的能力 fail closed

对声明为 `UNSUPPORTED`/`UNVERIFIED` 的操作，Provider **返回类型化错误且不发送请求**。
这一点由测试断言（`assertEquals(0, server.requestCount)`），因为它是最容易被「顺手实现一下」破坏的约束。

---

## 9. 错误模型

`CloudError` 是一个封闭的 sealed class，覆盖项目要求 §12 的全部类别：

```
NetworkUnavailable  Timeout  ServerError  RateLimited
NotAuthenticated    TokenExpired  PermissionDenied  AdditionalVerificationRequired
NotFound            Deleted  InvalidArgument  Unsupported
ResourceUrlExpired  IncompatibleResource  QuotaExceeded  ParseError
LocalIoError        Unknown
```

每个都保留 `vendorCode` / `vendorMessage` 供诊断，但**不含凭据**。
`isRetryable` 与 `requiresReauthentication` 是派生属性，让调用方不必 `when` 枚举全部子类。

关键区分（都有测试）：

- **网络故障 ≠ 授权失效。** 一次 500 绝不把账号标为过期（`a server error does not invalidate the stored credential`）。只有明确的认证失败才降级为 `TokenExpired`。
- **`NotFound` ≠ `ParseError`。** 响应能解析但没有该节点，是「文件不存在」，不是「App 坏了」。
- **`Unsupported` ≠ `Unknown`。** 前者让 UI 隐藏入口，后者提示重试。

---

## 10. 多账号与数据隔离

- `AccountId` 是**本地生成**的 UUID，不是厂商 user_id。重新授权同一厂商账号时沿用原 id，因此缓存不会丢失。
- 每条远程调用、每个缓存键、每个传输任务都以 `(ProviderId, AccountId)` 作用域。
- `CloudFileKey(providerId, accountId, fileId)` 是唯一可跨边界使用的文件引用。
- Provider 内部的缓存（如 115 的 `P115PickcodeCache`）也按账号分区，避免两个账号共享解析结果。

---

## 11. 导航与首页的三级规则

### 11.1 宿主导航

根 `Scaffold` 的底部导航只有**两个**顶级标签：**文件**与**设置**。
账号页不是顶级标签，入口在**设置 → 账号**（`SettingsScreen` 的「管理账号」/「去绑定」按钮
调用 `onOpenAccounts`，导航到 `Routes.ACCOUNTS`）。

### 11.2 首页按账号数分三级

`FilesViewModel` 用显式的 `ScreenLevel` 表达当前层级，而不是从 `breadcrumb.isEmpty()` 反推 ——
后者会把三种本质不同的界面混为一谈。规则只看已绑定账号数：

| 已绑定账号数 | `ScreenLevel` | 首页 |
|---|---|---|
| 0 | `EMPTY` | 空状态，给出「去绑定账号」入口 |
| 1 | `ACCOUNT_ROOT_SINGLE` | **直接显示该账号的内容**，不插入分组层 |
| ≥2 | `HOME_MULTI` | **每个账号一个顶层文件夹**，点进去才是该网盘内容 |

`homeItemsFor` 生成 `HOME_MULTI` 的行：行的 `id` 是 **account id**（不是 provider id，因为同一
Provider 可以绑定两个账号，两者都必须作为独立顶层文件夹出现），行的名字是 **Provider 显示名**
（例如「115 网盘」）。同一 Provider 绑定了不止一个账号时，在显示名后追加 ` · ` 与账号显示名
（`"$cloudName · ${account.displayName}"`，例如「115 网盘 · 张三」）以区分。
这些是合成的文件夹行，不携带任何厂商能力标志（`canRename = false` 等），因为它们不是真实的远程对象。

### 11.3 返回键向上走，而不是退出应用

文件夹浏览的层级由 `FilesViewModel` 自己持有，导航图的后退栈里只有首页与 player/preview。
因此若不拦截，位于第 3 层目录时按返回键会直接落到 Activity 并**关闭应用**。

`NotDiskNavHost` 用 `BackHandler` 让浏览器优先处理：**仅当浏览器还能向上时启用**。
`navigateUp()` 在云的根目录（`breadcrumb.size <= 1`）调用 `goHome()` —— 层次上有两个账号时回到
网盘列表，只有一个账号时回到该账号根目录。没有上一层时（`HOME_MULTI`、`EMPTY`、单账号根目录）
handler 处于禁用状态，交回平台行为即退出应用。不存在「总是消费返回事件」的实现，
否则用户将无法退出。

### 11.4 Edge-to-edge 的 inset 归属

应用使用 `enableEdgeToEdge()`，窗口 inset 必须**恰好被消费一次**。因此：

- 根 `Scaffold` 声明 `contentWindowInsets = WindowInsets(0, 0, 0, 0)`，只保留底部导航栏的高度；
- 顶部 inset 由各页面自己拥有：文件页的 `TopAppBar` 使用 `statusBarsPadding()`；
  player/preview 没有 app bar，由它们自己的顶部行应用 `statusBarsPadding()`。

此前宿主消费了状态栏 inset，而 `feature:files` 的嵌套 `Scaffold` 又叠加了一次 app bar 的 inset，
这正是屏幕上出现空白带的成因。

---

## 12. 已验证的构建与测试状态

见 [`docs/testing.md`](testing.md)。当前：

- `gradle clean build` **BUILD SUCCESSFUL**，产出 `app-debug.apk`（约 76 MB，
  `applicationId = com.notdisk.app`，`versionName = 0.2.0` / `versionCode = 2`）；
- **393 个测试全绿，0 失败**（`core:domain` 144 · `provider:p115` 74 · `core:network` 33 ·
  `feature:player` 32 · `core:designsystem` 28 · `feature:files` 43 · `core:data` 17 ·
  `feature:settings` 8 · `feature:accounts` 7 · `feature:preview` 7）；
- 真实账号联调**未进行**，原因与清单见 [`docs/api-mapping.md`](api-mapping.md) §10。

---

## 13. 新增一个网盘的步骤

1. 分析官方文档，产出能力映射表（照 `docs/api-mapping.md` 的格式）。
2. 新建 `provider/<name>` 模块，实现 `CloudProvider` 的五个子接口。
3. 写一个 `ProviderContractFixture`，让共享契约套件跑起来 —— 这一步会立刻暴露契约不匹配。
4. 用真实/录制的响应写 HTTP 层测试（照 `P115ProviderHttpTest` 的 `P115TestBodies` 模式）。
5. 在 DI 图里注册（`ProviderRegistry`），**不改动任何现有 UI、播放器或其它 Provider**。
6. 更新 `docs/api-mapping.md` 与 `docs/capabilities.md`。

若第 5 步需要修改公共抽象，应先说明设计原因、向后兼容方案与回归测试计划（项目要求 §19）。
