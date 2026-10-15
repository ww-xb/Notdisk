# 百度网盘 API 接入映射表

> **依据**：`/data/workspace/baidu_reverse/docs/` 中的逆向文档（主文档 + 4 份分模块报告，实测日期 2026-10-14）
> **代码位置**：`provider/baidu/`
> **状态图例**：
> - ✅ **已实现并测试** — 端点有文档依据（`✅ 实测` 或 bundle 完整字面量 + 调用点），且本工程有对应的请求构造、响应解析、错误映射与自动化测试。
> - 🔶 **已实现，声明为 UNVERIFIED，待真实账号联调** — 代码完整，但文档依据是**推断**（`⚠️`）：bundle 中没有完整字面量，只有 `actionType` 透传的旁证。接口按能力声明返回可识别的结果。
> - ⏸ **按能力声明拒绝** — 协议有文档，但在本工程的能力边界之外（需要内嵌 JS 引擎、需要开发者凭据、或成功响应从未被观测）。**刻意不实现**，接口返回可识别的 `Unsupported` 且**不发送请求**。
> - ❌ **厂商不存在** — 文档明确记录该端点不存在。
>
> 本表严格区分「文档已实测」「文档推断」「本工程刻意拒绝」与「厂商不存在」。任何一行都不允许凭推测填写。

---

## 0. 结论摘要

| 维度 | 结果 |
|---|---|
| 已实现的端点数 | **10**（`/api/list`、`/api/search`、`/api/filemetas`、`/api/filemanager`、`/api/create`、`/api/recycle/{list,restore,delete}`、`/api/streaming`、`/api/gettemplatevariable`、`/api/loginStatus`、`/api/user/getinfo`、`/api/quota`） |
| 声明 `SUPPORTED` 的能力 | 浏览全链路；`createFolder`；`rename`；回收站四项；视频播放（HLS）；图片/文本预览；配额 |
| 声明 `UNVERIFIED` 的能力 | `move`/`copy`/`delete`（`opera` 值系推断）；上传；分享创建；字幕消费；PDF/Office 预览；播放进度 |
| 声明 `UNSUPPORTED` 的能力 | 下载（需内嵌 JS 引擎）；音频流；单独音轨；后缀过滤 |
| 真实账号联调 | **未进行**（无百度账号），见 §9 |

### 0.1 与 115 接入相比，三个必须不同的设计决定

| # | 决定 | 原因 |
|---|---|---|
| 1 | **`FileId` 编码绝对路径**，不是 `fs_id` | 百度 Web API 是**按路径寻址**的：`filemetas` 的 `target`、`filemanager` 的 `filelist`、`streaming` 的 `path`、`download` 的 `fidlist` 之外全部收路径。`fs_id` 存在且稳定，但本工程驱动的端点不接受它（唯一例外是 `/api/recycle/*` 的 `fidlist`）。详见 §2.1。 |
| 2 | **不装 CookieJar，`BDUSS` 显式传递** | §1.5 记录 `BDUSS` 是唯一的主令牌，而 passport 的扫码流程需要 `sig`（由私有 JS 计算）。因此本工程只提供粘贴 Cookie 一条链路，凭据显式进出加密存储，不由 HTTP 层隐式管理。详见 §1.1。 |
| 3 | **播放不做音轨分离、下载完全不实现** | §7.4 实测分片 URL **自带签名，不需要 Cookie/UA/Referer**；§7.6 记录音频走 dlink 而非 `/api/streaming`；§5.2 记录下载签名需要执行**服务端实时下发的 JavaScript**。详见 §6、§7。 |

---

## 1. 认证与会话

### 1.1 唯一实现的流程：`MANUAL_CREDENTIAL`

