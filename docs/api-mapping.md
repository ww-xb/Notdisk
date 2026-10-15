# 115 网盘 API 接入映射表

> **依据**：对 `115.com` 网页版的实测抓取文档（2026-10-14）
> **代码位置**：`provider/p115/`
> **状态图例**：
> - ✅ **已实现并测试** — 端点有文档依据，且本工程有对应的请求构造、响应解析、错误映射与自动化测试。
> - 🔶 **已实现，待真实账号联调** — 代码完整，但本开发环境没有 115 账号，因此只验证到「文档给出的响应形状能被正确解析」这一层。
> - ⏸ **按能力声明拒绝** — 文档仅来自前端源码（`📖`），未在真实请求中验证。**刻意不实现**，接口返回可识别的 `Unsupported`。
>
> 本表严格区分「文档已确认」与「尚待验证」。任何一行都不允许凭推测填写。

---

## 0. 结论摘要

| 维度 | 结果 |
|---|---|
| 已实现的端点数 | 22 |
| 完全未实现、按能力声明拒绝的端点 | 3（上传 OSS 直链、分享创建、播放进度上报） |
| 有文档依据但本工程未接入的端点 | 约 130（P2 增强：标签、离线下载、解压、图片列表等） |
| 真实账号联调 | **未进行**（无 115 账号与开发者凭据），见 §6 |

---

## 1. 认证与会话（`API_REFERENCE.md` §2）

| 能力 | 端点 | 方法 | 文档标记 | 状态 | 代码 |
|---|---|---|---|---|---|
| 取二维码 token | `qrcodeapi.115.com/api/1.0/web/1.0/token` | GET | ✅ | ✅ | `P115AuthProvider.beginQrChallenge` |
| 二维码图片（备用） | `qrcodeapi.115.com/api/1.0/web/1.0/qrcode` | GET | ✅ | ✅ | `AuthChallenge.QrCode.imageUrl` |
| 轮询扫码状态 | `qrcodeapi.115.com/get/status/` | GET | ✅ | ✅ | `P115AuthProvider.pollChallenge` |
| 扫码换票 | `passportapi.115.com/app/1.0/web/1.0/login/qrcode` | POST | ✅ | ✅ | `P115AuthProvider.exchangeForSession` |
| 会话校验 | `my.115.com/?ct=ajax&ac=nav` | GET | ✅ | ✅ | `P115AuthProvider.verifyAccount` |
| 安全密钥状态 | `passportapi.115.com/app/1.0/web/8.2/user/security_key_status` | GET | ✅ | ✅ | `P115AuthProvider.isSafeKeySet` |
| 登出 | `passportapi.115.com/app/1.0/web/1.0/logout/logout/` | POST | 📖 | ✅ | `P115AuthProvider.logout` |
| RSA 公钥 | `passportapi.115.com/app/1.0/web/5.0.1/login/getKey` | GET | ✅ | ⏸ | 未接入密码登录流程 |
| 账号密码登录 | `passportapi.115.com/app/1.0/web/1.0/login/login` | POST | ✅ | ⏸ | 需处理 2FA + 图形验证码，未验证 |
| 短信二次验证 | `passportapi.115.com/app/1.0/web/1.0/code/sms/login` | POST | 📖 | ⏸ | 同上 |
| 二次验证校验 | `passportapi.115.com/app/1.0/web/1.0/login/vip` | POST | 📖 | ⏸ | 同上 |
| OAuth 2.0 token | `passportapi.115.com/app/1.0/1.0/token` | POST | 📖 | ⏸ | **需要开发者 app_id**，见 §6 |

### ⚠️ 实现中必须记住的两个陷阱（已在代码中处理）

1. **凭据在响应体，不在 `Set-Cookie`**（§2.1 Step 3）
   `UID` / `CID` / `SEID` / `KID` 由 `login/qrcode` 的 JSON `data.cookie` 返回，网页端自己写 `document.cookie`。
   任何只读 HTTP 响应头的实现（包括带 CookieJar 的客户端）都会拿不到会话。
   → 代码：`P115AuthProvider.persistSession` 显式读取 `data.cookie`；`P115HttpClient` **刻意不装 CookieJar**。
   → 测试：`qr flow extracts credentials from the response body`。

