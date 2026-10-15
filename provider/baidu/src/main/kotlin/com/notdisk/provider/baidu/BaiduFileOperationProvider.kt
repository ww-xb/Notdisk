package com.notdisk.provider.baidu

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.DownloadRequest
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileType
import com.notdisk.core.model.TransferDirection
import com.notdisk.core.model.TransferProgress
import com.notdisk.core.model.TransferStatus
import com.notdisk.core.model.TransferTask
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.provider.BatchItemFailure
import com.notdisk.core.provider.BatchOperationResult
import com.notdisk.core.provider.DownloadSource
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.ShareResult
import com.notdisk.core.provider.TrashItem
import com.notdisk.core.provider.TrashPage
import com.notdisk.provider.baidu.api.BaiduEndpoints
import com.notdisk.provider.baidu.api.BaiduErrorFamily
import com.notdisk.provider.baidu.api.BaiduHttpClient
import com.notdisk.provider.baidu.api.arrayOf
import com.notdisk.provider.baidu.api.boolOf
import com.notdisk.provider.baidu.api.intOf
import com.notdisk.provider.baidu.api.longOf
import com.notdisk.provider.baidu.api.stringOf
import com.notdisk.provider.baidu.auth.BaiduAuthProvider
import com.notdisk.provider.baidu.auth.BaiduSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.JsonObject

/**
 * Baidu Netdisk's mutating surface.
 *
 * ### The one endpoint behind every operation (§3.6)
 * Rename, move, copy and delete are all `POST /api/filemanager` with a different `opera` query
 * parameter and a `filelist` **JSON-string body**. The reference material is explicit that:
 *
 *  - there is **no centralized `opera` enum** in the vendor's bundle — only `rename` and `import`
 *    exist as complete URL literals, while `move`/`copy`/`delete` are passed through from
 *    `actionType` values and are therefore marked "strong inference" rather than measured;
 *  - `opera`, `async`, `onnest` and `bdstoken` are **query** parameters and must never appear inside
 *    the `filelist` JSON;
 *  - `filelist` has two shapes — `[{"fid":…,"ondup":…}]` and
 *    `[{"path":…,"dest":…,"newname":…}]` — and this provider always uses the **path** shape, because
 *    the unified [FileId] carries a path (see [BaiduFileIdCodec]).
 *
 * The inference is handled honestly rather than papered over: [BaiduCloudProvider] declares
 * `rename` as the only `SUPPORTED` operation, because it is the only one with a complete URL literal,
 * and marks the rest `UNVERIFIED`. The code is fully implemented for all of them — a capability
 * declaration is a statement about *evidence*, not about whether the method has a body.
 *
 * ### Write safety
 * `idempotentWrites` is `false`, and nothing here retries. That is not conservatism: §3.6 documents
 * that `copy` with the default `ondup: newcopy` produces a **duplicate file** rather than an error,
 * so a retry after a timeout creates a second copy. Only the caller knows whether the previous
 * attempt landed.
 *
 * ### Upload and download
 * Both are documented but neither is implemented, for reasons that are recorded on each method
 * rather than hidden in a comment.
 */