| 步骤 | 端点 | 方法 | 文档标记 | 状态 | 代码 |
|---|---|---|---|---|---|
| 粘贴 Cookie 解析 | — | — | §1.5 | ✅ | `BaiduCookieParser` |
| **写入前**校验会话 | `pan.baidu.com/api/loginStatus` | GET | §2.4 | ✅ | `BaiduAuthProvider.beginManualCredential` |
| 取用户资料（显示名 / `uk`） | `pan.baidu.com/api/user/getinfo` | GET | §9.1 | ✅ | `refreshProfile` |
| 取 `bdstoken`（写操作） | `pan.baidu.com/api/gettemplatevariable` | GET | §2.4 | ✅ | `bdstokenFor` |
| 配额 | `pan.baidu.com/api/quota` | GET | §9.2 | ✅ | `getStorageQuota` |
| 登出 | `pan.baidu.com/api/logout` | POST | §2.5 | ✅ | `logout` |

**要求**：`BDUSS` 必填，`STOKEN` 与设备 Cookie 可选但**一律保留**。

保留设备 Cookie 不是顺手为之：§1.5 记录 `BAIDUID`/`BIDUPSID`/`PSTM`/`ndut_fmt` 是设备与会话指纹，
而百度有风控（§1.4：`x-anti-risk-hint`、`errno 132`、`errno -20`）。**删掉它们会让一个本来有效的会话
看起来像新设备**。因此 `BaiduCredential` 保存整个 cookie jar，同时明确知道哪一个是凭据。

**顺序为什么是「先校验，再持久化」**：粘贴的 Cookie 可能失效、被截断或根本不属于百度。
先写入再发现问题，会留下一个「看似已绑定、首次点击才失败」的账号。
`loginStatus` 是廉价、只读、文档化的端点，因此用它做写入前检查。

**⚠️ 这里有一个必须记住的陷阱（已在代码中处理）**：
`loginStatus` 对**未登录**请求返回的是 `{"errno":0,"isLogin":0}` —— 一个**成功的信封**。
只检查 `errno` 会把未登录的粘贴当成有效会话。代码因此同时检查 `isLogin`
（`BaiduProviderHttpTest.a cookie that is not logged in is rejected` 断言了这一点）。

### 1.2 刻意不提供的流程

| 流程 | 状态 | 依据 |
|---|---|---|
| `QR_CODE` | ⏸ 拒绝 | §2.2 的 10 步扫码链路中，**第 7 步** `passport.baidu.com/v3/login/main/qrbdusslogin` 需要 `sig` 参数，由 Passport 前端 JS（`alg=v3` + `shaOne`）生成。文档明确：「自研 APP 若要完整复刻需逆向 passport SDK」。第 7 步是唯一产生 `BDUSS` 的一步 —— 没有它，前 6 步毫无意义。 |
| `PASSWORD` | ⏸ 拒绝 | 与扫码共用同一个 `sig` 校验，另需处理短信与图形验证码（§2.3）。 |
| `OAUTH_REDIRECT` | ⏸ 拒绝 | 需要开放平台注册的应用凭据，本项目未申请。**而粘贴 Cookie 不需要任何开发者凭据**，所以拒绝不构成功能缺失。 |
| `DEVICE_CODE` | ❌ 不存在 | 百度未提供设备码授权。 |

> **这是本项目「能力必须诚实」的一次具体应用**：扫码登录的*大部分*步骤是可复刻的，
> 但最后一步不可 —— 因此**整个流程**都不能声明。声明一个走不到最后的流程，
> 正是项目规则里禁止的「一个必然失败的按钮」。

---

## 2. 文件系统

### 2.1 ⭐ 为什么 `FileId` 编码绝对路径

文档依据：§3.5 的 `target=["/path/a.txt"]`、§3.6 的 `filelist=[{"path":…,"dest":…,"newname":…}]`、
§7.3 的 `path=<urlenc>`、§5.4 的 `path_list`。

| 端点 | 接受的寻址方式 |
|---|---|
| `/api/list` | `dir` = **绝对路径** |
| `/api/filemetas` | `target` = **绝对路径**数组 |
| `/api/filemanager` | `filelist[].path` / `.dest` = **绝对路径** |
| `/api/streaming` | `path` = **绝对路径** |
| `/api/recycle/{restore,delete}` | `fidlist` = `fs_id`（**唯一**的例外） |