2. **`state` 类型不统一**（§1.3）
   文件类接口是 `true/false`，认证类接口是 `1/0`。
   → 代码：`P115Json.parseEnvelope` 统一做真值转换。
   → 测试：`integer state envelopes are accepted`。

---

## 2. 文件列表与导航（§3）

| 能力 | 端点 | 方法 | 文档标记 | 状态 | 代码 |
|---|---|---|---|---|---|
| 目录列表 | `webapi.115.com/files` | GET | ✅ | ✅ | `P115FileSystemProvider.listChildren` |
| 文件/目录详情（含路径） | `webapi.115.com/category/get` | GET | ✅ | ✅ | `getFile` / `getPath` |
| 属性详情（最全字段） | `webapi.115.com/files/get_info` | GET | ✅ | ✅ | `getInfoFallback` + `P115PickcodeResolver` |
| 搜索 | `webapi.115.com/files/search` | GET | ✅ | ✅ | `search` |
| 路径 → ID | `webapi.115.com/files/getid` | GET | ✅ | ✅ | `resolvePath` |
| 最近/历史 | `webapi.115.com/history/list` | GET | ✅ | ✅ | `P115MediaProvider.getPlaybackProgress` |
| 分类树 | `webapi.115.com/lists` | GET | 📖 | ⏸ | 未接入；分类筛选改用 `type` 参数 |

### 映射要点

| 115 字段 | 统一模型 | 处理规则 |
|---|---|---|
| `fid` 存在 | `FileType.FILE` | §4：「`if (item.fid)` 是文件」 |
| 只有 `cid` | `FileType.FOLDER` | 目录对象**无 `fid`**，不能靠 `d` 字段（列表响应里没有） |
| `n` / `file_name` | `FileItem.name` | 两个端点的拼写不同 |
| `s`（可能是字符串） | `FileItem.size` | 搜索接口的 `s` 是字符串，必须做类型归一 |
| `ico` | `FileItem.extension` | §4：「图标类型 = 扩展名」 |
| `te` > `tu` > `ptime` | `FileItem.modifiedTime` | 优先数值字段；`t` 是无时区的本地时间串，仅作兜底并按 UTC 解析 |
| `tp` / `ctime` | `FileItem.createdTime` | Unix **秒** → 毫秒（带量级探测，避免二次乘 1000） |
| `pc` / `pick_code` | **不进 `FileId`** | 见 §3 |
| `sha` / `sha1` | `extensions["p115.sha1"]` | 缩略图路径需要它 |
| `path[]` | `FilePage.path` | 厂商自带的完整面包屑 |

### ⚠️ `pickcode` 与文件标识的分离（本工程修正的一个真实缺陷）

**背景**：`API_REFERENCE.md` §1.5 建议「APP 全程用 pickcode 做单文件寻址」。

**但 pickcode 不是稳定身份**：列表接口给 `pc`，详情接口给 `pick_code`，两者可能不同，列表还可能完全不给。
最初的实现把 `pc` 编进了 `FileId`，结果**同一个文件产生两个身份** —— 两条缓存、两条最近记录，播放刷新时找不到刚播过的文件。

**当前设计**：
- `FileId` 只编码稳定身份：`folder:115:cid=<cid>` / `file:115:fid=<fid>:cid=<父目录>`
- pickcode 由 `P115PickcodeCache` 按 `(accountId, fileId)` 缓存，`P115PickcodeResolver` 负责解析：
  1. 先查缓存（浏览过的文件**零额外请求**）；
  2. 未命中则发一次 `files/get_info`（冷启动/深链接**恰好一次**请求）。
- 编解码器容忍历史 id 里的 `:pc=` 段并忽略它，因此旧缓存不会失效。

