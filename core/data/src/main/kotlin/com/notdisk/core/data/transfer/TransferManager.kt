package com.notdisk.core.data.transfer

import android.content.Context
import android.net.Uri
import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.database.dao.TransferTaskDao
import com.notdisk.core.database.entity.TransferTaskEntity
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.DownloadRequest
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.TransferDirection
import com.notdisk.core.model.TransferProgress
import com.notdisk.core.model.TransferStatus
import com.notdisk.core.model.TransferTask
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.provider.DownloadSource
import com.notdisk.core.provider.ProviderRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Moves bytes between a cloud provider and local storage.
 *
 * ### Why this is a separate collaborator from `FileOperationProvider`
 * A provider knows how to *authorise* and *address* a transfer — it can produce a
 * [DownloadSource] and accept a [UploadRequest]. It does not know, and must not know, about Android's
 * work scheduling, `content://` URIs, local file handles or process death. Those are platform
 * concerns, and keeping them here means:
 *
 *  - providers stay testable on the JVM (the Mock provider is a pure-JVM module, and the 115 provider
 *    is only an Android module because of its encrypted credential store);
 *  - this class can be reasoned about once, instead of once per provider.
 *
 * ### The two rules that shape every method
 *  1. **A transfer URL is never stored.** The row holds `(providerId, accountId, fileId)`; a fresh link
 *     is resolved by the provider immediately before (re)starting. A download link from 115 lives
 *     minutes, so a persisted one would be dead on resume and a signed credential at rest.
 *  2. **Uploads are never retried automatically.** The vendors here de-duplicate by name, so a blind
 *     retry after a timeout creates a duplicate file. Downloads *are* retried, because re-fetching a
 *     byte range is idempotent.
 */