编码形式（`BaiduFileIdCodec`）：

```
folder:dir:baidu:/我的文件
file:file:baidu:/我的文件/a.txt
```

路径以**固定前缀 + 原样保留**的方式存储，而不是 `key=value` 分段：只有一个字段，
且路径本身可以包含 `=`、`:`、`&`（文件名里这些字符都合法）。分段方案必须自己发明转义规则，
而固定前缀不需要 —— `BaiduFileIdCodecTest` 用 6 个「难缠」路径断言了这一点。

**稳定性规则**：同一文件无论来自 `/api/list`、`/api/categorylist`、`/api/search` 还是
`/api/filemetas`，都产生**逐字节相同**的 `FileId`，因为这四个端点对同一文件报告同一个
`path` 字段。共享契约套件断言了 `getFile` 与 `listChildren` 必须一致。

**刻意不放进 id 的东西**：`md5`、`category`、`size`、以及带签名的 `thumbs`/`docpreview` URL。
前三个在不同端点会缺失，后两个是短时效凭据。放进去就会让**同一文件拥有两个身份** ——
这正是 115 接入踩过的 `pickcode` 坑。

**这个设计的代价，如实记录**：重命名或移动会让被改名项及其**子树**的 id 全部失效。
这是正确的结果（旧 id 确实不再指向任何东西），但它意味着缓存需要按路径前缀失效而不是按项失效。

### 2.2 端点表

| 能力 | 端点 | 方法 | 文档标记 | 状态 | 代码 |
|---|---|---|---|---|---|
| 目录列表 | `pan.baidu.com/api/list` | GET | §3.2 ✅ | ✅ | `BaiduFileSystemProvider.listChildren` |
| 分类列表 | `pan.baidu.com/api/categorylist` | GET | §3.3 ✅ | ✅ | `BaiduFileMapper.toVendorCategory` + `entries` 归一 |
| 搜索 | `pan.baidu.com/api/search` | GET | §3.4 ✅ | ✅ | `search` |
| 文件详情 | `pan.baidu.com/api/filemetas` | POST | §3.5 ✅ | ✅ | `getFile` |
| 取路径 | — | — | — | ✅ | `getPath`：**由 id 直接推导，零请求** |
| 路径 → id | — | — | — | ✅ | `resolvePath`：**归一化，零请求** |

**`getPath` 为什么可以是零请求**：因为 id 就是绝对路径。这不只是优化 ——
它意味着 `getPath` 不会因为文件暂时不可达而失败，而 115 的实现需要走一次 `category/get` 或
最多 32 层的父目录遍历。

**`/api/search` 的两个文档细节**：
- `dir` 必须**删除**而不是设置（厂商自己的客户端就是 `delete n.dir`），因为搜索总是从账号根递归；
- `recursion=1` 才是「搜索子目录」的开关。

因此 `FileSearchQuery.parentId` **不会**被转发：该端点没有任何文档化的子树限定能力。
代码不静默忽略它 —— 会记一条日志说明按全盘递归搜索，因为「返回一个比请求更宽的结果集」
必须让调用方知道。

**分页与终止规则**：§3.2 记录 `page` + `num`，并且**没有**「还有下一页」字段
（文档通过 grep 整个抓包确认了这一点，只有 `/api/search` 有 `has_more`）。
所以结束判定用**实际返回条数**：短于 `num` 的一页就是最后一页。
这条规则不会有歧义也不会死循环 —— 共享契约套件的 `contract_pagination_terminates` 断言了它。

### 2.3 ⭐ 三个信封陷阱（都已在代码中处理）