→ 发现者：共享契约测试 `contract_get_file_matches_listing`
→ 测试：`media resolution resolves a missing pickcode via get_info`

---

## 3. 文件操作（§5 / §8）

| 能力 | 端点 | 方法 | 文档标记 | 状态 | 代码 |
|---|---|---|---|---|---|
| 新建文件夹 | `webapi.115.com/files/add` | POST | ✅ | ✅ | `createFolder` |
| 重命名 | `webapi.115.com/files/batch_rename` | POST | ✅ | ✅ | `rename` |
| 移动 | `webapi.115.com/files/move` | POST | ✅ | ✅ | `move` |
| 复制 | `webapi.115.com/files/copy` | POST | ✅ | ✅ | `copy` |
| 星标 | `webapi.115.com/files/star` | POST | ✅ | ✅ | `setStarred` |
| 删除到回收站 | `webapi.115.com/rb/delete` | POST | ✅ | ✅ | `delete(permanent=false)` |
| 回收站列表 | `webapi.115.com/rb` | GET | ✅ | ✅ | `listTrash` |
| 还原 | `webapi.115.com/rb/revert` | POST | ✅ | ✅ | `restoreFromTrash` |
| 彻底删除 | `webapi.115.com/rb/clean` | POST | ✅ | ✅ | `purgeFromTrash` |
| 置顶 | `webapi.115.com/files/top` | POST | ✅ | ⏸ | 未接入（非首阶段必需） |
| 备注/描述 | `webapi.115.com/files/desc` | POST | 📖 | ⏸ | 未接入 |
| 排序设置 | `webapi.115.com/files/order` | POST | 📖 | ⏸ | 未接入（排序按次请求传参） |
| 上传（OSS 直传） | `uplb.115.com/3.0/sampleinitupload.php` | POST | 📖 | ⏸ | **刻意不实现**，见下 |
| 分享创建 | `webapi.115.com/usershare/share` | POST | 📖 | ⏸ | **刻意不实现**，见下 |
| 我的分享列表 | `webapi.115.com/usershare/list` | GET | ✅ | ⏸ | 未接入 |

### ⚠️ 索引参数：本 API 最大的坑（§15.2 item 3）

所有批量接口要求**带下标的参数名**，用别的写法一律报错：

| 接口 | 正确 | 文档实测的错误写法与结果 |
|---|---|---|
| `files/move` | `fid[0]=…&fid[1]=…` | `file_ids=` → `errno 1001 参数错误` |
| `files/copy` | `fid[0]=…&fid[1]=…` | 同上 |
| `rb/delete` | `fid[0]=…&fid[1]=…` | `file_ids=` → `errno 231013 删除失败，没有选择文件(夹)` |
| `rb/revert` | `rid[0]=…&rid[1]=…` | `rid[]=` → `errno 231012 还原失败` |
| `rb/clean` | `rid[0]=…&rid[1]=…&password=` | `rid[]=` / 逗号 / `all=1` 全部无效 |

→ 代码：`P115HttpClient.postIndexedForm(List<Pair<String,String>>)` —— 刻意用有序 Pair 列表而非 `Map`，因为 `Map` 会静默覆盖重复键。
→ 测试：`delete sends indexed fids`、`purge sends indexed rids and the safe key field`

### ⚠️ 重命名必须走 `batch_rename`（§5.2）

流传的 `/files/edit` 传 `cid&file_id&n` **实测报 `errno 1001`**。正确做法是 `POST /files/batch_rename` 且新名放在 `files_new_name[<id>]`。
→ 测试：`rename uses batch_rename with the indexed name field`

### ⚠️ 写操作必须 POST

`files/star`、`files/desc` 用 GET 返回 `405 METHOD NOT ALLOWED`（`errno 980005`）。
→ 代码：全部写操作用 `postForm` / `postIndexedForm`。

### 🔶 安全密钥（§8.4）

