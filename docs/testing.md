# 测试策略与执行结果

> 本文件记录**实际执行过的**命令与结果。未执行的测试不会被写成「通过」。

---

## 1. 测试分层

| 层 | 位置 | 依赖 | 目的 |
|---|---|---|---|
| **共享契约测试** | `core:provider-testing` | 无 | 一套 29 条规则，**所有** Provider 共用，保证抽象不被某一家网盘带偏 |
| **Provider HTTP / 行为测试** | `provider:p115/src/test`, `provider:baidu/src/test` | MockWebServer | 用**录制的真实响应体**验证 DTO 映射、请求参数、错误码、分页游标与认证流程 |
| **单元测试** | `core:designsystem/src/test` 等 | 无 | 纯函数（格式化器等） |
| **Compose UI 测试** | `feature/*/src/test` | Robolectric（JVM） | 关键页面的状态分支（见 §5.3） |

设计取舍：**把最多精力放在共享契约测试上**。它的价值在于「一次编写、约束所有当前与未来的 Provider」，而不是覆盖某个厂商的分支细节。

> `provider:mock` 模块已**整体删除**（`provider/mock/` 目录与 `settings.gradle.kts` 中的
> `include(...)` 均已移除），因此测试分层里不再有「离线行为测试」这一层。删除理由是它会在
> release APK 内伪造账号与文件：一个能凭空造出账号与文件列表的 Provider，一旦随应用发布，
> 用户就可能把假数据当成真实数据。共享契约规则仍然存在，由 `provider:p115` 接入。

---

## 2. 共享契约测试套件

`core/provider-testing/.../ProviderContractTest.kt` 定义 `ProviderContractRules`（29 个 JUnit 方法）。
任何 Provider 只需提供一个 `ProviderContractFixture` 即可接入：

```kotlin
class P115ProviderContractTest : ProviderContractRules(P115ContractFixture.build())
```

`ProviderContractFixture` 要求提供少量**真值**（一个可列出的目录、一个不存在的 id、一个可播放文件…），规则本身**不提及任何具体文件 id**，因此可复用。

### 2.1 规则清单与它们各自防住的问题

| 规则 | 防住的真实问题 |
|---|---|
| `provider exposes a non-blank identity` | — |
| `capabilities are declared for the provider itself` | `capabilities().providerId` 与实际 id 不一致 |
| `media provider agrees on provider identity` | 刷新令牌无法构造完整的 `CloudFileKey` |
| `declared listing capability matches behaviour` | 声明与行为不符 |
| `declared search capability matches behaviour` | 同上 |
| `unsupported auth flows are rejected rather than faked` | 未声明的授权流程却「成功」 |
| `root is returned with a folder type and matching account scope` | 根节点作用域错误 |
| `listing a folder returns well formed items` | 名称空、跨账号泄漏、文件夹带扩展名、`FileId` 是 URL |
| `listing honours the requested page size` | 静默忽略 `pageSize` |
| `paginating a folder makes progress and eventually terminates` | **游标不前进 → 无限循环** |
| `getFile agrees with the item returned by the listing` | **同一文件两个身份**（本项目真实缺陷） |
| `unknown file ids produce a typed failure rather than a crash` | 崩溃或以 `ParseError` 掩盖「文件不存在」 |
| `path resolution is ordered root first…` | 面包屑顺序错误 |
| `search results are well formed` | 同 listing |
| `blank search keyword is rejected as invalid…` | 空关键词被当成「列出全部」 |
| `operations that are not declared are rejected with Unsupported` | **声明 `UNSUPPORTED` 却返回成功** |
| `rename validates the new name before contacting the vendor` | 空名/含分隔符的名字被发到服务端 |
| `declared media capability matches behaviour` | 同上 |
| `resolved media resource is internally consistent` | 协议不在声明集合内；可刷新但无刷新令牌 |
| `media failure for an unsupported file is typed and never throws` | 抛异常 |
| `playback progress reporting is honest when undeclared` | **伪造播放进度上报成功** |
| `declared preview capability matches behaviour` | — |
| `non previewable files classify as Unsupported rather than failing the call` | 「此格式不支持」被当成错误 |
| `classification without access resolution needs no resolvable file` | 本地分类却返回了访问材料 |
| `preview never embeds vendor urls inside the kind` | 厂商 URL 泄漏进 `PreviewKind` |
| `thumbnail capability matches behaviour and absence is not an error` | 「无缩略图」被当成错误 |
| `listing accounts never contacts the network and never throws` | 账号列表触发网络请求 |
| `session for an unknown account is a typed failure` | 为未知账号伪造会话 |
| `getAccount for an unknown account is a typed failure` | — |

