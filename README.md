# Notdisk

一个 Kotlin + Jetpack Compose 的 Android 网盘聚合客户端。通过统一的 Provider 抽象把不同网盘的
API 封装成同一套文件系统、媒体播放与文件预览能力。

**首个真实接入：115 网盘**（依据对 115.com 网页版的实测抓取文档）。

[![CI](https://github.com/ww-xb/Notdisk/actions/workflows/ci.yml/badge.svg)](https://github.com/ww-xb/Notdisk/actions/workflows/ci.yml)

---

## 当前状态（如实说明）

| 维度 | 状态 |
|---|---|
| 工程可构建 | ✅ `./gradlew build` → `app-debug.apk`（约 25 MB，debug + release 双变体） |
| 自动化测试 | ✅ **438 个测试全绿，0 失败**（含 Robolectric Compose 页面测试） |
| 115 接入 | ✅ 22 个端点已实现并测试；3 项刻意未实现（见下） |
| **真实账号联调** | ❌ **未进行** —— 开发环境没有 115 账号与开发者凭据 |
| OAuth 登录 | ⏸ 需要开发者 `app_id`，入口已就绪，**未伪造任何凭据** |
| 上传 / 分享 | ⏸ 文档依据仅为前端源码（`📖`），**刻意不实现** |

「未进行真实联调」不等于「不可用」：所有 ✅ 项都有完整的请求构造、响应解析、错误映射与自动化测试，
测试用的是抓取文档附录里**逐字记录的响应体**。缺的是最后一步真机验证，清单见
[`docs/api-mapping.md`](docs/api-mapping.md) §10.2。

---

## 快速开始

### 环境

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 17+（开发用 21） | AGP 8.10 要求 17+ |
| Gradle | 8.13（wrapper 已内置） | 直接用 `./gradlew`，无需安装 |
| Android SDK | compileSdk 35 / minSdk 26 | 需设置 `ANDROID_HOME` |
| Kotlin | 2.1.0 | |
| AGP | 8.10.1 | |

```bash
# 构建
./gradlew :app:assembleDebug

# 全部单元测试
./gradlew test

# 只跑共享契约规则（所有 Provider 共用的一套约束）
./gradlew :provider:p115:testDebugUnitTest --tests '*ProviderContractTest'
```

### 首次使用

应用内置一个**离线演示网盘（Mock）**，无需任何账号即可完整体验：

1. 启动后进入「文件」页，默认使用 Mock 账号，可浏览目录树、分页、播放 HLS 视频、预览图片与文本；
2. 「账号」页可添加 115 账号 —— **扫码登录不需要开发者凭据**，直接用 115 App 扫码即可；
3. 「设置」页可查看每个网盘的**能力声明**（哪些功能已实现、哪些未验证、哪些不支持）。

Mock Provider 不是玩具：它与 115 Provider 实现**同一套契约**，并跑**同一套契约测试**，
且刻意保留了与 115 相同的功能缺口，以便离线也能走到「该网盘不支持」的 UI 分支。

---

## 架构一览

```
app (MainActivity · 导航 · Hilt 图)
 ├── feature:accounts / files / player / preview / settings
 ├── core:designsystem           纯 UI，不知道「网盘」是什么
 ├── core:domain                 52 个 UseCase（纯 Kotlin，无 Android 依赖）
 ├── core:data                   Repository · 播放协调器 · 传输 · 缓存 · DI
 ├── core:database               Room · DataStore
 ├── core:network                OkHttp 基础设施 · 错误映射 · 重试策略
 ├── core:provider-api           CloudProvider 契约
 ├── core:model                  FileItem · MediaResource · CloudCapabilities
 ├── core:common                 CloudResult · CloudError · 日志脱敏
 ├── provider:mock               离线参考实现
 └── provider:p115               115 网盘真实接入
```

详见 [`docs/architecture.md`](docs/architecture.md)。

---

## 三条被类型系统强制的架构约束

项目最容易在演进中被破坏的三件事，本工程用**类型**而非注释来保证：

### 1. 播放器与 Provider 完全解耦

播放器只消费 `MediaResource`。它的依赖里**没有** `provider:p115`，因此写不出网盘判断逻辑。
资源解析与刷新全部在 `MediaPlaybackCoordinator`。

### 2. 文件标识 / 媒体 URL / 授权凭证三者不可互换

| 类别 | 类型 | 可否持久化 |
|---|---|---|
| 文件标识 | `FileId` / `CloudFileKey` | ✅ |
| 媒体 / 下载 URL | `MediaResource.url`、`DownloadSource.url` | ❌ **禁止** |
| 授权凭证 | `P115Credential` | ⚠️ 仅加密存储 |

`TransferTask` 里**没有 URL 字段**：任务只存 `(providerId, accountId, fileId)`，传输前现取链接。
签名 URL 在类型层面就无法被写进任务记录。

### 3. 新增网盘不修改公共流程

分发只经 `ProviderRegistry`。**代码中没有 `when (providerId)`**。
注册通过 Hilt multibinding 完成，`ProviderRegistryModule` 不 import 任何具体 Provider。

---

## 115 接入要点

完整的接口映射表见 [`docs/api-mapping.md`](docs/api-mapping.md)，能力声明见
[`docs/capabilities.md`](docs/capabilities.md)。以下是几个**会决定集成成败**的细节：

### 凭据在响应体，不在 `Set-Cookie`

抓取文档 §2.1 Step 3：`UID`/`CID`/`SEID`/`KID` 由 `login/qrcode` 的 JSON
`data.cookie` 返回，网页端自己写 `document.cookie`。
→ `P115HttpClient` **刻意不装 CookieJar**；凭据显式从响应体读取。

### 批量接口必须用带下标的参数名

```kotlin
fid[0]=…&fid[1]=…      // move / copy / rb/delete
rid[0]=…&rid[1]=…      // rb/revert / rb/clean
```

用 `file_ids=` 会得到 `errno 1001`；用 `rid[]=` 会得到 `errno 231012`。
→ `postIndexedForm(List<Pair<String,String>>)` —— 刻意用有序 Pair 列表，因为 `Map` 会静默覆盖重复键。

### pickcode 不是文件身份

文档建议「全程用 pickcode 寻址」，但 pickcode 在不同端点字段名不同（`pc` / `pick_code`）、可能缺失。
把它编进 `FileId` 会让**同一文件拥有两个身份**（本工程实际踩过，被共享契约测试抓到）。
→ `FileId` 只编码 `cid`/`fid`；pickcode 存在按账号分区的缓存里，未命中时用一次 `files/get_info` 解析。

### 刻意不实现的三项

| 能力 | 文档依据 | 原因 |
|---|---|---|
| 上传（OSS 直传） | §6 全节 `📖`；§15.4「未实测」 | 协议未验证，失败会写入真实账号 |
| 分享创建 | §12.2 `📖` | 会把文件发布到公网 URL |
| 播放进度上报 | §10.3 端点未定位 | **不伪造成功** |

三者都返回带说明的 `CloudError.Unsupported` 且**不发送任何请求**（有测试断言 `requestCount == 0`）。

---

## 测试

```bash
./gradlew test
```

438 个测试 = 纯 JVM 模块 144 个 + Android 模块 294 个（debug 与 release 变体各跑一遍，双倍确认）。

| 层次 | 位置 | 作用 |
|---|---|---|
| **共享契约测试** | `core:provider-testing` | 29 条规则，**所有 Provider 共用** |
| **Compose 页面测试** | `feature/*/src/test`（Robolectric） | 状态分支可区分、无预览时有出路 |
| Provider 行为测试 | `provider:mock/src/test` | 离线验证分页/错误/多账号/传输流 |
| Provider HTTP 测试 | `provider:p115/src/test` | 用**录制的真实响应体**验证映射与参数 |
| UseCase 测试 | `core/domain/src/test` | 144 个测试，含「游标不前进」等边界 |
| 架构守护测试 | `feature:player/src/test` | 断言播放器模块**没有** Provider 依赖 |

共享契约测试抓到了本工程的真实缺陷（`FileId` 身份不稳定、传输流不终止、能力声明与行为不符）。
详见 [`docs/testing.md`](docs/testing.md)。

---

## 文档

| 文档 | 内容 |
|---|---|
| [`architecture.md`](docs/architecture.md) | 分层、依赖方向、约束如何被强制 |
| [`provider-contract.md`](docs/provider-contract.md) | **新增网盘的操作手册** |
| [`api-mapping.md`](docs/api-mapping.md) | 115 逐接口映射表 + 待联调清单 |
| [`capabilities.md`](docs/capabilities.md) | 能力声明的依据 |
| [`security.md`](docs/security.md) | 三类敏感信息的隔离 + **架构限制的如实报告** |
| [`testing.md`](docs/testing.md) | 测试策略与**实际执行结果** |

---

## 需要项目方提供

| 项 | 用途 | 缺失影响 |
|---|---|---|
| 115 开发者 `app_id` + 回调地址 | OAuth 2.0 登录（§2.4） | 无法启用 OAuth；**扫码登录仍可用**（不需要开发者凭据） |
| 一个测试用 115 账号 | 真实联调 | 见 `docs/api-mapping.md` §10.2 |

配置入口已就绪：`P115Configuration(oauthClientId, oauthRedirectUri)`。
未配置时 UI 隐藏 OAuth 入口并给出中文说明，而**不是**弹出一个必然失败的按钮。

---

## 后续接入新网盘

1. 分析官方文档，建立能力映射表
2. 实现 `CloudProvider` 的五个子接口
3. **接入共享契约测试**（`ProviderContractRules`）—— 它会立刻暴露契约不匹配
4. 用录制响应写 HTTP 层测试
5. 在 Hilt 图里注册

**不需要修改任何现有 UI、播放器或其它 Provider。** 详细步骤见
[`docs/provider-contract.md`](docs/provider-contract.md)。