| # | 陷阱 | 后果（若不处理） | 代码 / 测试 |
|---|---|---|---|
| 1 | **成功只认 `errno === 0`**；缺失或非数字的 `errno` 一律折算为 `-1`（厂商自己的拦截器：`if (isNaN(i)) i = -1`） | 一个畸形响应被当成成功 → 向用户展示错误的数据 | `BaiduJson.parseEnvelope`；`an envelope with no code at all is not treated as success` |
| 2 | **`/rest/2.0/membership/…` 用 `error_code` 而不是 `errno`** | 会员/配额的失败被当成成功 | `parseEnvelope` 同时接受两者 |
| 3 | **`"errno":""` 在真实响应中出现**，而 JS 里 `Number("") === 0` | 空字符串被意外当成成功 | `asIntOrNull` 先把空白折成 `null`；`an empty string errno is not coerced into success` |

另外两个跨端点差异：

- **`/api/categorylist` 返回 `info[]`，`/api/list` 返回 `list[]`**（§3.3，厂商前端自己做了
  `c.info && (c.list = c.info)`）。`BaiduEnvelope.entries` 统一了两种拼写，因此没有任何调用方
  需要记得自己在跟哪个端点说话。
- **`/api/filemetas` 的每个元素自带 `errno`**（§3.5 实测 `{"errno":12,"info":[{"errno":-9}]}`）。
  外层 `12` 表示「部分失败」，元素级 `-9` 才是「这个文件不存在」。
  代码把 `12` 声明为**允许的**外层码，然后检查元素级码 —— 否则整个调用会被错误地整体拒绝。
  `an element level error inside a successful envelope is honoured` 断言了这一点。

---

## 3. 文件操作

| 能力 | `opera` | 文档标记 | 状态 | 理由 |
|---|---|---|---|---|
| `createFolder` | — | §3.7 ✅ | ✅ `SUPPORTED` | `/api/create?a=commit` 有实测响应 `{errno,name,path}` |
| `rename` | `rename` | §3.6 ✅ | ✅ `SUPPORTED` | **唯一**在 bundle 中有完整 URL 字面量的 `opera` |
| `move` | `move` | §3.6 ⚠️ | 🔶 `UNVERIFIED` | 仅由 `actionType:"move"` 透传推断 |
| `copy` | `copy` | §3.6 ⚠️ | 🔶 `UNVERIFIED` | 仅由 `actionType:"copy"` 透传推断 |
| `delete` | `delete` | §3.6 ⚠️ | 🔶 `UNVERIFIED` | 仅由 `doManage("delete",…)` 推断 |
| `delete(permanent=true)` | — | §3.10 ✅ | ⏸ 拒绝 | 彻底删除是**另一个端点**且只接受已在回收站的条目 |
| `upload` | — | §4 ✅ | 🔶 `UNVERIFIED` | 协议文档完整，但会写入真实账号，且分片目标来自 60 秒有效的主机池 |
| `share` | — | §6.1 ✅ | 🔶 `UNVERIFIED` | 实测响应要求账号绑定手机号；**成功响应从未被观测** |
| `setStarred` | — | ❌ 不存在 | ⏸ 拒绝 | Web API 未公开收藏接口 |
| `move`/`copy`/`delete` 的**声明为什么是 `UNVERIFIED` 而不是 `SUPPORTED`** |
| 不是保守，而是**依据强度**的问题。文档明确记录：bundle 里**没有集中的 `opera` 枚举**，只有 `rename` 与 `import` 有完整字面量。`move`/`copy`/`delete` 的取值是从 `doManage(this.actionType, …)` 推出来的。代码是完整实现的、按文档请求形状测过的；缺的是「厂商确实接受这三个字符串」的**证据**。三态声明存在的意义正是让这种区别可以被表达。 |

### 3.1 `filelist` 的两种形态，以及本工程的选择

```json
// 形态 1：fid 数组
[{"fid": 1079551684522032, "ondup": "newcopy"}]

// 形态 2：path 数组
[{"path": "/a/b.txt", "dest": "/target", "newname": "b.txt"}]
```

本工程**始终**使用形态 2，因为 `FileId` 携带的是路径（§2.1），而另一形态需要 `fs_id`。
两种形态对同一端点都合法，所以这个选择不是限制，而是一致性。