### 2.2 三态语义的一致性

规则对 `CapabilitySupport` 三态的处理必须一致，否则自相矛盾：

- `SUPPORTED` → 不得返回 `Unsupported`，**除非**错误指明了更窄的能力（例如「115 能下载文件，但不能打包下载文件夹」）。这个例外是必要的：宽能力有窄洞是正常的，但洞必须可识别。
- `UNSUPPORTED` → **必须**返回 `Unsupported`（fail closed）。
- `UNVERIFIED` → 两种结果都可接受（字面含义）。

把 `UNVERIFIED` 归入「必须失败」会迫使 Provider 少报真实能力 —— 开发中确实先写成了这样，被 `contract_undeclared_operations_fail_closed` 暴露后修正。

---

## 3. Provider 的 HTTP 测试

`provider/p115/src/test/.../P115ProviderHttpTest.kt`（35 个测试）对本地 `MockWebServer` 驱动真实 Provider。

### 3.1 为什么用 MockWebServer 而不是 mock 掉映射器

如果把 DTO 映射 mock 掉，测试就什么都没验证。用真实 HTTP 往返 + 录制响应体，验证的是**从请求构造到领域模型的完整链路**。

### 3.2 响应体出处

`P115TestBodies.kt` 中的每个响应体都是抓取文档 `api_bodies.json` 的**逐字副本**（仅替换不稳定标识符）。
没有一个是编造的 —— 这一点很重要，否则测试只是在验证「我的实现符合我的想象」。

### 3.3 覆盖的真实陷阱

| 测试 | 固定的文档陷阱 |
|---|---|
| `listing maps folders and files from the recorded body` | §4：目录无 `fid`，靠字段缺失区分 |
| `search maps the string-typed recorded body` | §4：搜索结果的 `s`/`sta`/`t` 是**字符串** |
| `detail uses the recorded flat category body and its paths chain` | §3.2：扁平结构（不在 `data` 下）+ `pick_code` 拼写 |
| `path resolution returns the vendor breadcrumb` | §3.2：根的 `file_id` 是数字 `0`，需归一化 |
| `video metadata maps renditions, audio tracks and trims subtitle urls` | §10.1：字幕 URL 带尾部 `\r\n`；`4K` 与 `1080P` 两种清晰度命名 |
| `office preview carries the url and token pair` | §11.1：WPS 需要 `url` + `token` **成对**；必须 POST |
| `an unsupported preview format becomes a classification not a failure` | §11.1：`code 4011` 是「该文件不支持」，不是错误 |
| `image preview resolves the original url and parses its expiry` | §11.2：`t` 参数即过期时间 |
| `download link expiry is parsed from the signed url` | §7.1：`t` 参数表；§15.2 item 14：文件名取自 `file_name` |
| `trash listing maps rid and reports the safe key requirement` | §8.1：`id` 是 `rid` 不是 `file_id`；字段全是字符串；`rb_pass` |
| `delete sends indexed fids` | §8.2：**必须** `fid[0]=`，`file_ids=` 报 231013 |
| `purge sends indexed rids and the safe key field` | §8.4：**必须** `rid[i]` + `password` |
| `rename uses batch_rename with the indexed name field` | §5.2：`/files/edit` 报 1001；新名须在 `files_new_name[<id>]` |
| `documented errno 1001 maps to InvalidArgument` | §14.2 错误码表 |
| `a safe key error becomes an actionable verification requirement` | §8.4：`errno 0` + `"密钥错误"`，只能靠文本识别 |
| `a server error does not invalidate the stored credential` | §12：网络故障不得导致登出 |
| `integer state envelopes are accepted` | §1.3：`state` 在认证接口是 `1/0` |
| `qr flow extracts credentials from the response body` | §2.1 Step 3：**凭据在响应体，不在 `Set-Cookie`** |
| `manual credential flow verifies before persisting` | 粘贴的 Cookie 必须先经 `ac=nav` 校验，**通过后才**写入存储、才创建账号行 |
| `a cancelled scan is reported as a terminal failure` | §2.1 Step 2：`status: -1` |
| `an invalid qr key ends the challenge as retryable` | §2.1 Step 2：`errno 40199002` |
| `media resolution resolves a missing pickcode via get_info` | §1.5：pickcode 不是身份，但需要可解析 |
| `capabilities declare the documented and the unverified accurately` | §15.4：未验证项不得声明为支持 |
| `upload / share / permanent delete returns a typed unsupported result and sends nothing` | 声明为 `UNVERIFIED`/`UNSUPPORTED` 的操作**不发请求** |