`rb/clean` **强制要求账号安全密钥**。失败响应的 `errno` 是 `0`，只有 `error: "密钥错误"` 可辨识。
→ 代码：`mapP115Error` 以消息文本识别该情况，映射为 `CloudError.AdditionalVerificationRequired`（`verificationKind = "115.safe_key"`），UI 据此弹输入框。
→ 安全密钥**不持久化**：由 `purgeFromTrash(safeKey=…)` 逐次传入。
→ 测试：`a safe key error becomes an actionable verification requirement`

### ⏸ 上传与分享为何刻意不实现

引用 `API_REFERENCE.md`：

| 项 | 原文依据 |
|---|---|
| 上传 | §6 全节标记 `📖`；§15.4「上传 OSS 直传的实际回调 —— 未实测（避免写入生产数据）」 |
| 分享 | §12.2 创建接口标记 `📖`；§15.4 未列其为已实测 |

上传会写入真实账号，分享会把文件发布到公网 URL。在协议未经验证的情况下实现这两者，是本工程可以做出的**风险最高的行为**。因此：

- `CloudCapabilities.operations.upload.supported = UNVERIFIED`
- `CloudCapabilities.operations.share = UNVERIFIED`
- 对应方法返回 `CloudError.Unsupported` 并附中文说明，**不发送任何请求**
- 测试断言这一点（`upload returns a typed unsupported result and sends nothing`、`share …`）

这使得「能力声明」与「实际行为」完全一致，也符合项目要求第 9 条与 §18「不支持的功能能返回明确结果」。

---

## 4. 下载（§7）

| 能力 | 端点 | 方法 | 文档标记 | 状态 |
|---|---|---|---|---|
| 获取直链 | `webapi.115.com/files/download` | GET | ✅ | ✅ |
| 文件夹打包下载 | `115.com/?ct=browser&ac=download_folder` | GET | 📖 | ⏸ |

### 映射

`file_url` 是**短时效签名链接**，其 query 参数在 §7.1 有完整表：

| 参数 | 含义 | 本工程用法 |
|---|---|---|
| `t` | 过期时间戳（Unix 秒） | `DownloadSource.expiresAt`（`parseExpiryFromUrl`） |
| `u` | user_id | 不解析 |
| `s` | 单次限速 | 不解析 |
| `d` | 下载票据 | 不解析 |
| `k` | 签名 | **绝不记录到日志** |
| `us` / `uc` / `v` | 大小上限/并发/版本 | 不解析 |

**关键设计**：该 URL **不进入 `TransferTask`**。任务记录只保存 `CloudFileKey`，传输前由 `prepareDownload` 重新取链。这样签名 URL 在类型层面就无法被持久化。
→ §7.1 原文：「建议每次下载前重新获取，不要缓存」
→ §15.2 item 14：直链**不带** `Content-Disposition`，文件名取自 `file_name` 字段（已处理）

---

## 5. 媒体播放（§10）

| 能力 | 端点 | 方法 | 文档标记 | 状态 |
|---|---|---|---|---|
| 视频元信息 + 播放地址 | `webapi.115.com/files/video` | GET | ✅ | ✅ |
| HLS 实际取流 | `115.com/api/video/m3u8/<pc>.m3u8` → `cpats01.115.com/...` | GET | ✅ | ✅（交给播放器跟随重定向） |
| 字幕 | `cpats01.115.com/.../subtitle/N.srt` | GET | ✅ | ✅ |
| 播放进度上报 | *未定位* | — | 📖 | ⏸ **刻意不实现** |
| 文件夹连播 | `aps.115.com/getFolderPlaylong` | GET | 📖 | ⏸ |

### 映射