**两个容易写错的细节，都已处理**：
1. `opera`、`async`、`onnest`、`bdstoken` 是 **query** 参数，绝不能出现在 `filelist` JSON 里。
   `bdstoken and opera stay in the query rather than the body` 断言了这一点。
2. **根目录作为目标时，厂商把它写成空字符串**（`"/"===e && (e="")`），不是 `"/"`。

### 3.2 写安全

`idempotentWrites = false`，且本工程**不做任何自动重试**。
这不是保守：§3.6 记录 `copy` 的默认 `ondup` 是 `newcopy`，意味着超时后重试会**产生第二份副本**
而不是报错。

单次操作上限 100 个文件（§3.6「单次操作最多 100 个文件」），在本地拒绝并给出明确消息，
而不是让厂商回一个 `errno 3`。

---

## 4. 回收站

| 操作 | 端点 | 方法 | 文档标记 | 状态 |
|---|---|---|---|---|
| 列表 | `/api/recycle/list/` ⚠️ **尾斜杠必需** | GET | §3.10 ✅ | ✅ |
| 还原 | `/api/recycle/restore` | POST | §3.10 ✅ | ✅ |
| 彻底删除 | `/api/recycle/delete` | POST | §3.10 ✅ | ✅ |
| 清空 | `/api/recycle/clear?async=1` | POST | §3.10 ✅ | ⏸ 未接入（24 小时限一次） |

**尾斜杠不是格式问题而是路由问题**：厂商 bundle 里就是 `"".concat("/api/recycle/list", "/")`，
实测请求也带斜杠。`the recycle list uses the trailing slash endpoint` 断言了它。

**`async` 与 `bdstoken` 的提升**：厂商自己的代码是
`if(e.async){t+="&async="+…; delete e.async}` —— 即把它们从 body **移到 query 再删掉**。
本工程直接按最终形态构造，因此 body 里只有 `fidlist`。

**诚实处理未知**：文档记录该测试账号的回收站为**空**，因此**元素级字段从未被观测**，
字段名是从厂商自己的 Vue 模板（`row-key:"id"`、`deleteType`、`leftTimeSlot`）推断的。
映射因此只读文档/模板确认过的拼写，**读不到 id 的条目直接跳过** ——
一个合成 id 既不能还原也不能删除。

---

## 5. 下载：为什么完全不实现

这不是「没做」，而是**一个硬边界**。

§5.2 记录的签名算法（源码字面量证明，非猜测）：

```js
sign = base64_utf16( new Function("return " + sign2)()(sign3, sign1) )
```

关键在于 **`sign2` 是服务端下发的 JavaScript 函数源码字符串**，每次会话都不同。
文档明确：「**无法静态硬编码**，且会随服务端更新变化」，因此自研客户端必须内嵌 JS 引擎
（QuickJS / JavaScriptCore / V8）。

Kotlin 无法求值它，而伪造一个 `sign` 会得到 `errno 113`（「页面已过期」）——
一个把客户端的能力缺失归咎于用户会话的错误。

因此：

| 项 | 值 |
|---|---|
| `operations.download.supported` | `UNSUPPORTED` |
| `operations.download.directLink` | `false` |
| `prepareDownload` | 返回 `Unsupported`，**不发送请求** |
| `folderArchive` | `UNSUPPORTED`（打包结果同样需要 dlink 取回） |

> **附带的发现（值得记录）**：§5.3 与 §_upload_download §2.7 都记录了
> **所有 bundle 中都不存在 `Range`/`Content-Range`/`User-Agent` 的处理代码**，
> 且抓包中**从未真正 fetch 过 dlink**。因此 dlink 的 302 跳转行为、是否需要 UA/Range，
> 至今**未经验证** —— 即使解决了签名问题，这一层也仍是空白。

---

## 6. 媒体播放

### 6.1 ⭐ 两阶段握手（已实测，代码已实现）

```
① GET /api/streaming?path=<enc>&type=M3U8_AUTO_480&vip=0
   → {"errno":133,"adTime":8,"adToken":"…","ltime":5}       ← 广告闸门，不是失败
② GET /api/streaming?…&isplayer=1&check_blue=1&adToken=<enc>
   → HTTP 200  Content-Type: application/x-mpegURL           ← HLS 主清单
```