### 3.4 百度网盘专有行为（`provider:baidu`）

百度与 115 在**信封语义**上的差异比在文件系统上更大，因此这一组测试针对的是协议细节而非功能：

| 测试 | 依据 |
|---|---|
| `a 200 response with a non zero errno is a failure` | §0.3：厂商拦截器判 `0 !== errno`，HTTP 状态**不是**失败信号 |
| `an envelope with no code at all is not treated as success` | §0.3：`if (isNaN(i)) i = -1` —— 缺码必须折算为失败，**不能**回退到 HTTP 200 |
| `an empty string errno is not coerced into success` | §1.3：真实响应里有 `"errno":""`，而 JS 中 `Number("") === 0` |
| `error_code is accepted as the success field` | §9.3：`/rest/2.0/membership/…` 用 `error_code` 而非 `errno` |
| `errno minus six is reported as an expired session` | §1.3：`-6` 是唯一的重新登录触发码 |
| `the category listing is read from the info array` | §3.3：`/api/categorylist` 返回 `info[]`，`/api/list` 返回 `list[]` |
| `an element level error inside a successful envelope is honoured` | §3.5：外层 `errno 12` + 元素级 `-9`，必须读元素级 |
| `an asynchronous taskid survives as an exact 64 bit value` | §0.3：厂商自己用正则从原始文本重取 `task_id` 以避开 53 位浮点 |
| `risk control verification is a distinct typed outcome` | §3.6：`errno 132` 带 `authwidget`，是「需要身份验证」而非权限不足 |
| `a partial batch reports the failed item rather than a blanket success` | §3.6：`info[]` 的逐项 `errno` |
| `bdstoken and opera stay in the query rather than the body` | §3.6：`filelist` 里不得含这两个字段 |
| `the recycle list uses the trailing slash endpoint` | §3.10：`"".concat("/api/recycle/list", "/")` |
| `restore reports per item outcomes from faillist` | §3.10：`faillist` 是失败清单 |
| `the streaming ad handshake is completed and yields an hls resource` | §7.3：`errno 133` 是握手第一步，不是失败 |
| `an unavailable rendition is reported honestly` | §7.3：档位不可用返回 `errno 2` |
| `the playlist carries the documented renditions` | §7.3.1：最高 1080P，**2160 不得被提供** |
| `playback progress reporting fails closed` | §7：进度只存在于厂商 `localStorage`，无上报端点 |
| `a cookie that is not logged in is rejected` | §2.4：`loginStatus` 对未登录返回**成功信封** + `isLogin:0` |
| `a cookie without bduss is rejected as invalid input` | §1.5：`BDUSS` 是唯一主令牌，缺项不得写入残缺凭据 |
| `a pasted cookie is verified before it is persisted` | 顺序要求：先校验、后写入 |
| `re-binding the same account reuses its local id` | 去重键是**校验后的**厂商用户 id，不是粘贴内容 |
| `signed urls never leak into the file model` | §8.2：`thumbs`/`docpreview` 是短时效凭据，而 `FileItem` 会被缓存 |
| `thumbnail returns absence rather than a signed url` | 同上；「没有缩略图」是成功 + `null`，不是错误 |
| `office documents classify without a fabricated viewer` | §8.4：`/disk/docview` 需要独立 OAuth 授权，不得伪造 viewer |
| `the capability declaration matches the documented boundaries` | 逐项固定 `SUPPORTED`/`UNVERIFIED`/`UNSUPPORTED` 的边界 |
| `permanent delete is refused and names the narrower capability` | §3.10：彻底删除是另一个端点，不得静默降级 |