| 115 字段 | 统一模型 | 说明 |
|---|---|---|
| `video_url` | `MediaResource.url` | **逻辑地址**，播放器须跟随重定向，**不能硬编码 `cpats01` 路径**（随机段每次都变） |
| （协议） | `MediaProtocol.HLS` | §10.2 实测产物是 m3u8 |
| `definition_list` | `MediaResource.renditions` | 码率 → 清晰度名；`4K` 与 `1080P` 两种命名都要处理 |
| `definition_index` | 未使用 | 仅序号，`renditions.id` 用码率更稳定 |
| `multitrack_list` | `MediaResource.audioTracks` | 无 id，用**数组下标**作 id（对应 `audio/{N}.m3u8`） |
| `subtitle_info[].url` | `MediaSubtitleTrack.url` | **必须 `trim()`**，实测带尾部 `\r\n` |
| `play_long` | `MediaResource.durationMs` | 秒 → 毫秒 |
| `width` / `height` | 同名字段 | 直接映射 |
| `file_status` | 门禁 | `≠1` → `CloudError.Unsupported`（转码中/受限），不是传输错误 |

### ⚠️ 三个已在代码中处理的陷阱

1. **不能拼 CDN 路径**（§10.2 实现建议 1）：随机 32-hex 段与日期段每次都不同 → 只用 `video_url`，由播放器跟随。
2. **HLS 音视频分离**（§10.2 实现建议 2）：产物是独立视频轨 + `audio/{N}.m3u8`。`MediaResource.audioTracks` 显式表达这一点，播放器不必自己发现。
3. **字幕 URL 带 `\r\n`**（§10.1 警告）：已 `trim()`，并由测试 `trims subtitle urls` 固定。

### 请求头

CDN 校验 `Referer` / `Origin` / `User-Agent`（§1.4）。`MediaResource.headers` 携带它们，播放器通过 Media3 的 `OkHttpDataSource` 注入。**这些头不是账号凭据**，与 `Cookie` 是两类不同的东西。

### ⏸ 播放进度上报

§10.3 只列出 `getFolderPlaylong` 与一个**推测的** `setPlaylong`；§15.4 明确把「播放进度上报接口」列为未定位。
→ `reportPlaybackProgress` 返回 `Unsupported`，能力声明为 `UNVERIFIED`；**不伪造成功**。
→ 读取进度走已实测的 `history/list` 的 `current_time`/`play_long`（§3.8）。

---

## 6. 预览（§11）

| 能力 | 端点 | 方法 | 文档标记 | 状态 |
|---|---|---|---|---|
| 统一预览入口 | `webapi.115.com/files/preview` | POST | ✅ | ✅ |
| 图片缩略图/原图 | `webapi.115.com/files/image` | GET | ✅ | ✅ |
| 不支持的格式 | `files/preview` → `code 4011` | POST | ✅ | ✅ 映射为 `PreviewKind.Unsupported` |
| 压缩包解压 | `files/extract_*` | — | 📖 | ⏸ |

### 映射

| 115 响应 | 统一模型 |
|---|---|
| `data.url` + `data.token` | `PreviewKind.OfficeDocument(OfficeViewer(url, token, expiresAt, refreshBeforeSeconds, canEdit))` |
| `data.expire_at` | `OfficeViewer.expiresAt`（Unix 秒 → 毫秒） |
| `data.refresh_before` | `OfficeViewer.refreshBeforeSeconds` |
| `data.origin_url` | `PreviewResource.url`（原图优先） |
| `data.all_url[]` | 按 `_<size>` 后缀选尺寸 |
| `code 4011` | **`PreviewKind.Unsupported`（成功返回）**，不是错误 |

### ⚠️ `4011` 的语义

§11.1 实测：不支持的格式返回 `{"state":false,"code":4011,"msg":"暂不支持此文件格式预览"}`。

这**不是失败**，而是「该文件没有在线预览」。两者对 UI 的含义完全不同：
- 能力失败 → 「这个网盘不能预览」
- 单文件不支持 → 「这个文件看不了，可以下载后用本地应用打开」

代码把 `4011` 转成**成功的** `PreviewResult(kind = Unsupported)`，UI 据此显示下载入口。
→ 测试：`an unsupported preview format becomes a classification not a failure`

### 本地分类（零网络请求）