**`errno 133` 的处理方式是这个 Provider 最能说明「诚实能力」的地方**：
它是一个 `CloudError.Unsupported`，但 `capability` 字段指向**窄**能力
`media.streaming.adToken`，而不是宽能力 `media.videoStreaming`。

这个区分是必需的，不是修辞：共享契约套件规定「一个宽能力可以有窄的洞，但洞必须可识别」。
如果返回一个不带 `capability` 的 `Unsupported`，套件会（正确地）判定
「你声明了 `videoStreaming` 却做不到」——而这会掩盖真实情况：流媒体是能用的，
只是这个文件在广告闸门后面。

代码**自动完成**这个握手：第一次请求若拿到闸门，就用它给的 `adToken` 发第二阶段。
`the streaming ad handshake is completed and yields an hls resource` 断言了这一点。
递归有深度上限（2），因为文档只描述了两个阶段 —— 第三次出现闸门意味着服务端
因为本客户端无法满足的原因再次拦截，此时循环会把一个清晰的失败变成挂起。

### 6.2 三个与 115 相反的结论

| 维度 | 百度 | 115 | 影响 |
|---|---|---|---|
| **分片是否需要鉴权头** | **不需要** —— §7.4 实测 `credentials:"omit"` 仍返回 `HTTP 200`，`Range` 返回 `206` | 需要 `Referer`/`Origin`/`User-Agent` | `MediaResource.headers` 对百度**刻意留空**：把账号 Cookie 交给播放器转发到 CDN 是没有收益的泄露 |
| **音频播放** | 走 **dlink**，不走 `/api/streaming`（§7.6） | 有分离的 `audio/{N}.m3u8` | `audioStreaming` = `UNSUPPORTED`；`separateAudioTracks` = `UNSUPPORTED` |
| **清晰度上限** | **1080P**（§7.3.1：「没有『原画』『蓝光』，最高只有 1080P 超清」） | 4K | 清晰度阶梯里**刻意不含 2160** —— 它存在于参数表但 UI 从未暴露，请求它会得到 `errno 31023` |

`the playlist carries the documented renditions` 断言了 2160 不被提供。

### 6.3 播放进度：不实现

§7 记录百度的进度**完全保存在本地 `localStorage`**（`video_<md5>`），
唯一的跨设备痕迹是二维码 URL 上的一个 `video_progress` 参数。
文档中**没有任何进度上报端点**。

因此 `playbackProgress` = `UNVERIFIED`，`reportPlaybackProgress` 返回 `Unsupported`，
`getPlaybackProgress` 返回**成功的 `null`**（「没有已保存的位置」是一个正常答案，
不是失败 —— 混为一谈会让每次播放都弹错误条）。

---

## 7. 文件预览

| 能力 | 状态 | 依据 / 理由 |
|---|---|---|
| `image` | ✅ `SUPPORTED` | §8.2：`thumbs.url{1,2,3}` 随**列表响应**下发，零额外请求 |
| `thumbnail` | ✅ `SUPPORTED` | 同上；`size`/`quality` **不在签名内**，可自由改写 |
| `text` | ✅ `SUPPORTED` | §8.5 实测 `picdocpreview`（`filemetas?text=1`） |
| `pdf` | 🔶 `UNVERIFIED` | §8.1 的注册表把它路由到 `pdf-plugin`（平台阅读器）。分类正确，但取字节需要 dlink |
| `officeDocument` | 🔶 `UNVERIFIED` | §8.4 实测 `/disk/docview` 会 302 到**独立的 OAuth 应用**（`client_id=9dgBV9yesuBVOXaxls7aVHbLBLqU8yyg`）。驱动它意味着一个本工程无法完成的 WebView 授权流程 |
| 压缩包 | ⏸ `Unsupported` | §8.6 的 `/api/zipfile/list` 有一个语义只被部分记录的密码参数 |