### 3.5 测试基础设施中的三个坑（已修）

1. **测试曾经打到真实 115 服务器。**
   `P115HttpClient.resolve()` 用**完整 URL** 去查 host 覆盖表，而表是按**主机名**索引的，因此覆盖从未生效。真实服务器返回了 `990001 登录超时`，测试「看起来在跑」但毫无意义。
   → 改为按主机前缀替换。当前每个测试都会用 `server.takeRequest()` 断言请求落到了本地。

2. **测试曾经永久挂起。**
   MockWebServer 的默认行为是队列为空时**阻塞**客户端。一个漏掉的 `enqueue` 会变成数分钟挂起，掩盖真正的 bug。
   → 改为「队列为空立即返回 500」的 dispatcher，把挂起变成有名有姓的失败。

3. **测试曾经依赖请求的先后顺序（百度）**。
   写操作会先取一次 `bdstoken`（§2.4），因此「重命名请求是第几个」并不固定。
   用位置 `takeRequest()` 断言会让测试在「多了一次无关的令牌请求」时失败 —— 一个与被测性质无关的假信号。
   → 改为按**路径内容**取请求（`takeRequestMatching { it.contains("opera=rename") }`），并给它一个小的排空上限，
   让「请求缺失」快速失败而不是挂起。

---

## 4. 已删除的 Mock Provider

`provider:mock`（`provider/mock/src/test/.../MockProviderBehaviourTest.kt` 等 70 个测试）**已随模块整体删除**，
本节保留一条说明而不是留下一堆失效路径：

- 目录 `provider/mock/` 已删除，`settings.gradle.kts` 中的 `include(":provider:mock")` 已移除；
- `core:provider-testing` **保留**，共享 `ProviderContractRules` 仍在，由 `provider:p115/src/test` 接入；
- 删除理由：该 Provider 会**凭空伪造账号与文件**。它能跑通契约，也正因如此，一旦随 release APK 发布，
  用户就可能把假数据当成自己网盘里的真实内容。测试用的替身必须是**测试专用**的，不能成为可发布的 Provider。

因此「离线也能走到『厂商不支持』分支」这一目标改由别的途径保证：录制响应体驱动的 `provider:p115` HTTP 测试，
以及各 feature 模块的 Compose 状态分支测试。

---

## 5. 实际执行结果

环境：JDK 21.0.12 / Gradle 8.13 / AGP 8.10.1 / Kotlin 2.1.0 / compileSdk 35。

```bash
export JAVA_HOME=<你的 JDK 17+ 路径> ANDROID_HOME=<你的 Android SDK 路径>
# 项目已内置 Gradle wrapper，无需额外配置 PATH
cd <项目根目录>

gradle clean build --console=plain
```

结果：**BUILD SUCCESSFUL**，`app-debug.apk` 产出（`applicationId = com.notdisk.app`，
版本号读取自 AGP 生成的 `output-metadata.json`，非猜测）。`build` 同时跑了 debug 与 release 两个变体。

> `./gradlew test --rerun-tasks` 的实测总数（含 Android 模块的 debug + release 双变体，
> **未按上表去重**）为 **854 个测试、0 失败**。上表按「每个测试计一次」统计。

发布产物 `v0.2.0` 的 APK 实测 75,355,607 bytes，SHA-256
`951bc2b19af2b733527c22f5d55402e7470aae796bf05102ecd66ecacb25948a`，与 Release notes 中记录的
哈希一致（即「发布说明里的哈希」这一项是真实验证过的，不是自己填的）。

### 各模块测试数（去重后，每个测试计一次）

