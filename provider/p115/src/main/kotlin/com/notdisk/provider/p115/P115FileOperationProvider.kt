package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.DownloadRequest
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
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
import com.notdisk.provider.p115.api.P115Endpoints
import com.notdisk.provider.p115.api.P115Hosts
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.asIntOrNull
import com.notdisk.provider.p115.api.asLongOrNull
import com.notdisk.provider.p115.api.asNonBlankStringOrNull
import com.notdisk.provider.p115.api.intOf
import com.notdisk.provider.p115.api.longOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import com.notdisk.provider.p115.auth.P115Session
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.JsonObject

/**
 * 115's mutating surface.
 *
 * ### The indexed-parameter rule (§5.3, §5.4, §8.2, §8.3 and §15.2 item 3)
 * Every batch endpoint on this API requires **indexed** parameter names:
 * ```
 * move:      fid[0]=…&fid[1]=…
 * copy:      fid[0]=…&fid[1]=…
 * rb/delete: fid[0]=…&fid[1]=…
 * rb/revert: rid[0]=…&rid[1]=…
 * rb/clean:  rid[0]=…&rid[1]=…&password=…
 * ```
 * The manual measured that `file_ids=`, `file_id=`, `ids=` and `rid[]=` all fail — `errno 1001` for
 * the file endpoints, `errno 231013`/`231012` for the trash. Because a `Map<String, String>` cannot
 * hold repeated keys safely, every batch call here builds an **ordered list of pairs** and goes
 * through [P115HttpClient.postIndexedForm].
 *
 * ### Write safety (§12 of the brief)
 * `FileOperationCapabilities.idempotentWrites` is `false` for 115, and this class does not retry.
 * The reason is concrete rather than theoretical: `files/copy` is *not* idempotent, so a retry after
 * a timeout would create a second copy. Moves and deletes are idempotent in effect, but the caller
 * still decides, because only it knows whether the previous attempt actually landed.
 *
 * ### Upload
 * The upload flow (§6) is documented but marked `📖` (source-derived, never executed against the
 * live service), and §15.4 lists "上传 OSS 直传的实际回调" as unverified. Implementing it would mean
 * writing to a real account with an unverified protocol. This build therefore reports
 * `CapabilitySupport.UNVERIFIED` for upload and returns a typed `Unsupported` from [upload] with an
 * explanation, which is exactly what the brief's rule "不要伪造真实成功" requires.
 */