**Office 文档的分类结果刻意是「无 viewer 材料的 `OfficeDocument`」**：
`PreviewKind.Unsupported` 是错的（文件**是**可预览的），
而伪造一个 `OfficeViewer(url, token)` 则会宣称一个走不通的入口。
`office documents classify without a fabricated viewer` 断言了 viewer 的 url 为空。

**一个必须记住的安全映射**：`thumbs` 与 `docpreview` 的 URL 带 `sign`/`expires`/`time`，
是短时效凭据；而 `FileItem` **会被缓存**。因此 `BaiduFileMapper` 只把
**「URL 存在」记成布尔值**，绝不把 URL 本身写进 `extensions`。
`signed urls never leak into the file model` 断言了模型里不含 `sign=` 与 `http`。

---

## 8. 错误码映射

`BaiduErrorMapping.kt` 的核心结论来自文档的一句话：**`errno` 的语义是「接口族局部」的，不是全局唯一的。**

同一个 `111`：
| 族 | 文案 |
|---|---|
| 回收站 | 您还有任务未完成，请稍后重试 |
| filemanager | 当前还有未完成的任务，需完成后才能操作 |
| 团队空间 | 创建团队空间失败，请稍后重试 |

因此 `mapBaiduError` **接收 `BaiduErrorFamily` 参数**，不存在一张全局的「码 → 文案」表。

几个需要特定反应的码（都已按文档实现）：

| `errno` | 映射 | 理由 |
|---|---|---|
| `-6` | `TokenExpired(canRefresh = false)` | 厂商自己的拦截器在此跳 `/login`；且百度**没有**文档化的静默刷新，声明可刷新会让 UI 无限重试 |
| `-1` | **按族分流** | 分享族是「违规内容被封禁」，filemanager 族是「用户名和密码验证失败」 |
| `132` | `AdditionalVerificationRequired` | 响应带 `authwidget{safetpl,saferand,safesign}`，只能由交互式验证消费；既不是权限不足也不是可重试 |
| `-20` | `AdditionalVerificationRequired` | 需要验证码（`/api/getcaptcha`） |
| `133` | `Unsupported(capability="media.streaming.adToken")` | 文档化的握手第一步，见 §6.1 |
| `31341` | `ServerError`（**可重试**） | 厂商自己的播放器重试 30 次、间隔 1 秒 —— 明确是暂时性的 |
| `31034` | `RateLimited` | 「操作过于频繁」是限流信号，与 `31341` 语义相反 |
| `-10`/`-32`/`31116`/`36009` | `QuotaExceeded` | 文档列出的四个「空间不足」拼写 |

---

## 9. 需要项目方提供 / 待真实联调的事项

### 9.1 需要提供

| 项 | 用途 | 缺失时的影响 |
|---|---|---|
| **无** | — | — |

**这一栏为空是一个事实陈述，而不是遗漏。** 本工程唯一提供的流程是粘贴浏览器会话 Cookie，
它不需要任何开发者凭据：每个请求用的 `app_id=250528` 是网页版**自己的公开** `app_id`（§1.2），
不是按开发者下发的，也不是秘密。

### 9.2 待真实联调清单

以下各项**代码已完成、单元/HTTP 测试已通过**，但未在真实账号上跑过：