| 模块 | 测试数 | 失败 |
|---|---|---|
| `core:domain`（UseCase，含游标不前进等边界） | 144 | 0 |
| `provider:p115`（共享契约 29 + HTTP 35 + **Cookie 解析 10**） | 74 | 0 |
| `provider:baidu`（共享契约 29 + HTTP 48 + **Cookie 解析 12** + **标识编解码 8**） | 97 | 0 |
| `core:network`（超时/取消/重试/脱敏） | 33 | 0 |
| `feature:player`（错误映射 + 刷新上限 + **架构隔离守卫**） | 32 | 0 |
| `core:designsystem`（格式化器） | 28 | 0 |
| `feature:files`（**陈旧响应丢弃** + 分页终止 + **Compose 状态分支** + **首页三级规则** + **加载骨架与过渡**） | 52 | 0 |
| `core:data`（播放协调器刷新语义） | 17 | 0 |
| `feature:settings`（**Compose 能力矩阵** + **账号摘要**） | 8 | 0 |
| `feature:accounts`（**Compose 授权状态**） | 7 | 0 |
| `feature:preview`（**Compose 预览分支**） | 7 | 0 |
| **合计** | **499** | **0** |

> 说明：Android 模块的 `build` 会同时跑 `testDebugUnitTest` 与 `testReleaseUnitTest`，
> 因此上表按 **debug 变体 + JVM 模块的 `test`** 去重统计，每个测试恰好计一次
> （未去重时约 642，含每个 Android 模块跑两遍）。上表数字取自各模块
> `build/test-results/` 下 JUnit XML 的 `tests` 属性实测值；`provider:mock` 行已随该模块删除而移除。
>
> `feature:files` 的 52 = `FilesScreenTest` 16 + `FilesViewModelTest` 22 + `FolderListingPolicyTest` 11
> + `SkeletonScreenshotTest` 3（把骨架屏与「进入文件夹」整屏渲染为 PNG，见 §5.4）。

### 5.1 Compose UI 测试为何用 Robolectric 而不是模拟器

本环境**没有 KVM，CPU 也不支持虚拟化**（已核实：`/dev/kvm` 不存在，`/proc/cpuinfo` 无
`vmx`/`svm`）。Android 模拟器明确拒绝启动：

```
ERROR | x86_64 emulation currently requires hardware acceleration!
CPU acceleration status: KVM requires a CPU that supports vmx or svm
```

Robolectric 在 JVM 上运行**真实的 Compose 运行时**、真实的布局与语义树，足以验证关键页面
最需要被固定的性质 —— **每个状态分支都渲染出了东西，且没有内容可渲染的分支给出了出路**。
这正是一条容易静默腐化的验收标准（「空目录、加载失败、权限不足……的状态」必须可区分）。

### 5.2 Compose 测试抓到的真实缺陷

`feature:preview` 的测试写完后立刻失败，报「找到 2 个『下载』节点」。原因是
`UnsupportedPreview` 已经在 `EmptyState` 里渲染了下载按钮，而外层的 `DownloadBar` 又渲染了一个 ——
**界面上真的出现了两个一模一样的下载按钮**。修正方式是让 `DownloadBar` 排除 `Unsupported`
分支，并把「只有 1 个下载入口」保留为断言。

这类缺陷任何单元测试都抓不到：两个按钮各自都是正确的。只有把界面真正组合出来才会暴露。

### 5.3 Compose 测试的边界（如实说明）

- 断言的是**存在性与结构**，不是像素可见性：Robolectric 默认画布比手机窄，
  三按钮一行会溢出，溢出的节点会被报告为「未显示」。因此部分断言用
  `assertExists()` 而非 `assertIsDisplayed()`，滚动后才可见的内容用
  ViewModel 的映射函数直接断言。
- **未验证**：Coil 图片加载、WPS 预览的 WebView、Media3 实际播放、HLS 请求头下发。
  这些需要真实设备与网络。
- `@Preview` 函数只验证了可编译（在 Android Studio 之外渲染需要额外工具）。

### 5.4 把界面渲染成 PNG 来复核（`SkeletonScreenshotTest`）

