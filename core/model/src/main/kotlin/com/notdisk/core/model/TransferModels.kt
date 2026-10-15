package com.notdisk.core.model

/**
 * A long-running upload or download.
 *
 * The task record holds **only** stable, non-secret data, so it can be persisted and shown after
 * a process restart. In particular:
 *  - [sourceUri] is an Android `content://` URI (or a local path) selected by the user — a local
 *    reference, not a credential;
 *  - a remote *download URL* is never stored here; it is re-resolved from [targetKey] on resume;
 *  - the local destination path is a plain filesystem path.
 */
data class TransferTask(
    val id: String,
    val key: CloudFileKey,
    val direction: TransferDirection,
    val fileName: String,
    val totalBytes: Long?,
    val transferredBytes: Long,
    val status: TransferStatus,
    /**
     * For uploads: the local `content://` or `file://` source.
     * For downloads: `null` until a destination is chosen.
     */
    val sourceUri: String? = null,
    /** For downloads: the local destination path chosen via SAF / app storage. */
    val destinationPath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /**
     * True when the transfer may be retried automatically after a transient failure.
     * Downloads are resumable/idempotent; uploads are **not** blindly retried (a duplicate file
     * could be created), so this is `false` unless the provider declares safe resumption.
     */
    val autoRetryable: Boolean = false,
) {
    val progress: TransferProgress
        get() = TransferProgress(transferredBytes, totalBytes, status)
}

enum class TransferDirection { UPLOAD, DOWNLOAD }

enum class TransferStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class TransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long?,
    val status: TransferStatus,
) {
    /** Fraction in `0f..1f`, or `null` when the total size is unknown. */
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let { (transferredBytes.toFloat() / it).coerceIn(0f, 1f) }
}

/**
 * A request to begin an upload.
 *
 * [source] is intentionally a URI string rather than a `File`: on Android the Storage Access
 * Framework hands back `content://` URIs that frequently have no filesystem path at all
 * (cloud-backed providers, `MediaStore`, scoped storage). Providers must open the stream through
 * a [TransferSource] abstraction supplied by the platform layer.
 */
data class UploadRequest(
    val key: CloudFileKey,
    /** Destination folder. */
    val parentId: FileId,
    val fileName: String,
    val totalBytes: Long?,
    val mimeType: String? = null,
    /** `content://` or `file://` URI of the local source. */
    val sourceUri: String,
    /** Client-side duplicate policy. */
    val collisionPolicy: CollisionPolicy = CollisionPolicy.RENAME,
)

/** What to do when a name already exists at the destination. */
enum class CollisionPolicy {
    /** Fail with a typed error rather than guessing. */
    FAIL,

    /** Ask the vendor to auto-uniquify (115 appends a suffix server-side). */
    RENAME,

    OVERWRITE,
}

/**
 * A request to begin a download.
 *
 * The download URL is deliberately absent: the transfer layer resolves a fresh one right before
 * it starts (and again if it expires mid-transfer), because direct links are short-lived.
 */
data class DownloadRequest(
    val key: CloudFileKey,
    val fileName: String,
    /** Local destination path (app-private storage or a SAF-provided path). */
    val destinationPath: String,
    /** Resume from this offset when the server supports Range. */
    val startOffset: Long = 0L,
)