| # | 项 | 风险 | 验证方式 |
|---|---|---|---|
| 1 | 粘贴 Cookie 登录 | 低 — 解析与「先校验后写入」已有测试 | 从浏览器复制真实 `Cookie` 粘贴一次；再用失效 Cookie 确认**不会**留下账号行 |
| 2 | 目录浏览与分页（>100 项目录） | 中 — 文档未给出「还有下一页」字段，本工程用「短页即末页」推断 | 找一个 >100 项的目录滚动到底 |
| 3 | 分类列表（`info[]` 归一） | 低 | 打开「图片」「文档」分类 |
| 4 | 搜索 | 低 | 搜索一个关键词 |
| 5 | `rename` | 低 — 有完整 URL 字面量 | 在**测试目录**内重命名 |
| 6 | `move` / `copy` / `delete` | **中高 — `opera` 值系推断** | 在测试目录内操作；若返回 `errno 2`，说明推断的 `opera` 字符串不对，需按文档 §3.6 更新 |
| 7 | 回收站四项 | 中 — 列表**元素级字段从未被观测** | 先删一个文件再查回收站，确认条目能被正确列出 |
| 8 | 视频播放（两阶段握手） | 中 — 合同已实测，但广告闸门的后续行为（看完广告后 `adToken` 是否仍有效）未观测 | 播放一个非会员账号的视频 |
| 9 | 文本预览（`picdocpreview`） | 低 | 打开一个 `.txt` / `.md` |
| 10 | 图片缩略图 | 中 — 本工程不下发签名 URL（见 §7），UI 会走类型图标回退 | 打开图片目录确认不出现破图 |
| 11 | 风控触发时的行为（`errno 132` / `-20`） | **高 — 可能触发风控** | **不建议主动测试** |

**联调建议**：在百度网盘根目录下新建一个专用测试目录，所有写操作都在其中进行。

### 9.3 文档自身的空白（本工程无法补齐的部分）

| 项 | 文档记录的状态 | 对本工程的影响 |
|---|---|---|
| `sign1`/`sign2`/`sign3` 字面值 | ⚠️ 从未捕获（疑似限流） | 算法已证明；但因需 JS 引擎，下载整体不实现 |
| dlink 的 302 跳转行为 | ⚠️ 未观测（抓包中从未 fetch） | 同上 |
| 分享创建的成功响应 | ⚠️ 被手机号绑定拦截 | `share` = `UNVERIFIED` |
| 回收站有内容时的元素字段 | ⚠️ 测试账号回收站为空 | 映射只读模板确认过的拼写，读不到 id 的条目跳过 |
| `/share/streaming` | ⚠️ 仅静态分析 | 分享播放未接入 |
| `opera` 的完整枚举 | ⚠️ 无集中枚举 | 见 §3 的 `UNVERIFIED` 声明 |

---

## 附：本表所依据的代码与测试

| 关注点 | 代码 | 测试 |
|---|---|---|
| 端点常量与依据标记 | `api/BaiduEndpoints.kt` + `BaiduVerification` | — |
| HTTP 与信封归一 | `api/BaiduHttpClient.kt`, `api/BaiduJson.kt` | `BaiduProviderHttpTest`（缺码/空码/`error_code`/非 JSON 体） |
| 错误码映射（按族） | `api/BaiduErrorMapping.kt` | `BaiduProviderHttpTest`（`-6` / `132` / 元素级 `-9` / `errno 2`） |
| 文件标识 | `BaiduFileIdCodec.kt` | `BaiduFileIdCodecTest`（8 项，含难缠路径与跨 Provider 拒绝） |
| DTO → 领域模型 | `BaiduFileMapper.kt` | `BaiduProviderHttpTest`（逐字段断言 + 签名 URL 不泄露） |
| 认证 | `auth/BaiduAuthProvider.kt`, `auth/BaiduCookieParser.kt` | `BaiduProviderHttpTest`（写入前校验 / 未登录拒绝 / 重复绑定复用）、`BaiduCookieParserTest`（12 项） |
| 回收站 | `BaiduFileOperationProvider.kt` | `BaiduProviderHttpTest`（尾斜杠 / `faillist`） |
| 媒体两阶段握手 | `BaiduMediaProvider.kt` | `BaiduProviderHttpTest`（握手完成 / 清晰度上限 / 无鉴权头） |
| 预览 | `BaiduPreviewProvider.kt` | `BaiduProviderHttpTest`（文本通道 / 无伪造 viewer / 缩略图不泄露） |
| 能力声明 | `BaiduCloudProvider.kt` | `capabilities declare the documented and the unverified accurately` + `the capability declaration matches the documented boundaries` |
| 共享契约 | — | `BaiduProviderContractTest`（29 条规则） |