「加载时要有过渡动画」这类验收标准，断言是**说不清楚**的：测试能证明骨架屏存在，不能证明
它看起来像一份文件列表。因此 `feature:files` 里有一个把骨架屏真正画成 PNG 的测试：

```bash
# 产物写到仓库的 dist/screens/（该目录已被 .gitignore 忽略）
./gradlew :feature:files:testDebugUnitTest --tests '*SkeletonScreenshotTest' \
  -Dnotdisk.screenshot.dir=$PWD/dist/screens --console=plain
```

实测输出（`w411dp-h891dp-xhdpi`），均为 822×1782：

| 文件 | 内容 | 复核结论 |
|---|---|---|
| `list-skeleton.png` | 列表骨架 | 10 行，铺满一屏 |
| `grid-skeleton.png` | 网格骨架 | 12 格（4 行 ×3 列），格子为**正方形**（`aspectRatio(1f)`），与 `CloudFileGridTile` 的真实几何一致 |
| `folder-entry-loading.png` | **进入文件夹时的整屏** | 顶栏 + 面包屑（根目录 › 我的文件）+「正在读取「我的文件」…」+ 列表占位 |

网格格子必须是正方形，否则真实数据到达时布局仍会跳动 —— 那正是骨架屏要消除的东西。

第三张图是**整屏**（真实的 `FilesScreen`）而不是裸骨架，它才是「进入文件夹」那一刻用户看到的
东西。它同时是 §5.5 那个缺陷的可视证据：修复前同一时刻渲染的是「这个文件夹是空的」。

两个实现细节值得记录，它们都是**实测**得出的而不是推断的：

1. **不能用 `captureToImage()`**。它在内部调用 `waitForIdle()`，而微光是一个**无限**动画，
   时钟永远不会 idle —— 第一版测试因此以 `ComposeTimeoutException` 失败。改为
   `mainClock.autoAdvance = false` 手动推进时钟，再直接从 `decorView` 绘制。
2. **截图目录不得写死绝对路径**。第一版写死了仓库路径，这会在 CI runner 上失败。现在默认写到
   JVM 临时目录，只有显式传入 `notdisk.screenshot.dir` 时才落到仓库里。

### 5.5 Compose 状态测试第三次漏掉真实缺陷：进入文件夹渲染成「空文件夹」

用户第二次反馈「进入文件夹时依旧很生硬」。定位方式不是猜，而是先写一个**诊断测试**打印
进入文件夹请求在飞行中的状态：

```
DIAG before: items=1 isLoading=false isRefreshing=false crumbs=1
DIAG mid:    items=0 isLoading=false isRefreshing=false isEmpty=true  crumbs=2 folder=dir-a
DIAG after:  items=1
```

`isLoading=false` 而 `items=0` —— 这正是**一次成功的空列表**的形状。屏幕因此在整个请求期间
渲染「这个文件夹是空的」，拿到数据后再硬切成列表。这也解释了为什么第一次的 Crossfade 修复
没有解决手感：淡入淡出是有的，但**淡入的目标内容本身是错的**。

根因在 `loadChildren` 的到达分支：

```kotlin
items = if (isArrival) emptyList() else it.items,
isLoading = isArrival && it.items.isEmpty(),   // ← `it` 是 *上一个* state
```

`items` 已在上一行被清空，所以第二个条件只会**抑制** `isLoading`，永远不会让它为真。从有内容
的目录进入子目录时，该表达式恒为 false。**返回上一级共用这个函数，所以同样受影响。**

修正即 `isLoading = isArrival`：一次到达就意味着「这个目录的内容还不知道」，不由任何条件决定。

修复方式与验证：新增两个测试（进入 / 返回），断言的是**字段组合**而非单个字段 —— 单独看
「items 为空」和「isLoading=false」各自都合理，只有组合起来才是缺陷，这正是它躲过此前所有测试的
原因。两个测试在**还原修复后立刻失败、恢复修复后通过**（已实测，见 §6 的失败输出）。

### 5.6 未执行的测试