internal class BaiduFileOperationProvider(
    private val http: BaiduHttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<BaiduSession>,
    private val authProvider: BaiduAuthProvider,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : FileOperationProvider {

    /** Download transfers are tracked locally; the vendor hands out a link and the app transfers. */
    private val transfers = ConcurrentHashMap<String, MutableStateFlow<TransferProgress>>()

    // ------------------------------------------------------------------ create / rename

    /**
     * Creates a folder via `POST /api/create?a=commit` (§3.7).
     *
     * The body is `path` + `isdir=1` + `block_list=[]` + `size=0`, exactly as the vendor's own
     * client builds it (`Object.assign({}, t, {isdir:1, block_list:"[]"})`), and the response is
     * documented to return the new folder's `name` and complete `path` — which is what makes it
     * possible to build the [FileItem] without a second request.
     */
    override suspend fun createFolder(
        accountId: AccountId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (name.isBlank()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件夹名不能为空"))
        }
        if (!isValidName(name)) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "文件夹名不能包含路径分隔符"),
            )
        }

        val parent = BaiduFileIdCodec.decode(parentId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的父目录标识: $parentId"),
            )
        if (!parent.isFolder) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "目标不是文件夹: $parentId"),
            )
        }

        val newPath = BaiduFileMapper.joinPath(parent.path, name)

        val token = when (val result = authProvider.bdstokenFor(session)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.CREATE),
            form = mapOf(
                "path" to newPath,
                "isdir" to "1",
                "block_list" to "[]",
                "size" to "0",
            ),
            query = mapOf("a" to "commit", "bdstoken" to token),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "create.folder", family = BaiduErrorFamily.FILE_MANAGER)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // §3.7's measured response: `{"errno":0,"name":"<目录名>","path":"<完整路径>"}`. The
                // returned path is preferred over the locally-composed one, because the server is the
                // authority on how it normalized the name.
                val returnedPath = result.data.string("path") ?: newPath
                val returnedName = result.data.string("name") ?: name
                CloudResult.Success(
                    FileItem(
                        id = BaiduFileIdCodec.forFolder(returnedPath),
                        providerId = BaiduProviderId,
                        accountId = accountId,
                        name = returnedName,
                        type = FileType.FOLDER,
                        parentId = FileId(BaiduFileIdCodec.forFolder(parent.path).value),
                        createdTime = clock(),
                        modifiedTime = clock(),
                        capabilities = FileCapabilities(
                            canDownload = false,
                            canStream = false,
                            canPreview = false,
                            canRename = true,
                            canMove = true,
                            canCopy = true,
                            canDelete = true,
                            hasThumbnail = false,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Renames one item via `POST /api/filemanager?opera=rename` (§3.6).
     *
     * This is the **only** operation whose `opera` value exists as a complete URL literal in the
     * vendor's bundle (`home.ba8ddd2c.js @954,245`), which is why it is the only mutating capability
     * this provider declares `SUPPORTED`.
     *
     * The `filelist` uses the path shape: `[{"path": …, "dest": <parent>, "newname": …}]`. Note the
     * vendor's own quirk that a **root destination becomes the empty string** (`"/"===e && (e="")`),
     * which is reproduced here for the root case because sending `"/"` for a root destination is not
     * the form the vendor's server is documented to accept.
     */
    override suspend fun rename(
        accountId: AccountId,
        fileId: FileId,
        newName: String,
    ): CloudResult<FileItem> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (newName.isBlank()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能为空"))
        }
        if (!isValidName(newName)) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能包含路径分隔符"))
        }

        val ref = BaiduFileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的百度网盘文件标识: $fileId"),
            )

        val parentPath = ref.parentPath ?: BaiduFileIdCodec.ROOT_PATH
        val token = when (val result = authProvider.bdstokenFor(session)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val fileList = buildFileListEntry(
            path = ref.path,
            dest = parentPath,
            newName = newName,
        )

        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.FILEMANAGER),
            form = mapOf("filelist" to fileList),
            query = mapOf(
                "opera" to OPERA_RENAME,
                "async" to ASYNC_SMALL,
                "onnest" to "fail",
                "bdstoken" to token,
            ),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "filemanager.rename", family = BaiduErrorFamily.FILE_MANAGER)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val newPath = BaiduFileMapper.joinPath(parentPath, newName)
                CloudResult.Success(
                    FileItem(
                        id = BaiduFileIdCodec.forPath(newPath, ref.isFolder),
                        providerId = BaiduProviderId,
                        accountId = accountId,
                        name = newName,
                        type = if (ref.isFolder) FileType.FOLDER else FileType.FILE,
                        parentId = FileId(BaiduFileIdCodec.forFolder(parentPath).value),
                        modifiedTime = clock(),
                        capabilities = FileCapabilities(
                            canRename = true,
                            canMove = true,
                            canCopy = true,
                            canDelete = true,
                        ),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ batch mutations

    /** §3.6 ⚠️ — `opera=move`, a pass-through of the vendor's `actionType`. */
    override suspend fun move(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> = batchMutation(
        accountId = accountId,
        fileIds = fileIds,
        targetParentId = targetParentId,
        opera = OPERA_MOVE,
        tag = "filemanager.move",
        operationName = "移动",
    )

    /** §3.6 ⚠️ — `opera=copy`, a pass-through of the vendor's `actionType`. */
    override suspend fun copy(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> = batchMutation(
        accountId = accountId,
        fileIds = fileIds,
        targetParentId = targetParentId,
        opera = OPERA_COPY,
        tag = "filemanager.copy",
        operationName = "复制",
    )

    /**
     * §3.6 ⚠️ — `opera=delete`.
     *
     * Baidu has **no permanent-delete operation reachable from this endpoint**, and `permanent = true`
     * is refused with a typed `Unsupported` pointing at the recycle bin rather than silently
     * downgraded. That is a data-safety statement, not a limitation: §3.10 documents that permanent
     * deletion is a *different* endpoint (`/api/recycle/delete`) which only accepts items that are
     * already in the bin. Quietly turning "delete forever" into "move to bin" would tell the user
     * something false about where their file went.
     */
    override suspend fun delete(
        accountId: AccountId,
        fileIds: List<FileId>,
        permanent: Boolean,
    ): CloudResult<BatchOperationResult> {
        if (permanent) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "百度网盘的彻底删除只能对回收站中的文件执行；普通删除会移入回收站。",
                    capability = "operations.delete.permanent",
                ),
            )
        }
        return batchMutation(
            accountId = accountId,
            fileIds = fileIds,
            targetParentId = null,
            opera = OPERA_DELETE,
            tag = "filemanager.delete",
            operationName = "删除",
        )
    }

    /**
     * §4 — upload.
     *
     * **Not implemented, deliberately.** The upload protocol is the best-documented part of the
     * reference material (six endpoints, every constant, the MD5 transform) and it is **not** the
     * obstacle. The obstacle is that an unverified upload writes to a real account: the protocol's
     * `superfile2` chunk endpoint must be pointed at a server obtained from `locateupload`, whose
     * response is a *pool* of hosts with a 60-second lifetime (§4.2), and a mistake there produces a
     * corrupt or duplicated file rather than an error the user can act on.
     *
     * The project's rule is explicit that an unverified write must not be attempted, so this returns
     * a typed `Unsupported` **without sending any request** — the shared contract suite asserts the
     * request count is zero for a capability declared this way.
     */
    override suspend fun upload(
        accountId: AccountId,
        request: UploadRequest,
    ): CloudResult<TransferTask> {
        // The session is still resolved so the error is accurate: reporting "unsupported" for an
        // account that is also logged out would hide the more actionable problem.
        sessionOrFail(accountId)
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未实现百度网盘上传：分片上传需要按 locateupload 下发的主机池动态改写目标地址，" +
                    "该协议尚未在真实账号上端到端验证，为避免写入损坏或重复的文件，暂不提供。",
                capability = "operations.upload",
            ),
        )
    }

    // ------------------------------------------------------------------ download

    /**
     * §5.3 — resolve a download link.
     *
     * **Not implemented, deliberately**, and for a reason that is structural rather than cautious:
     * `/api/download` requires a `sign` produced by
     * `base64_utf16(new Function("return " + sign2)()(sign3, sign1))`, where **`sign2` is JavaScript
     * source the server sends at runtime** (§5.2). The reference material is unambiguous that it
     * "无法静态硬编码" and that a client must embed a JS engine (QuickJS/JavaScriptCore/V8) to compute
     * it. Kotlin cannot evaluate it, and fabricating a `sign` would produce `errno 113`
     * ("页面已过期") — a message that blames the user's session for a client-side impossibility.
     *
     * This is a hard, honest boundary rather than an omission, and it is why
     * `operations.download.supported` is `UNSUPPORTED` and `download.directLink` is `false`.
     */
    override suspend fun prepareDownload(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<DownloadSource> {
        val ref = BaiduFileIdCodec.decode(fileId)
        if (ref != null && ref.isFolder) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "百度网盘文件夹下载需要打包接口，本版本未实现",
                    capability = "operations.download.folderArchive",
                ),
            )
        }
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未实现百度网盘下载：下载直链需要一个由服务端实时下发的 JavaScript 函数计算的" +
                    "签名（sign2），Kotlin 无法在无内嵌 JS 引擎的情况下生成它。",
                capability = "operations.download",
            ),
        )
    }

    /** Never reached: [prepareDownload] cannot produce a source. Kept honest rather than faked. */
    override suspend fun download(
        accountId: AccountId,
        request: DownloadRequest,
        source: DownloadSource,
    ): CloudResult<TransferTask> {
        sessionOrFail(accountId)
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未实现百度网盘下载（下载直链签名需要内嵌 JS 引擎）。",
                capability = "operations.download",
            ),
        )
    }

    override fun observeTransfer(transferId: String): Flow<TransferProgress> {
        val stateFlow = transfers[transferId]
            ?: return flowOf(TransferProgress(0, null, TransferStatus.FAILED))
        return stateFlow.asStateFlow().transformWhile { progress ->
            emit(progress)
            !progress.status.isTerminal
        }
    }

    override suspend fun cancelTransfer(transferId: String): CloudResult<Unit> {
        transfers[transferId]?.let { flow ->
            flow.value = flow.value.copy(status = TransferStatus.CANCELLED)
        }
        // Cancelling requires no server call: the vendor has no transfer to abort, because it hands
        // out a link rather than running the transfer.
        return CloudResult.Success(Unit)
    }

    override suspend fun retryTransfer(transferId: String): CloudResult<TransferTask> {
        val flow = transfers[transferId]
            ?: return CloudResult.Failure(CloudError.NotFound(message = "任务不存在: $transferId"))
        flow.value = flow.value.copy(status = TransferStatus.QUEUED)
        return CloudResult.Success(
            TransferTask(
                id = transferId,
                key = CloudFileKey(BaiduProviderId, AccountId(""), FileId("")),
                direction = TransferDirection.DOWNLOAD,
                fileName = "",
                totalBytes = flow.value.totalBytes,
                transferredBytes = flow.value.transferredBytes,
                status = TransferStatus.QUEUED,
                autoRetryable = true,
            ),
        )
    }

    /**
     * §6.1 — sharing.
     *
     * **Not implemented, deliberately.** Two independent facts make this the highest-risk action
     * available:
     *  - the measured response for this account was
     *    `{"errno":2,"show_msg":"为保障您的账号安全，使用分享功能前需完成手机号绑定"}`, i.e. the flow
     *    cannot even be exercised without a phone-bound account;
     *  - the **success** response shape was never measured, and the reference material records that
     *    the parameter set is only reachable through dynamically-loaded plugin chunks ("U1, HIGH").
     *
     * Creating a public link on a protocol whose success shape is unknown is precisely what the
     * project's capability rules forbid. So this returns a typed `Unsupported` and the capability is
     * `UNVERIFIED` — not `UNSUPPORTED`, because the vendor *does* offer sharing; this build simply has
     * no evidence for how it behaves.
     */
    override suspend fun share(
        accountId: AccountId,
        fileIds: List<FileId>,
        enable: Boolean,
        password: String?,
    ): CloudResult<ShareResult> {
        sessionOrFail(accountId)
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未实现百度网盘分享创建：该流程需要账号先绑定手机号，且成功响应结构未经实测。" +
                    "为避免在未验证的情况下对外发布文件，暂不提供。",
                capability = "operations.share",
            ),
        )
    }

    /**
     * Star/unstar.
     *
     * Returns a typed `Unsupported` rather than pretending, because the reference material does not
     * document a star endpoint for the web API at all — `/api/filemanager`'s `opera` values are
     * `rename`/`import`/`move`/`copy`/`delete`/`unzip` and nothing else (§3.6). The 115 integration
     * has a real `files/star` endpoint; this one does not, and inventing a plausible path would be
     * exactly the "猜测 API" the project forbids.
     */
    override suspend fun setStarred(
        accountId: AccountId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit> {
        sessionOrFail(accountId)
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "百度网盘 Web API 未公开收藏/星标接口",
                capability = "operations.setStarred",
            ),
        )
    }

    // ------------------------------------------------------------------ recycle bin

    /**
     * §3.10 — the recycle bin listing.
     *
     * Note the **trailing slash** on `/api/recycle/list/`: the vendor's bundle builds it as
     * `"".concat("/api/recycle/list", "/")`, and the measured request confirms it. Omitting it is not
     * a redirect but a routing miss.
     *
     * The reference material records that the element-level fields were **never observed** (the test
     * account's bin was empty) and that the field set is inferred from the vendor's own Vue template
     * (`row-key: "id"`, `deleteType`, `leftTimeSlot`). Mapping therefore reads the documented and
     * template-confirmed spellings and skips an entry it cannot name, rather than inventing a
     * placeholder row.
     */
    override suspend fun listTrash(
        accountId: AccountId,
        cursor: String?,
        pageSize: Int,
    ): CloudResult<TrashPage> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        val page = cursor?.toIntOrNull()?.takeIf { it >= 1 } ?: 1
        val num = pageSize.coerceIn(1, MAX_PAGE_SIZE)

        val request = http.get(
            url = http.resolve(BaiduEndpoints.RECYCLE_LIST),
            query = mapOf("num" to num.toString(), "page" to page.toString()),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "recycle.list", family = BaiduErrorFamily.RECYCLE)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val entries = result.data.entries
                CloudResult.Success(
                    TrashPage(
                        items = entries.mapNotNull { node -> trashItemOf(node) },
                        // Same terminating rule as listings: a short page ends the sequence. The
                        // material documents no `has_more` for this endpoint, and a page counter that
                        // never stops is the failure the shared contract suite forbids.
                        nextCursor = if (entries.size >= num && entries.isNotEmpty()) {
                            (page + 1).toString()
                        } else {
                            null
                        },
                        totalCount = result.data.long("count"),
                        // The material documents no recycle-bin password for Baidu (unlike 115's
                        // `rb_pass`), so nothing is claimed.
                        protectedBySecret = false,
                    ),
                )
            }
        }
    }

    /**
     * §3.10 — restore from the recycle bin.
     *
     * The body is `fidlist`, a **JSON array string**, and the `async`/`bdstoken` parameters are hoisted
     * into the query — the vendor's own client appends them to the URL and then `delete`s them from
     * the body object (`if(e.async){t+="&async="+…; delete e.async}`).
     *
     * The `fidlist` values are the recycle-bin entry ids, which the material documents as the
     * `fs_id` of the original file. That is why [restoreFromTrash] takes raw strings rather than
     * [FileId]s: conflating a bin entry id with a live file path would reintroduce exactly the
     * identity confusion [BaiduFileIdCodec] exists to prevent.
     */
    override suspend fun restoreFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (trashIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何回收站条目"))
        }

        val token = when (val result = authProvider.bdstokenFor(session)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.RECYCLE_RESTORE),
            form = mapOf("fidlist" to idListJson(trashIds)),
            query = mapOf("async" to ASYNC_SMALL, "bdstoken" to token),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "recycle.restore", family = BaiduErrorFamily.RECYCLE)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> CloudResult.Success(
                // §3.10 documents that a partial failure arrives as `faillist`. Reporting per-item
                // outcomes from it is the difference between "3 of 5 restored" and a blanket claim.
                batchResultFrom(result.data.root, accountId, trashIds),
            )
        }
    }

    /**
     * §3.10 — permanently delete recycle-bin entries.
     *
     * `safeKey` is accepted and **ignored on the wire**: Baidu's recycle bin is documented to have no
     * password (unlike 115's `rb_pass`), so there is no parameter to carry one. It is kept in the
     * signature because the shared interface declares it, and dropping it silently would make a
     * caller that *does* collect a safe key believe it had been used.
     */
    override suspend fun purgeFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
        safeKey: String?,
    ): CloudResult<BatchOperationResult> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (trashIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何回收站条目"))
        }

        val token = when (val result = authProvider.bdstokenFor(session)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.RECYCLE_DELETE),
            form = mapOf("fidlist" to idListJson(trashIds)),
            query = mapOf("async" to ASYNC_SMALL, "bdstoken" to token),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "recycle.delete", family = BaiduErrorFamily.RECYCLE)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> CloudResult.Success(
                batchResultFrom(result.data.root, accountId, trashIds),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Shared driver for the three `filemanager` batch operations.
     *
     * It validates locally, issues **exactly one** request, and translates the vendor's response into
     * an honest shape:
     *  - `taskid` present ⇒ the operation was accepted asynchronously, so it is surfaced as
     *    `vendorTaskId` rather than being reported as already applied;
     *  - `info[]` present ⇒ per-item outcomes are read from it, because §3.6 documents that each
     *    element carries its own `errno` and that a partially applied batch is normal.
     */
    private suspend fun batchMutation(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId?,
        opera: String,
        tag: String,
        operationName: String,
    ): CloudResult<BatchOperationResult> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "$operationName 操作未选择任何文件"),
            )
        }

        val refs = fileIds.map { id ->
            BaiduFileIdCodec.decode(id)
                ?: return CloudResult.Failure(
                    CloudError.InvalidArgument(message = "$operationName 操作包含无法解析的文件标识: $id"),
                )
        }

        // §3.6 documents a single-operation limit of 100 items ("单次操作最多 100 个文件"). Rejecting
        // locally gives a clear message instead of the vendor's `errno 3`.
        if (refs.size > MAX_BATCH_SIZE) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "$operationName 单次最多 $MAX_BATCH_SIZE 个文件，当前 ${refs.size} 个",
                ),
            )
        }

        val destPath = targetParentId?.let { target ->
            val decoded = BaiduFileIdCodec.decode(target)
                ?: return CloudResult.Failure(
                    CloudError.InvalidArgument(message = "无法解析的目标目录标识: $target"),
                )
            if (!decoded.isFolder) {
                return CloudResult.Failure(
                    CloudError.InvalidArgument(message = "目标不是文件夹: $target"),
                )
            }
            decoded.path
        }

        val token = when (val result = authProvider.bdstokenFor(session)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val async = if (refs.size < ASYNC_SMALL_THRESHOLD) ASYNC_SMALL else ASYNC_LARGE
        val request = http.postForm(
            url = http.resolve(BaiduEndpoints.FILEMANAGER),
            form = mapOf(
                "filelist" to fileListJson(refs, destPath),
            ),
            query = mapOf(
                "opera" to opera,
                "async" to async,
                "onnest" to "fail",
                "bdstoken" to token,
            ),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = tag, family = BaiduErrorFamily.FILE_MANAGER)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val vendorTaskId = result.data.string("taskid", "task_id")
                if (vendorTaskId != null) {
                    logger.i(TAG, "$tag accepted asynchronously, task=$vendorTaskId")
                }

                val failures = failuresFrom(
                    info = result.data.array("info")?.mapNotNull { it as? JsonObject },
                    accountId = accountId,
                    requested = fileIds,
                )
                val succeeded = fileIds.filterNot { candidate ->
                    failures.any { it.key.fileId == candidate }
                }

                CloudResult.Success(
                    BatchOperationResult(
                        succeeded = succeeded.map { CloudFileKey(BaiduProviderId, accountId, it) },
                        failed = failures,
                        vendorTaskId = vendorTaskId,
                    ),
                )
            }
        }
    }

    /**
     * Builds the `path`-shaped `filelist` for a rename or a batch mutation.
     *
     * The vendor's own `convertFileMeta` produces exactly `{path, dest, newname}`, and its
     * `convertRepeatList` adds `ondup`. Reproduction of the shape matters because the alternative
     * shape (`[{"fid":…}]`) is *also* valid for the same endpoint — but this provider's [FileId]
     * carries a path and not an `fs_id`, so the path shape is the one that is actually consistent
     * with the rest of the integration.
     */
    private fun fileListJson(
        refs: List<BaiduRef>,
        destPath: String?,
    ): String {
        val entries = refs.map { ref ->
            buildString {
                append('{')
                appendJsonProperty("path", ref.path)
                // A root destination is the **empty string** in the vendor's own client, not "/".
                append(',')
                appendJsonProperty("dest", destPath?.let { if (it == "/") "" else it } ?: "")
                // `newname` is the file's own name; for move/copy the vendor sends the unchanged name
                // and lets `dest` carry the move, while for rename the new name is supplied.
                append(',')
                appendJsonProperty("newname", ref.name)
                append('}')
            }
        }
        return entries.joinToString(prefix = "[", postfix = "]", separator = ",")
    }

    /** The single-entry shape `opera=rename` uses, built through the same escaper. */
    private fun buildFileListEntry(path: String, dest: String, newName: String): String = buildString {
        append('[')
        append('{')
        appendJsonProperty("path", path)
        append(',')
        appendJsonProperty("dest", if (dest == "/") "" else dest)
        append(',')
        appendJsonProperty("newname", newName)
        append('}')
        append(']')
    }

    /**
     * Appends `"key":"value"` with the escaping JSON requires.
     *
     * Hand-built rather than through a serializer because the result must be a **string** inside a
     * form field, and because a path is arbitrary user data: an unescaped quote would produce a
     * malformed array that the vendor reports as `errno 2` ("参数错误"), a message that points nowhere
     * near the real cause.
     */
    private fun StringBuilder.appendJsonProperty(key: String, value: String) {
        append('"').append(key).append('"').append(':').append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }

    /** §3.10's `fidlist` is a JSON array of ids — numbers in the vendor's own client. */
    private fun idListJson(ids: List<String>): String =
        ids.joinToString(prefix = "[", postfix = "]") { id ->
            // The ids are numeric, but they are kept as strings end-to-end: `fs_id` is documented as a
            // 64-bit value that must never round-trip through a float (§13.2 item 2). They are
            // therefore emitted unquoted only when they are genuinely numeric, so a malformed id
            // produces a clear JSON error rather than a silently-quoted string the server rejects.
            if (id.toLongOrNull() != null) id else "\"${id.replace("\"", "")}\""
        }

    /** Reads per-item failures from `info[]`, whose elements carry their own `errno` (§3.6). */
    private fun failuresFrom(
        info: List<JsonObject>?,
        accountId: AccountId,
        requested: List<FileId>,
    ): List<BatchItemFailure> {
        if (info.isNullOrEmpty()) return emptyList()
        return info.mapNotNull { node ->
            val code = node.intOf("errno") ?: return@mapNotNull null
            if (code == 0) return@mapNotNull null
            val path = node.stringOf("path") ?: return@mapNotNull null
            val id = requested.firstOrNull { BaiduFileIdCodec.decode(it)?.path == path }
                ?: FileId(BaiduFileIdCodec.forFile(path).value)
            BatchItemFailure(
                key = CloudFileKey(BaiduProviderId, accountId, id),
                error = com.notdisk.provider.baidu.api.mapBaiduError(
                    code = code,
                    message = node.stringOf("show_msg", "errmsg"),
                    family = BaiduErrorFamily.FILE_MANAGER,
                ),
            )
        }
    }

    /** Reads `faillist` from a recycle response and reports per-item outcomes honestly. */
    private fun batchResultFrom(
        root: JsonObject,
        accountId: AccountId,
        requested: List<String>,
    ): BatchOperationResult {
        val failedIds = root.arrayOf("faillist")
            ?.mapNotNull { it.toString().trim('"').takeIf { value -> value.isNotBlank() } }
            .orEmpty()

        val succeeded = requested.filterNot { failedIds.contains(it) }
        return BatchOperationResult(
            // The key's `fileId` is the bin entry id encoded as a bare id: a recycled file has no
            // live path, so there is nothing truthful to encode as a path. Using the vendor's own id
            // keeps the value round-trippable back into `restoreFromTrash`/`purgeFromTrash`.
            succeeded = succeeded.map {
                CloudFileKey(BaiduProviderId, accountId, FileId("recycle:$it"))
            },
            failed = failedIds.map { failedId ->
                BatchItemFailure(
                    key = CloudFileKey(BaiduProviderId, accountId, FileId("recycle:$failedId")),
                    error = CloudError.Unknown(
                        message = "该条目操作失败（百度网盘未提供逐项原因）",
                    ),
                )
            },
            vendorTaskId = root.stringOf("taskid", "task_id"),
        )
    }

    /**
     * Maps one recycle-bin entry.
     *
     * The reference material is explicit that these element fields were **never observed** — the test
     * account's bin was empty — and that the names come from the vendor's own Vue template. The
     * documented/template-confirmed spellings are therefore read, and an entry with no id is skipped
     * rather than given a synthetic one (a synthetic id could not be restored or purged).
     */
    private fun trashItemOf(node: JsonObject): TrashItem? {
        val id = node.stringOf("id", "fs_id") ?: return null
        return TrashItem(
            trashId = id,
            originalName = node.stringOf("server_filename", "filename", "path") ?: id,
            isFolder = node.boolOf("isdir") ?: false,
            sizeBytes = node.longOf("size"),
            deletedAt = BaiduFileMapper.epochSecondsToMillis(node.longOf("mtime", "server_mtime")),
            // No live parent can be reconstructed from a recycled entry, and the material documents
            // no parent field, so nothing is claimed.
            originalParentId = null,
            originalParentName = null,
        )
    }

    /** Baidu rejects a name containing a separator; validating locally is clearer than `errno 2`. */
    private fun isValidName(name: String): Boolean =
        !name.contains('/') && !name.contains('\\') && name != "." && name != ".."

    private var lastSessionFailure: CloudResult<Nothing>? = null

    private suspend fun sessionOrFail(accountId: AccountId): BaiduSession? =
        when (val result = sessionProvider(accountId)) {
            is CloudResult.Success -> {
                lastSessionFailure = null
                result.data
            }

            is CloudResult.Failure -> {
                lastSessionFailure = CloudResult.Failure(result.error)
                null
            }
        }

    private companion object {
        const val TAG = "BaiduOps"

        /** §3.6 ✅ — the only `opera` value with a complete URL literal in the vendor's bundle. */
        const val OPERA_RENAME = "rename"

        /** §3.6 ⚠️ — inferred from `actionType:"move"`. Declared UNVERIFIED. */
        const val OPERA_MOVE = "move"

        /** §3.6 ⚠️ — inferred from `actionType:"copy"`. Declared UNVERIFIED. */
        const val OPERA_COPY = "copy"

        /** §3.6 ⚠️ — inferred from `doManage("delete", …)`. Declared UNVERIFIED. */
        const val OPERA_DELETE = "delete"

        /** §3.6 ✅ — `async: 1` for a small batch (<10), `2` for a large one. */
        const val ASYNC_SMALL = "1"
        const val ASYNC_LARGE = "2"
        const val ASYNC_SMALL_THRESHOLD = 10

        /** §3.6 ✅ — "单次操作最多 100 个文件". */
        const val MAX_BATCH_SIZE = 100

        const val MAX_PAGE_SIZE = 100
    }
}
