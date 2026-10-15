# Provider 契约

> 面向**新增网盘接入者**的操作手册：公共契约是什么、必须遵守哪些规则、如何用测试证明遵守了。

---

## 1. 契约全貌

```kotlin
interface CloudProvider {
    val id: ProviderId                      // 稳定标识，会被持久化，不可改名
    val displayName: String                 // 面向用户的中文名
    val auth: AuthProvider
    val files: FileSystemProvider
    val operations: FileOperationProvider
    val media: MediaProvider
    val preview: PreviewProvider
    fun capabilities(): CloudCapabilities   // 必须廉价、无副作用、无网络
}
```

`ProviderId` 一旦发布就**不能改**：它随账号与缓存行一起被持久化，改名会让用户的账号变成孤儿。

---

## 2. 五条硬性规则

### 规则 1：账号上下文显式传入

每个远程调用都取 `AccountId`。Provider 内部**没有**「当前账号」这种状态。

```kotlin
suspend fun listChildren(accountId: AccountId, request: ListChildrenRequest): CloudResult<FilePage>
```

理由：同一 Provider 的两个账号必须能并发操作。任何环境状态都会让它们互相污染，而多账号是项目要求 §6.2 的硬性要求。

### 规则 2：失败是返回值

```kotlin
sealed interface CloudResult<out T> {
    data class Success<out T>(val data: T) : CloudResult<T>
    data class Failure(val error: CloudError) : CloudResult<Nothing>
}
```

- 不用抛异常处理预期失败；
- 不用 `null` 表示失败 —— `null` 是领域概念（「该文件没有缩略图」）；
- **取消必须传播**：`CancellationException` 绝不能被吞进 `Failure`。
  OkHttp 会把取消报成 `IOException("Canceled")`，必须识别并转回 `CancellationException`。

### 规则 3：厂商类型不越界

没有 DTO、没有 `okhttp3.Response`、没有厂商 SDK 对象穿过契约。
原始 JSON 只出现在 `provider:<name>/internal/api/` 包内。

### 规则 4：能力必须诚实

见 [`docs/capabilities.md`](capabilities.md)。要点：

- 只有文档已**实测**且本工程完整实现的才能标 `SUPPORTED`；
- 文档来自源码分析的标 `UNVERIFIED`；
- 声明为 `UNSUPPORTED`/`UNVERIFIED` 的操作，方法必须返回 `CloudError.Unsupported` 且**不发送请求**；
- 不确定的布尔能力用可空字段留 `null`，不要猜。

### 规则 5：不猜测 API

禁止编造端点、参数、响应字段或授权范围。
每个端点常量都要带**依据注释**（指向文档章节），例如：

```kotlin
/**
 * §5.2 ✅ — rename.
 *
 * This is the *only* working rename endpoint. The widely-circulated `/files/edit` with
 * `cid`/`file_id`/`n` returns `errno 1001`; the manual measured it explicitly.
 */
const val FILES_BATCH_RENAME = "$WEB_API/files/batch_rename"
```

`P115Verification` 枚举（`MEASURED` / `SOURCE_ONLY` / `REQUIRES_APP_CREDENTIALS`）把「依据强度」变成类型，`permitsSupportedCapability` 让能力声明可以从单一事实来源推导。

---

## 3. 关键类型速查

| 类型 | 何时用 |
|---|---|
| `ProviderId` | Provider 身份（编译期常量性质） |
| `AccountId` | **本地生成**的账号标识（UUID），不是厂商 user_id |
| `FileId` | 不透明的文件句柄。**跨 Provider/账号无意义** |
| `CloudFileKey(providerId, accountId, fileId)` | 任何会跨边界存活的东西（缓存键、传输记录、深链） |
| `FileItem` | 统一文件模型 |
| `FilePage` | 一页结果 + 不透明游标 |
| `MediaResource` | 可播放资源（**含签名 URL，属秘密**） |
| `PreviewResult` | 预览结论 + 访问材料 |
| `CloudCapabilities` | 能力声明 |
| `TransferTask` | 传输记录（**不含 URL**） |

### 3.1 `FileId` 必须只含稳定身份

这是本项目踩过的真实坑：115 的 `pickcode` 曾被编进 `FileId`，导致**同一文件两个身份**（列表给 `pc`、详情给 `pick_code`，还可能缺失），破坏缓存键与媒体刷新。

**要求**：
- `FileId` 只编码**稳定**、**必现**、**唯一**的标识；
- 会变化或可能缺失的派生信息放**按 `(accountId, fileId)` 分区的缓存**里；
- 编解码器必须容忍历史格式（本项目忽略遗留的 `:pc=` 段），否则升级后旧缓存全部失效。

**自检**：对同一文件调用 `listChildren` 与 `getFile`，返回的 `id` 必须**逐字节相同**。共享契约测试会断言这一点。

### 3.2 分页游标必须前进

`FilePage.nextCursor` 是不透明的。规则：

- `null` 表示**确定没有更多**，是一个断言；
- 「不知道」不能返回一个会重新取第 1 页的游标；
- 结束判定优先依据**实际返回条数**而不是厂商的 `count` 字段（`count` 语义在不同端点可能不同，甚至可能缺失）。

共享契约测试会遍历分页直到结束，并断言每个子项**恰好出现一次**。