| 项 | 原因 |
|---|---|
| **115 真实账号联调** | 本环境无 115 账号与开发者凭据。清单见 [`api-mapping.md`](api-mapping.md) §10.2 |
| **真机/模拟器运行** | 无 KVM、无虚拟化，模拟器无法启动；无实体设备 |
| **Android Lint 门禁** | 已配置但未作为构建门禁执行 |

**未执行的测试不会被描述为通过。**

## 6. 如何运行

```bash
# 全部单元测试
gradle test --console=plain

# 单个模块
gradle :provider:p115:testDebugUnitTest --console=plain
gradle :provider:baidu:testDebugUnitTest --console=plain
gradle :core:domain:test --console=plain

# 只跑共享契约规则
gradle :provider:p115:testDebugUnitTest --tests '*ProviderContractTest' --console=plain
gradle :provider:baidu:testDebugUnitTest --tests '*ProviderContractTest' --console=plain

# 调试单个规则
gradle :provider:p115:testDebugUnitTest \
  --tests '*P115ProviderContractTest.contract_get_file_matches_listing' --console=plain -i

# 构建 APK
gradle :app:assembleDebug --console=plain

# Compose 页面测试（Robolectric，无需设备）
gradle :feature:preview:testDebugUnitTest --console=plain
gradle :feature:accounts:testDebugUnitTest --console=plain

# 只跑某个页面测试
gradle :feature:files:testDebugUnitTest --tests '*FilesScreenTest' --console=plain -i

# 把骨架屏与「进入文件夹」整屏渲染成 PNG（见 §5.4）
gradle :feature:files:testDebugUnitTest --tests '*SkeletonScreenshotTest' \
  -Dnotdisk.screenshot.dir=$PWD/dist/screens --console=plain

# 仪器测试（需设备；本环境无法运行，见 §5.1）
gradle :app:connectedDebugAndroidTest --console=plain
```

---

## 7. 回归测试本身也被验证过（反向验证）

「新加的测试能抓住这个 bug」不是靠断言，而是**把修复还原、看它是否失败**。§5.5 的两个测试实测：

```
FilesViewModelTest > entering a folder shows a loading state rather than an empty one FAILED
FilesViewModelTest > leaving a folder also reports loading rather than empty FAILED
51 tests completed, 2 failed
```

恢复修复后同一命令全绿。一个在缺陷存在时也通过的测试，等于没有测试。

### 6.1 Robolectric 在本环境需要的一处配置

Robolectric 通过**自己的解析器**下载 `android-all` 运行时 jar，**不读 Gradle 仓库配置**。
在 Maven Central 被拦截的网络里，它即使能从镜像拿到也会失败。因此每个承载 Compose 测试的
模块都声明了官方覆盖项：

```kotlin
testOptions {
    unitTests.all {
        it.systemProperty(
            "robolectric.dependency.repo.url",
            "https://maven.aliyun.com/repository/public",
        )
        it.systemProperty("robolectric.dependency.repo.id", "aliyun-public")
    }
}
```

另外 `createComposeRule()` 需要一个宿主 Activity，由 `ui-test-manifest` 提供。它必须声明在
**unit test** 源集上（`testImplementation`）——只声明为 `debugImplementation` 时，
release 变体的单元测试会因为找不到该 Activity 而全数失败。

四个承载 Compose 测试的模块同时用 `@Config(sdk = [35])` 固定 Robolectric 的模拟 SDK 版本，
与 `minSdk 35`（仅 Android 15+）保持一致；Robolectric 默认仍会自行下载对应版本的
`android-all` 运行时。

### 6.1 新增 Provider 时的测试清单

- [ ] 提供 `ProviderContractFixture`，接入 `ProviderContractRules`（**第一步**，它会立刻暴露契约不匹配）
- [ ] 用真实/录制响应写 HTTP 层测试，覆盖请求参数与错误码
- [ ] 断言「声明为不支持的操作不发送请求」
- [ ] 断言签名 URL 不出现在任何任务的持久化字段里
- [ ] 确保测试不会挂起（dispatcher 不留阻塞）
- [ ] 确保测试不会访问真实服务（断言请求全部落在本地服务器）