列表渲染不能每个文件发一次预览请求（200 行的目录会发 200 次）。因此 `PreviewProvider.preview(resolveAccess = false, hint = …)` 只依据调用方已有的文件名/扩展名分类，**不发请求**。
→ 这要求接口传入 `PreviewHint`：115 的 `FileId` 只含 `cid`/`fid`/`pickcode`，不含文件名，没有 hint 就无法本地分类。
→ 测试：`classification without access resolution sends no request`

---

## 7. 空间与用户（§9 / §13）

| 能力 | 端点 | 方法 | 文档标记 | 状态 |
|---|---|---|---|---|
| 空间统计 | `webapi.115.com/files/index_info` | GET | ✅ | ✅ |
| VIP 限制 | `webapi.115.com/user/vip_limit` | GET | ✅ | ⏸ |
| 用户设置 | `webapi.115.com/user/setting` | GET | ✅ | ⏸ |
| 标签系统 | `webapi.115.com/label/*` | — | ✅ | ⏸ |

### ⚠️ 厂商字段拼写错误（§9.3）

`type_summury`（不是 summary）、`pecent`（不是 percent）。
代码**保留原拼写**：写成正确拼写会静默取到 0。

`mark` 是 VIP 等级**位图**，`1023` = 年费VIP；用 §13.2 的 `vip_list` 反查名称（`P115Membership.labelFor`）。

---

## 8. 错误码映射（§14）

`provider/p115/.../api/P115ErrorMapping.kt` 是唯一的映射点。已覆盖：

| errno / code | 文档含义 | 统一 `CloudError` | 备注 |
|---|---|---|---|
| `40101045` | 用户名或密码错误 | `NotAuthenticated` | |
| `40101010` / `70128` | 需二次验证 | `AdditionalVerificationRequired` | `kind = 115.two_factor` |
| `40101060` | 公钥过期 | `TokenExpired(canRefresh = true)` | 文档要求重取 key 并**重放一次** |
| `40111010` | 账号异常 | `PermissionDenied` | 提示走申诉 |
| `40101017` | 老乡验证失败 | `InvalidArgument` | 扫码时序/来源校验 |
| `40199002` | `key invalid` | `ResourceUrlExpired(refreshable = true)` | 二维码失效，重取 |
| `20026` | 短信风控 | `AdditionalVerificationRequired` | `kind = 115.sms` |
| `1001` | 参数错误 | `InvalidArgument` | 通常是**本客户端**用错参数名 |
| `231013` | 删除失败，未选择 | `InvalidArgument` | `fid[]` 写法错误 |
| `231012` | 还原失败，未选择 | `InvalidArgument` | `rid[i]` 写法错误 |
| `4500032` | 空间不足 | `QuotaExceeded` | |
| `990009` | 操作尚未完成 | `ServerError` | 可重试 |
| `980005` | 405 | `Unknown`（消息说明须 POST） | 客户端 bug，单独可辨 |
| `21001` | 标签不能为空 | `InvalidArgument` | |
| `4011` | 暂不支持预览 | `Unsupported(capability = preview.format)` | 预览层再转成 `PreviewKind.Unsupported` |
| `-601` | 无效文件类型 | `InvalidArgument` | 上传 |
| `-602` | 空文件/文件夹 | `InvalidArgument` | 上传 |
| `-700` / `-701` | 无效图片 | `IncompatibleResource` | 上传 |
| `-702` | 上传已停止 | `Unknown` | |
| `-800` | 空间不足 | `QuotaExceeded` | |
| `0` + `"密钥错误"` | 安全密钥校验失败 | `AdditionalVerificationRequired` | **只能靠消息文本识别** |

未枚举的 code 走保守回退：`≥500` → `ServerError`；HTTP 401/403 → `TokenExpired`；其余 → `Unknown` 并**保留厂商 code** 供诊断。

---

## 9. ⚠️ 本工程对文档的一处有意偏离