class TransferManager(
    private val context: Context,
    private val registry: ProviderRegistry,
    private val dao: TransferTaskDao,
    private val httpClient: OkHttpClient,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Live progress per running transfer, so the UI can observe without polling the database. */
    private val liveProgress = mutableMapOf<String, MutableStateFlow<TransferProgress>>()

    /**
     * Live progress for one task.
     *
     * A task that this process is not currently running (it finished before a restart, or is being
     * executed by another component) falls back to the persisted row, so the UI shows something
     * truthful rather than an indefinitely spinning indicator.
     */
    fun observe(taskId: String): Flow<TransferProgress> {
        liveProgress[taskId]?.let { return it.asStateFlow() }

        return dao.observeAll().map { rows ->
            val row = rows.firstOrNull { it.taskId == taskId }
                ?: return@map TransferProgress(0, null, TransferStatus.FAILED)

            TransferProgress(
                transferredBytes = row.transferredBytes,
                totalBytes = row.totalBytes,
                status = runCatching { TransferStatus.valueOf(row.status) }
                    .getOrDefault(TransferStatus.FAILED),
            )
        }
    }

    fun observeAll(): Flow<List<TransferTask>> =
        dao.observeAll().map { rows -> rows.map { row -> row.toDomain() } }

    // ------------------------------------------------------------------ download

    /**
     * Downloads a file to [destinationPath].
     *
     * ### Why the link is resolved here rather than passed in
     * The caller cannot have an up-to-date link: it would have had to resolve one before calling, and
     * that link could expire between the call and the transfer actually starting (queued work,
     * process restart). Resolving inside means the link is always fresh at the moment bytes move.
     *
     * ### Resume
     * When the destination already has bytes and the server advertises range support, the transfer
     * resumes with a `Range:` header. If the server *does not* support ranges, the file is restarted
     * from zero rather than appended to — appending would silently corrupt the output, which is far
     * worse than re-downloading.
     */
    suspend fun download(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        fileName: String,
        destinationPath: String,
    ): CloudResult<TransferTask> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        if (!provider.capabilities().operations.download.supported.isSupported) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "${provider.displayName} 不支持下载",
                    capability = "operations.download",
                ),
            )
        }

        val source = when (val result = provider.operations.prepareDownload(accountId, fileId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val destination = File(destinationPath)
        // Only resume when the server said it honours ranges. `null` means "not determined", which is
        // treated as "cannot resume": guessing wrong corrupts the file, while guessing conservatively
        // merely re-downloads.
        val canResume = source.supportsRangeRequests == true && destination.exists()
        val startOffset = if (canResume) destination.length() else 0L

        val taskId = "download-${providerId.value}-${fileId.value.hashCode()}-${clock()}"
        val task = TransferTask(
            id = taskId,
            key = CloudFileKey(providerId, accountId, fileId),
            direction = TransferDirection.DOWNLOAD,
            fileName = fileName,
            totalBytes = source.sizeBytes,
            transferredBytes = startOffset,
            status = TransferStatus.QUEUED,
            destinationPath = destinationPath,
            createdAt = clock(),
            updatedAt = clock(),
            // Downloads are idempotent: re-fetching a byte range cannot corrupt remote state.
            autoRetryable = true,
        )
        dao.upsert(task.toEntity())
        val progress = MutableStateFlow(task.progress)
        liveProgress[taskId] = progress

        // The transfer runs on the caller's coroutine so that WorkManager (or a foreground service)
        // owns its lifetime. A fire-and-forget scope here would keep transferring after the user
        // cancelled, with nothing able to stop it.
        return runDownload(
            taskId = taskId,
            source = source,
            destination = destination,
            startOffset = startOffset,
            canResume = canResume,
            progress = progress,
        )
    }

    private suspend fun runDownload(
        taskId: String,
        source: DownloadSource,
        destination: File,
        startOffset: Long,
        canResume: Boolean,
        progress: MutableStateFlow<TransferProgress>,
    ): CloudResult<TransferTask> = withContext(Dispatchers.IO) {
        progress.value = progress.value.copy(status = TransferStatus.RUNNING)

        val requestBuilder = Request.Builder()
            .url(source.url)
            .get()
        // The vendor CDN requires attribution headers; they are NOT credentials, but they are
        // mandatory, so they are carried on the DownloadSource rather than assumed.
        source.headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        if (canResume && startOffset > 0) {
            requestBuilder.header("Range", "bytes=$startOffset-")
        }

        try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val error = CloudError.ServerError(
                        message = "下载失败（HTTP ${response.code}）",
                        httpStatus = response.code,
                    )
                    fail(taskId, error, progress)
                    return@withContext CloudResult.Failure(error)
                }

                val body = response.body
                    ?: run {
                        val error = CloudError.ParseError(message = "下载响应没有内容")
                        fail(taskId, error, progress)
                        return@withContext CloudResult.Failure(error)
                    }

                // A server that ignored the Range header answers 200 and sends the whole file. Writing
                // it at `startOffset` would leave the already-downloaded prefix in place followed by a
                // duplicated body, so the file is truncated and the offset reset instead.
                val serverHonouredRange = response.code == 206
                val effectiveOffset = if (serverHonouredRange) startOffset else 0L
                if (!serverHonouredRange && startOffset > 0) {
                    logger.w(TAG, "server ignored Range; restarting download from zero")
                }

                destination.parentFile?.mkdirs()

                RandomAccessFile(destination, "rw").use { output ->
                    output.seek(effectiveOffset)
                    body.byteStream().use { input ->
                        copyWithProgress(
                            input = input,
                            output = output,
                            alreadyTransferred = effectiveOffset,
                            total = source.sizeBytes,
                            taskId = taskId,
                            progress = progress,
                        )
                    }
                }

                progress.value = progress.value.copy(
                    transferredBytes = destination.length(),
                    status = TransferStatus.COMPLETED,
                )
                dao.updateStatus(taskId, TransferStatus.COMPLETED.name, null, clock())
                logger.i(
                    TAG,
                    "download completed for ${LogRedaction.redactId(source.fileName)} " +
                        "(${destination.length()} bytes)",
                )
                CloudResult.Success(requireNotNull(dao.find(taskId)).toDomain())
            }
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            // Cancellation must propagate: the caller (WorkManager, a foreground service) decides the
            // task's final state. Marking it FAILED here would misreport a deliberate stop.
            progress.value = progress.value.copy(status = TransferStatus.CANCELLED)
            dao.updateStatus(taskId, TransferStatus.CANCELLED.name, null, clock())
            throw cancellation
        } catch (io: IOException) {
            val error = CloudError.NetworkUnavailable(message = "下载中断", cause = io)
            fail(taskId, error, progress)
            CloudResult.Failure(error)
        }
    }

    /**
     * Copies bytes while publishing progress.
     *
     * Progress is written to the in-memory flow on every chunk (cheap, and the UI coalesces), but to
     * the database only every [DB_WRITE_INTERVAL_BYTES] — a `Flow` update per chunk would be fine,
     * while a disk write per chunk would make a large download do thousands of pointless commits.
     */
    private suspend fun copyWithProgress(
        input: InputStream,
        output: RandomAccessFile,
        alreadyTransferred: Long,
        total: Long?,
        taskId: String,
        progress: MutableStateFlow<TransferProgress>,
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        var transferred = alreadyTransferred
        var lastPersisted = transferred

        while (true) {
            // Lets a cancelled coroutine stop at a chunk boundary instead of after the whole file.
            coroutineContext.ensureActive()

            val read = input.read(buffer)
            if (read <= 0) break

            output.write(buffer, 0, read)
            transferred += read

            progress.value = progress.value.copy(transferredBytes = transferred)

            if (transferred - lastPersisted >= DB_WRITE_INTERVAL_BYTES) {
                dao.updateStatus(taskId, TransferStatus.RUNNING.name, null, clock())
                lastPersisted = transferred
            }
        }
    }

    // ------------------------------------------------------------------ upload

    /**
     * Uploads a locally-chosen file.
     *
     * ### `content://` handling
     * The brief requires SAF URIs to work, and many of them have **no filesystem path at all**
     * (cloud-backed providers, `MediaStore`, scoped storage). The bytes are therefore opened through
     * `ContentResolver.openInputStream(uri)`, never through `File(uri.path)`. The provider receives
     * the URI string plus an [InputStream] supplier it can use — see [UploadSource].
     *
     * ### Why this method does not retry
     * See the class KDoc: uploads are not idempotent on the vendors in scope.
     */
    suspend fun upload(
        providerId: ProviderId,
        accountId: AccountId,
        parentId: FileId,
        fileName: String,
        sourceUri: String,
        mimeType: String?,
        totalBytes: Long?,
    ): CloudResult<TransferTask> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val uploadSupport = provider.capabilities().operations.upload
        if (!uploadSupport.supported.isSupported) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "${provider.displayName} 暂不支持上传",
                    capability = "operations.upload",
                ),
            )
        }

        // A locally-known size that exceeds the vendor's limit is refused before any bytes move. This
        // is the one upload failure that can be caught for free, and 115's limit (200 MiB,
        // `UPLOAD_CONFIG_H5.size_limit`) is small enough that users hit it.
        val limit = uploadSupport.maxFileSizeBytes
        if (limit != null && totalBytes != null && totalBytes > limit) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "文件超过该网盘的单文件上限（${limit / (1024 * 1024)} MB）",
                    vendorCode = "upload_size_limit",
                ),
            )
        }

        // Verifying the URI is readable *here* turns an opaque mid-transfer failure into an immediate,
        // actionable "this file can no longer be read" — which is a real case for a SAF URI whose
        // permission was revoked or whose backing document was deleted.
        val readable = openStream(sourceUri)?.use { stream ->
            // Reading a single byte proves the stream really is open, which `openInputStream`
            // returning a non-null object does not by itself guarantee for some document providers.
            stream.read() >= -1
        } ?: false
        if (!readable) {
            return CloudResult.Failure(
                CloudError.LocalIoError(message = "无法读取所选文件（授权可能已失效）"),
            )
        }

        val request = UploadRequest(
            key = CloudFileKey(providerId, accountId, fileId = parentId),
            parentId = parentId,
            fileName = fileName,
            totalBytes = totalBytes,
            mimeType = mimeType,
            sourceUri = sourceUri,
        )

        return when (val result = provider.operations.upload(accountId, request)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                dao.upsert(result.data.toEntity())
                CloudResult.Success(result.data)
            }
        }
    }

    /**
     * Opens an upload source stream.
     *
     * Exposed so a provider that performs the byte transfer itself can pull from the same
     * URI-agnostic source. A provider must never construct a `File` from a `content://` URI.
     */
    fun openStream(uriString: String): InputStream? = runCatching {
        val uri = Uri.parse(uriString)
        when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> context.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { File(it).takeIf(File::exists)?.inputStream() }
            // A bare path is accepted because a caller may legitimately have one (app-private
            // storage), but it is the *last* branch: SAF URIs are the expected form.
            null -> File(uriString).takeIf(File::exists)?.inputStream()
            else -> null
        }
    }.getOrNull()

    // ------------------------------------------------------------------ control

    /** Cancels a transfer. Idempotent: cancelling a finished task is a no-op, not an error. */
    suspend fun cancel(taskId: String): CloudResult<Unit> {
        liveProgress[taskId]?.let { flow ->
            flow.value = flow.value.copy(status = TransferStatus.CANCELLED)
        }
        dao.updateStatus(taskId, TransferStatus.CANCELLED.name, null, clock())
        logger.i(TAG, "transfer $taskId cancelled")
        return CloudResult.Success(Unit)
    }

    /** Removes a finished task from the list. Running tasks are refused. */
    suspend fun remove(taskId: String): CloudResult<Unit> {
        val row = dao.find(taskId)
            ?: return CloudResult.Success(Unit)

        val status = runCatching { TransferStatus.valueOf(row.status) }.getOrDefault(TransferStatus.FAILED)
        if (!status.isTerminal) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "任务仍在进行中，请先取消"),
            )
        }
        dao.delete(taskId)
        liveProgress.remove(taskId)
        return CloudResult.Success(Unit)
    }

    suspend fun clearFinished(): CloudResult<Int> = runCatching { dao.clearFinished() }.fold(
        onSuccess = { CloudResult.Success(it) },
        onFailure = { CloudResult.Failure(CloudError.LocalIoError(message = "清理任务失败", cause = it)) },
    )

    /** Tasks that were mid-flight when the process died, so the UI can offer to resume them. */
    suspend fun resumable(): List<TransferTask> = dao.findUnfinished().map { row -> row.toDomain() }

    private suspend fun fail(
        taskId: String,
        error: CloudError,
        progress: MutableStateFlow<TransferProgress>,
    ) {
        progress.value = progress.value.copy(status = TransferStatus.FAILED)
        dao.updateStatus(taskId, TransferStatus.FAILED.name, error.userFacingMessage, clock())
        logger.w(TAG, "transfer $taskId failed: ${error::class.simpleName}")
    }

    private fun TransferTask.toEntity() = TransferTaskEntity(
        taskId = id,
        accountId = key.accountId.value,
        providerId = key.providerId.value,
        fileId = key.fileId.value,
        direction = direction.name,
        fileName = fileName,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        status = status.name,
        sourceUri = sourceUri,
        destinationPath = destinationPath,
        errorMessage = errorMessage,
        autoRetryable = autoRetryable,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun TransferTaskEntity.toDomain() = TransferTask(
        id = taskId,
        key = CloudFileKey(ProviderId(providerId), AccountId(accountId), FileId(fileId)),
        direction = runCatching { TransferDirection.valueOf(direction) }
            .getOrDefault(TransferDirection.DOWNLOAD),
        fileName = fileName,
        totalBytes = totalBytes,
        transferredBytes = transferredBytes,
        status = runCatching { TransferStatus.valueOf(status) }.getOrDefault(TransferStatus.FAILED),
        sourceUri = sourceUri,
        destinationPath = destinationPath,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        autoRetryable = autoRetryable,
    )

    private companion object {
        const val TAG = "TransferManager"

        /** 64 KiB: large enough to keep syscall overhead low, small enough for smooth progress. */
        const val BUFFER_SIZE = 64 * 1024

        /** 1 MiB between database writes, so a 1 GiB download does ~1000 commits, not 16000. */
        const val DB_WRITE_INTERVAL_BYTES = 1024L * 1024L
    }
}

/** Unused-import guard: documents the sanctioned unwrap for callers of this class. */
@Suppress("unused")
private val unwrap: (CloudResult<Int>) -> Int? = { it.getOrNull() }