---

## 4. 接入步骤

### 第 1 步：分析文档，产出映射表

照 [`docs/api-mapping.md`](api-mapping.md) 的格式，逐项标注「已确认 / 待验证 / 不支持」，并记录需要项目方提供的配置。

### 第 2 步：建模块，实现五个子接口

```
provider/<name>/
  build.gradle.kts
  src/main/kotlin/com/notdisk/provider/<name>/
    <Name>CloudProvider.kt          能力声明 + 装配
    <Name>FileSystemProvider.kt
    <Name>FileOperationProvider.kt
    <Name>MediaProvider.kt
    <Name>PreviewProvider.kt
    auth/<Name>AuthProvider.kt
    api/                            端点常量、HTTP 客户端、错误映射、JSON 读取
    internal/                       DTO → 领域模型映射、id 编解码
```

### 第 3 步：接入共享契约测试（**先做这一步**）

```kotlin
class MyProviderContractTest : ProviderContractRules(MyContractFixture.build())
```

实现 `ProviderContractFixture` 需要提供少量真值：

```kotlin
interface ProviderContractFixture {
    val provider: CloudProvider
    val accountId: AccountId
    val knownFolderId: FileId
    val nonExistentFileId: FileId
    val searchKeywordThatMatchesSomething: String
    val searchKeywordThatMatchesNothing: String
    val knownVideoFileId: FileId?
    val knownImageFileId: FileId?
    val knownNonMediaFileId: FileId?
    val knownUnpreviewableFileId: FileId?
    val maxPaginationPages: Int
    val paginationPageSize: Int
    val readableContentUriOrNull: String?
}
```

契约套件是**离线**的。网络型 Provider 用 MockWebServer 提供录制响应即可（参考 `P115ContractFixture`）。
规则本身不提及任何具体文件 id，因此同一套规则可以复用在所有 Provider 上。

### 第 4 步：写 HTTP 层测试

用真实的（或录制的）响应体，覆盖：
- 请求参数名与形式（这是最容易错的地方）；
- 响应字段的类型差异；
- 错误码映射；
- 「声明不支持的操作不发请求」。

### 第 5 步：注册

在 DI 图里加入 `ProviderRegistry`，**不改动任何现有 UI、播放器或其它 Provider**。

若这一步需要修改公共抽象，应先说明设计原因、向后兼容方案与回归测试计划（项目要求 §19）。

### 第 6 步：更新文档

`docs/api-mapping.md`、`docs/capabilities.md`，以及本文末尾的 Provider 清单。

---

## 5. 常见错误与后果

| 错误 | 后果 |
|---|---|
| `FileId` 里放了会变的信息 | 同一文件多个身份 → 缓存错乱、媒体刷新失效 |
| 用 `when (providerId)` 分发 | 新增网盘要改公共代码，违背核心目标 |
| 声明 `SUPPORTED` 但返回 `Unsupported` | UI 显示必然失败的入口 |
| 声明 `UNSUPPORTED` 却返回成功 | UI 隐藏了本来能用的功能 |
| 把签名 URL 存进 `TransferTask` | 凭据落盘 |
| 网络故障把账号标为过期 | 用户被无故登出（有测试防这条） |
| 「文件不存在」返回 `ParseError` | 用户看到「App 坏了」而不是「文件没了」 |
| 游标不前进 | 「加载更多」无限循环 |
| 传输进度流不终止 | 任何 `collect` 永久挂起 |
| 批量写操作自动重试 | 复制产生重复文件 |

---

## 6. 现有 Provider 清单

| Provider | 模块 | id | 认证 | 状态 |
|---|---|---|---|---|
| 115 网盘 | `provider:p115` | `p115` | `QR_CODE` / `MANUAL_CREDENTIAL`（粘贴会话 Cookie）；均无需开发者凭据 | 22 个端点已实现并测试；3 项刻意未实现；真实联调待做 |

**此前的 `provider:mock`（`mock`）已整体删除**（目录 `provider/mock/` 与 `settings.gradle.kts`
的 `include(...)` 均已移除）。它曾是一个纯离线参考实现，能跑通同一套契约；但一个 Provider 若要
在 APK 内**伪造账号与文件**，就存在被真实用户当成真实数据的风险，因此它不能从 release 构建可达。
公共契约与平台无关这一点，现在由 `provider:p115` 接入 `ProviderContractRules` 来检验；
`core:provider-testing` 仍然保留共享规则。

### 6.1 `MANUAL_CREDENTIAL` 流程的契约要求

粘贴会话 Cookie 的流程说明了「Provider 必须诚实」在本项目里的具体含义：

1. 解析用户粘贴的 `Cookie` 头，要求 `UID` / `CID` / `SEID` / `KID` 四项（`USERSESSIONID` 可选）；
   缺项时返回 `InvalidArgument`，而不是写入一个残缺的凭据。
2. **先校验，再持久化**：凭据必须先向 `my.115.com/?ct=ajax&ac=nav` 校验通过，**之后**才写入加密
   凭据存储，账号行也才创建。反过来做会留下一个「看似已绑定、首次点击才失败」的账号。
3. 返回 `AuthChallenge.Completed` —— 凭据已经产生，**没有需要轮询的状态**；调用方据此立即刷新列表。