**`API_REFERENCE.md` §1.5 建议「APP 全程用 pickcode 做单文件寻址」。本工程不把 pickcode 放入文件标识。**

理由（修正了一个真实缺陷）：pickcode 在不同端点字段名不同（`pc` / `pick_code`），可能缺失，也可能变化。把它编进 `FileId` 会让**同一文件拥有多个身份**，破坏缓存键、最近访问记录和媒体刷新。详见 §2 末尾。

`pickcode` 仍然被优先使用 —— 它只是存放在 `(accountId, fileId)` 索引的缓存里，而不是身份本身。

---

## 10. 需要项目方提供 / 待真实联调的事项

### 10.1 需要提供

| 项 | 用途 | 缺失时的影响 |
|---|---|---|
| 115 开发者 `app_id` + 回调地址 | OAuth 2.0 登录（§2.4） | 无法启用 OAuth；当前只有扫码登录可用（**扫码不需要开发者凭据**） |
| 一个测试用 115 账号 | 真实联调 | 所有 ✅ 项只能验证到「文档响应形状可解析」这一层 |

配置入口已就绪：`P115Configuration(oauthClientId, oauthRedirectUri)`。
未配置时 `oauthSupport = UNSUPPORTED`，`notes` 会给出中文说明，UI 据此隐藏 OAuth 按钮而**不是**弹出一个必然失败的入口。

### 10.2 待真实联调清单

以下各项**代码已完成、单元/HTTP 测试已通过**，但未在真实账号上跑过：

| # | 项 | 风险 | 验证方式 |
|---|---|---|---|
| 1 | 扫码登录全链路 | 低（§2.1 为实测） | 用真实账号扫一次码 |
| 2 | 会话有效性、失效后重新授权 | 低 | 手动登出/等待过期 |
| 3 | 根目录与多级目录浏览 | 低 | 浏览实际目录 |
| 4 | 分页（大目录滚动到底） | 中 — `count` 语义文档未完全明确 | 找一个 >200 项的目录 |
| 5 | 视频播放（HLS 双轨） | **中高** — 音视频分离需要播放器正确处理 | 播一个 115 已转码的视频 |
| 6 | 字幕、清晰度切换、多音轨 | 中 | 同上 |
| 7 | 图片预览与缩略图 | 低 | 打开相册目录 |
| 8 | Office 文档预览（WPS） | 中 — 需要 WebView 加载外部 URL | 打开一个 docx |
| 9 | 下载直链与断点续传 | 中 | 下载一个大文件并暂停/续传 |
| 10 | 新建/重命名/移动/复制/删除 | 中 — 写操作 | 在**测试目录**内操作 |
| 11 | 回收站还原 | 低 | 同上 |
| 12 | 彻底删除（安全密钥） | 中 — 不可逆 | 需先设置安全密钥 |
| 13 | 手机号/风控触发时的行为 | 高 — 可能触发风控 | **不建议主动测试** |

**联调建议**：在 115 根目录下新建一个专用测试目录，所有写操作都在其中进行。

---

## 附：本表所依据的代码与测试

| 关注点 | 代码 | 测试 |
|---|---|---|
| HTTP 与信封归一 | `api/P115HttpClient.kt`, `api/P115Json.kt` | `P115ProviderHttpTest` |
| 错误码映射 | `api/P115ErrorMapping.kt` | `P115ProviderHttpTest`（1001 / 密钥错误 / POST-only） |
| 文件标识 | `P115FileIdCodec.kt` + `P115PickcodeCache` | `P115ProviderContractTest`, `P115ProviderHttpTest` |
| DTO → 领域模型 | `P115FileMapper.kt` | `P115ProviderHttpTest`（逐字段断言） |
| 认证 | `auth/P115AuthProvider.kt` | `P115ProviderHttpTest`（QR 三步 + 凭据在响应体） |
| 能力声明 | `P115CloudProvider.kt` | `capabilities declare the documented and the unverified accurately` |
| 共享契约 | — | `P115ProviderContractTest`（29 条规则） |