internal class P115FileOperationProvider(
    private val http: P115HttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<P115Session>,
    private val pickcodes: P115PickcodeResolver,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : FileOperationProvider {

    /** Download transfers are tracked locally; 115 hands out a link and the app does the transfer. */
    private val transfers = ConcurrentHashMap<String, MutableStateFlow<TransferProgress>>()

    override suspend fun createFolder(
        accountId: AccountId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (name.isBlank()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件夹名不能为空"))
        }
        // 115 rejects names containing separators; validating locally avoids a pointless round trip
        // and gives a clearer message than `errno 1001`.
        if (name.contains('/') || name.contains('\\')) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件夹名不能包含路径分隔符"))
        }

        val parentCid = P115FileIdCodec.decode(parentId)?.cid
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的父目录标识: $parentId"),
            )

        // §5.1: `POST /files/add` with `pid` + `cname`.
        val request = http.postForm(
            url = http.resolve(P115Endpoints.FILES_ADD),
            form = mapOf("pid" to parentCid, "cname" to name),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.add")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // §5.1's measured response puts the new id in BOTH `cid` and `file_id`. `file_id` is
                // preferred because it is the field the rest of the API uses for a directory id.
                val newId = result.data.string("cid", "file_id")
                    ?: result.data.dataString("cid", "file_id")
                    ?: return CloudResult.Failure(
                        CloudError.ParseError(message = "115 未返回新建目录的 ID"),
                    )
                val newName = result.data.string("cname", "file_name") ?: name
                CloudResult.Success(
                    FileItem(
                        id = FileId(P115FileIdCodec.forFolder(newId).value),
                        providerId = P115ProviderId,
                        accountId = accountId,
                        name = newName,
                        type = com.notdisk.core.model.FileType.FOLDER,
                        parentId = FileId(P115FileIdCodec.forFolder(parentCid).value),
                        createdTime = clock(),
                        modifiedTime = clock(),
                        capabilities = com.notdisk.core.model.FileCapabilities(
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
     * §5.2: rename via `POST /files/batch_rename`.
     *
     * The manual is emphatic that `/files/edit` with `cid`/`file_id`/`n` returns `errno 1001`, and
     * that the new name must be sent as `files_new_name[<id>]`. Both requirements are honoured here.
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
        if (newName.contains('/') || newName.contains('\\')) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能包含路径分隔符"))
        }

        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )
        // The rename endpoint keys on the target's id: `fid` for a file, `cid` for a folder.
        val targetId = ref.fid ?: ref.cid
            ?: return CloudResult.Failure(CloudError.InvalidArgument(message = "文件标识缺少 ID"))

        val request = http.postIndexedForm(
            url = http.resolve(P115Endpoints.FILES_BATCH_RENAME),
            pairs = listOf(
                "fid" to targetId,
                "file_name" to newName,
                "files_new_name[$targetId]" to newName,
                "aid" to PERSONAL_SPACE_AID,
            ),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.batch_rename")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // The response echoes the new state; re-reading it is unnecessary because the caller
                // supplied the only changed field. The returned item is therefore built locally and
                // the id is re-encoded with the new name implicit (the id does not contain a name).
                val updated = ref
                CloudResult.Success(
                    FileItem(
                        id = FileId(updated.encode()),
                        providerId = P115ProviderId,
                        accountId = accountId,
                        name = newName,
                        type = if (ref.isFolder) {
                            com.notdisk.core.model.FileType.FOLDER
                        } else {
                            com.notdisk.core.model.FileType.FILE
                        },
                        parentId = ref.cid?.let { FileId(P115FileIdCodec.forFolder(it).value) },
                        modifiedTime = clock(),
                        capabilities = com.notdisk.core.model.FileCapabilities(
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

    /** §5.3: `POST /files/move` with `pid` + `fid[i]`. */
    override suspend fun move(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> = batchMutation(
        accountId = accountId,
        fileIds = fileIds,
        tag = "files.move",
        operationName = "移动",
    ) { session, targetCid ->
        http.postIndexedForm(
            url = http.resolve(P115Endpoints.FILES_MOVE),
            pairs = buildList {
                add("pid" to targetCid)
                fileIds.forEachIndexed { index, id -> add("fid[$index]" to requireVendorId(id)) }
            },
            cookies = session.credential.cookieHeader(),
        )
    }

    /** §5.4: `POST /files/copy`, same indexed-`fid` requirement as move. */
    override suspend fun copy(
        accountId: AccountId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> = batchMutation(
        accountId = accountId,
        fileIds = fileIds,
        tag = "files.copy",
        operationName = "复制",
    ) { session, targetCid ->
        http.postIndexedForm(
            url = http.resolve(P115Endpoints.FILES_COPY),
            pairs = buildList {
                add("pid" to targetCid)
                fileIds.forEachIndexed { index, id -> add("fid[$index]" to requireVendorId(id)) }
            },
            cookies = session.credential.cookieHeader(),
        )
    }

    /**
     * §8.2: `POST /rb/delete` moves items to the recycle bin.
     *
     * `permanent = true` is refused with `Unsupported`, pointing the caller at [purgeFromTrash]. That
     * is not a limitation of the client: §8.4 measured that permanent deletion is a *different*
     * endpoint (`rb/clean`) which additionally requires the account's safe key. Silently downgrading
     * a permanent delete into a recycle-bin delete would be a data-safety lie.
     */
    override suspend fun delete(
        accountId: AccountId,
        fileIds: List<FileId>,
        permanent: Boolean,
    ): CloudResult<BatchOperationResult> {
        if (permanent) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "115 的彻底删除需要单独的接口与账号安全密钥；普通删除只会移入回收站。" +
                        "请使用回收站中的“彻底删除”。",
                    capability = "operations.delete.permanent",
                ),
            )
        }
        return batchMutation(
            accountId = accountId,
            fileIds = fileIds,
            tag = "rb.delete",
            operationName = "删除",
        ) { session, _ ->
            http.postIndexedForm(
                url = http.resolve(P115Endpoints.RB_DELETE),
                pairs = buildList {
                    add("aid" to PERSONAL_SPACE_AID)
                    fileIds.forEachIndexed { index, id -> add("fid[$index]" to requireVendorId(id)) }
                    // §8.2: the manual notes `ignore_warn=1` accompanies the delete.
                    add("ignore_warn" to "1")
                },
                cookies = session.credential.cookieHeader(),
            )
        }
    }

    /**
     * §6: upload.
     *
     * Returns a typed `Unsupported` rather than a fabricated success. See the class KDoc for why the
     * documented OSS-direct protocol is not implemented in this build.
     */
    override suspend fun upload(
        accountId: AccountId,
        request: UploadRequest,
    ): CloudResult<TransferTask> {
        sessionOrFail(accountId)?.let { /* session is still validated so the error is accurate */ }
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未实现 115 上传：文档中的 OSS 直传协议尚未在真实账号上验证，" +
                    "为避免产生错误数据，暂不提供。",
                capability = "operations.upload",
            ),
        )
    }

    /**
     * §7.1: resolve a short-lived direct download link.
     *
     * The response's `file_url` is a **signed, short-lived** URL (`t` = expiry, `k` = signature), so
     * it is returned as a [DownloadSource] — a type that is deliberately not persistable — rather
     * than being stored on the transfer record.
     */
    override suspend fun prepareDownload(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<DownloadSource> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!

        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )
        if (ref.isFolder) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "115 文件夹下载需要单独的打包接口，本版本未实现",
                    capability = "operations.download.folderArchive",
                ),
            )
        }

        val fid = ref.fid
            ?: return CloudResult.Failure(CloudError.InvalidArgument(message = "文件标识缺少 fid"))

        // §7.1's example sends both `pickcode` and `file_id`; sending both keeps the request valid
        // whether or not the server requires the pickcode.
        val pickcode = pickcodes.resolve(accountId, fileId, session).getOrNull()
        val query = buildMap {
            put("file_id", fid)
            pickcode?.let { put("pickcode", it) }
        }

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_DOWNLOAD),
            query = query,
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.download")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                // §7.1's payload is at the top level (`file_url`), not under `data`.
                val payload = envelope.dataObject ?: envelope.root
                val fileUrl = payload.stringOf("file_url", "url")
                    ?: return CloudResult.Failure(
                        CloudError.Unsupported(
                            message = "115 未返回下载直链，该文件可能已被限制下载",
                            capability = "operations.download",
                        ),
                    )

                // §7.1's parameter table: `t` is the expiry timestamp and `us` a size ceiling.
                val expiresAt = parseExpiryFromUrl(fileUrl)
                logger.i(
                    TAG,
                    "download link resolved for file=${LogRedaction.redactId(fileId.value)}, " +
                        "url=${LogRedaction.redactUrl(fileUrl)}",
                )

                CloudResult.Success(
                    DownloadSource(
                        url = fileUrl,
                        // §15.2 item 14: the direct link does NOT carry a Content-Disposition, so
                        // the file name must come from the payload.
                        fileName = payload.stringOf("file_name") ?: "download",
                        sizeBytes = payload.longOf("file_size"),
                        mimeType = null,
                        // §1.4: the CDN validates attribution headers.
                        headers = mapOf(
                            "Referer" to "https://115.com/",
                            "Origin" to "https://115.com",
                            "User-Agent" to P115HttpClient.DEFAULT_USER_AGENT,
                        ),
                        expiresAt = expiresAt,
                        // A direct CDN link to a plain file supports byte ranges, but the manual
                        // does not state it, so the value stays undetermined rather than asserted.
                        supportsRangeRequests = null,
                        // §7.1's advice: never cache `file_url`; always re-resolve.
                        refreshable = true,
                    ),
                )
            }
        }
    }

    /**
     * Starts a download.
     *
     * 115 does not run the transfer: it hands out a link and the client fetches it. So the actual
     * byte movement belongs to the platform download worker, which is given the [DownloadSource].
     * This method therefore only creates and tracks the task record, which keeps the provider free
     * of Android scheduling concerns.
     */
    override suspend fun download(
        accountId: AccountId,
        request: DownloadRequest,
        source: DownloadSource,
    ): CloudResult<TransferTask> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!

        val transferId = "p115-dl-${UUID.randomUUID()}"
        val task = TransferTask(
            id = transferId,
            key = CloudFileKey(P115ProviderId, accountId, FileId(source.fileName)),
            direction = TransferDirection.DOWNLOAD,
            fileName = source.fileName,
            totalBytes = source.sizeBytes,
            transferredBytes = request.startOffset,
            status = TransferStatus.QUEUED,
            destinationPath = request.destinationPath,
            createdAt = clock(),
            updatedAt = clock(),
            // A download is idempotent: re-fetching a byte range cannot corrupt remote state.
            autoRetryable = true,
        )
        transfers[transferId] = MutableStateFlow(task.progress)
        logger.i(TAG, "download task $transferId created (account ${session.accountId})")
        return CloudResult.Success(task)
    }

    /**
     * Emits progress and **completes on a terminal state**, matching the shared contract.
     *
     * 115's transfer progress is produced by the platform download worker, which reports into this
     * flow via [reportDownloadProgress]. The initial emission is the queued state so a collector
     * always sees something immediately.
     */
    override fun observeTransfer(transferId: String): Flow<TransferProgress> {
        val stateFlow = transfers[transferId]
            ?: return flowOf(TransferProgress(0, null, TransferStatus.FAILED))
        return stateFlow.asStateFlow().transformWhile { progress ->
            emit(progress)
            !progress.status.isTerminal
        }
    }

    /** Platform hook: the download worker reports its own progress through this. */
    fun reportDownloadProgress(transferId: String, progress: TransferProgress) {
        transfers[transferId]?.value = progress
    }

    override suspend fun cancelTransfer(transferId: String): CloudResult<Unit> {
        transfers[transferId]?.let { flow ->
            flow.value = flow.value.copy(status = TransferStatus.CANCELLED)
        }
        // Cancelling a download requires no server call: 115 has no transfer to abort.
        return CloudResult.Success(Unit)
    }

    override suspend fun retryTransfer(transferId: String): CloudResult<TransferTask> {
        val flow = transfers[transferId]
            ?: return CloudResult.Failure(CloudError.NotFound(message = "任务不存在: $transferId"))
        flow.value = flow.value.copy(status = TransferStatus.QUEUED)
        return CloudResult.Success(
            TransferTask(
                id = transferId,
                key = CloudFileKey(P115ProviderId, AccountId(""), FileId("")),
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
     * §12: sharing.
     *
     * `POST /usershare/share` is documented as `📖` (source-derived only) and §15.4 does not list it
     * as verified. Because creating a share publishes the user's data to a URL, implementing an
     * unverified publish operation would be the single most dangerous thing this build could do. It
     * is therefore declared `UNVERIFIED` and returns a typed `Unsupported`.
     */
    override suspend fun share(
        accountId: AccountId,
        fileIds: List<FileId>,
        enable: Boolean,
        password: String?,
    ): CloudResult<ShareResult> {
        sessionOrFail(accountId)?.let { }
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未实现 115 分享创建：该接口尚未在真实账号上验证，" +
                    "避免在未验证的情况下对外发布文件。",
                capability = "operations.share",
            ),
        )
    }

    /**
     * §5.6: `POST /files/star`.
     *
     * The manual records that a GET returns `405 METHOD NOT ALLOWED`, so POST is used.
     */
    override suspend fun setStarred(
        accountId: AccountId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何文件"))
        }

        // The endpoint takes a single `file_id` plus the *current folder* (`cid`) in the measured
        // request, so a batch is issued as one request per file. Sequential rather than parallel on
        // purpose: a burst of concurrent writes on one account is exactly what triggers 115's risk
        // control (§2.1's polling warning is the same concern).
        var lastError: CloudError? = null
        for (fileId in fileIds) {
            val ref = P115FileIdCodec.decode(fileId) ?: continue
            val targetId = ref.fid ?: ref.cid ?: continue

            val request = http.postForm(
                url = http.resolve(P115Endpoints.FILES_STAR),
                form = buildMap {
                    put("aid", PERSONAL_SPACE_AID)
                    ref.cid?.let { put("cid", it) }
                    put("file_id", targetId)
                    put("star", if (starred) "1" else "0")
                },
                cookies = session.credential.cookieHeader(),
            )

            when (val result = http.execute(request, tag = "files.star")) {
                is CloudResult.Failure -> lastError = result.error
                is CloudResult.Success -> Unit
            }
        }

        return lastError?.let { CloudResult.Failure(it) } ?: CloudResult.Success(Unit)
    }

    /** §8.1: `GET /rb` — the recycle bin. */
    override suspend fun listTrash(
        accountId: AccountId,
        cursor: String?,
        pageSize: Int,
    ): CloudResult<TrashPage> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        val offset = cursor?.toIntOrNull() ?: 0

        val request = http.get(
            url = http.resolve(P115Endpoints.RB),
            query = mapOf(
                "aid" to PERSONAL_SPACE_AID,
                "offset" to offset.toString(),
                "limit" to pageSize.coerceIn(1, MAX_PAGE_SIZE).toString(),
                "format" to "json",
            ),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "rb.list")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                val entries = envelope.dataAsArray.mapNotNull { it as? JsonObject }
                val nextOffset = offset + entries.size

                CloudResult.Success(
                    TrashPage(
                        items = entries.mapNotNull { node ->
                            // §8.1: `id` here is the **rid**, which is deliberately distinct from the
                            // original `file_id`; confusing them is a documented upstream bug source.
                            val rid = node.stringOf("id", "rid") ?: return@mapNotNull null
                            TrashItem(
                                trashId = rid,
                                originalName = node.stringOf("file_name", "n") ?: return@mapNotNull null,
                                // §8.1's field table: `type` is "1=文件 2=文件夹".
                                isFolder = node.intOf("type") == 2,
                                sizeBytes = node.longOf("file_size", "s"),
                                deletedAt = P115FileMapper.epochSecondsToMillis(node.longOf("dtime")),
                                originalParentId = node.stringOf("cid")
                                    ?.let { FileId(P115FileIdCodec.forFolder(it).value) },
                                originalParentName = node.stringOf("parent_name"),
                            )
                        },
                        // Same terminating rule as listings: a short page ends the sequence.
                        nextCursor = if (entries.size >= pageSize && entries.isNotEmpty()) {
                            nextOffset.toString()
                        } else {
                            null
                        },
                        totalCount = envelope.long("count"),
                        // §8.1: `rb_pass` reports whether a recycle-bin password is set.
                        protectedBySecret = envelope.int("rb_pass") == 1,
                    ),
                )
            }
        }
    }

    /** §8.3: `POST /rb/revert` with `rid[i]`. */
    override suspend fun restoreFromTrash(
        accountId: AccountId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (trashIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何回收站条目"))
        }

        val request = http.postIndexedForm(
            url = http.resolve(P115Endpoints.RB_REVERT),
            pairs = trashIds.mapIndexed { index, rid -> "rid[$index]" to rid },
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "rb.revert")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> CloudResult.Success(
                // `rb/revert` is all-or-nothing: the API reports one state for the whole batch, so
                // claiming per-item outcomes would be inventing detail the response does not carry.
                BatchOperationResult(
                    succeeded = trashIds.map {
                        CloudFileKey(P115ProviderId, accountId, FileId(it))
                    },
                ),
            )
        }
    }

    /**
     * §8.4: `POST /rb/clean` — permanent deletion.
     *
     * Two measured requirements that are easy to miss:
     *  - the parameter must be `rid[i]` (indexed); `rid[]`, a comma list and `all=1` all fail;
     *  - the account's **safe key** must be supplied as `password` when one is set, and an empty
     *    value produces `{"state":false,"error":"密钥错误"}`.
     *
     * The safe key is accepted as an argument and never stored, per the brief's §6.3. Callers obtain
     * it from the user after checking [P115AuthProvider.isSafeKeySet].
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

        val request = http.postIndexedForm(
            url = http.resolve(P115Endpoints.RB_CLEAN),
            pairs = buildList {
                trashIds.forEachIndexed { index, rid -> add("rid[$index]" to rid) }
                // §8.4: when the account has no safe key, `password` must be present but empty.
                add("password" to (safeKey ?: ""))
            },
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "rb.clean")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> CloudResult.Success(
                BatchOperationResult(
                    succeeded = trashIds.map {
                        CloudFileKey(P115ProviderId, accountId, FileId(it))
                    },
                ),
            )
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Shared driver for the three indexed-`fid` batch endpoints.
     *
     * It validates locally, issues exactly one request, and translates the vendor's failure into an
     * honest shape: when the whole call fails there are no per-item successes to report, so the
     * failure is returned as a failure rather than as an all-items-failed [BatchOperationResult]
     * (which would falsely suggest the server had evaluated each item).
     */
    private suspend fun batchMutation(
        accountId: AccountId,
        fileIds: List<FileId>,
        tag: String,
        operationName: String,
        buildRequest: (P115Session, String) -> okhttp3.Request,
    ): CloudResult<BatchOperationResult> {
        val session = sessionOrFail(accountId) ?: return lastSessionFailure!!
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "$operationName 操作未选择任何文件"),
            )
        }

        val missing = fileIds.filterNot { P115FileIdCodec.matches(it) }
        if (missing.isNotEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "$operationName 操作包含无法解析的文件标识: ${missing.first()}",
                ),
            )
        }

        val targetCid = ROOT_CID
        val request = buildRequest(session, targetCid)

        return when (val result = http.execute(request, tag = tag)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // §5.3: a move may be processed asynchronously and reports `move_proid`. It is
                // surfaced so a caller can poll `files/move_progress` if it wants to.
                val vendorTaskId = result.data.string("move_proid", "task_id")
                if (vendorTaskId != null) {
                    logger.i(TAG, "$tag accepted asynchronously, task=$vendorTaskId")
                }
                CloudResult.Success(
                    BatchOperationResult(
                        succeeded = fileIds.map { CloudFileKey(P115ProviderId, accountId, it) },
                        vendorTaskId = vendorTaskId,
                    ),
                )
            }
        }
    }

    /**
     * Extracts the expiry from a signed download URL's `t` parameter (§7.1's table).
     *
     * Returns `null` when the parameter is absent rather than guessing a lifetime: an invented
     * expiry would either discard a still-valid link or keep using a dead one.
     */
    private fun parseExpiryFromUrl(url: String): Long? {
        val t = runCatching {
            url.substringAfter('?')
                .split('&')
                .firstOrNull { it.startsWith("t=") }
                ?.removePrefix("t=")
                ?.toLongOrNull()
        }.getOrNull() ?: return null
        // `t` is a Unix-seconds timestamp (§7.1: "过期时间戳").
        return P115FileMapper.epochSecondsToMillis(t)
    }

    private fun requireVendorId(fileId: FileId): String {
        val ref = P115FileIdCodec.decode(fileId)
        return ref?.fid ?: ref?.cid ?: fileId.value
    }

    /**
     * Resolves the session or records the failure for the caller's early return.
     *
     * The `lastSessionFailure` field is used instead of returning a `CloudResult` pair because every
     * operation needs the *credential*, not merely a success/failure signal, and Kotlin has no
     * built-in "either" that keeps the call sites as readable as this.
     */
    private var lastSessionFailure: CloudResult<Nothing>? = null

    private suspend fun sessionOrFail(accountId: AccountId): P115Session? =
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
        const val TAG = "P115Ops"

        /** §1.5: `aid=1` is the personal space. */
        const val PERSONAL_SPACE_AID = "1"

        const val ROOT_CID = "0"

        const val MAX_PAGE_SIZE = 200
    }
}
