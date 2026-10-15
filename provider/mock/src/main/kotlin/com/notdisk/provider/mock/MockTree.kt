package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthSession
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.ProviderId

/**
 * The offline provider id.
 *
 * Kept as a constant because it is persisted with accounts (a developer's Mock account survives
 * an app restart, exactly like a real one) and used as a cache key namespace.
 */
val MOCK_PROVIDER_ID = ProviderId("mock")

const val MOCK_DISPLAY_NAME = "离线演示网盘 (Mock)"

/**
 * A deterministic, in-memory remote file tree.
 *
 * The tree is expressed once, here, as plain data. Everything else in the Mock provider is
 * derived from it, which keeps the "ground truth" for the shared contract tests in one readable
 * place instead of scattered across stub methods.
 *
 * Ids are stable strings (`mock:f-<n>`) rather than positional indexes so that a test can hard-code
 * one without depending on ordering.
 */
internal object MockTree {

    const val ROOT_ID = "mock:root"
    const val DOCUMENTS_ID = "mock:dir-documents"
    const val MEDIA_ID = "mock:dir-media"
    const val PHOTOS_ID = "mock:dir-photos"
    const val DEEP_ID = "mock:dir-deep"
    const val EMPTY_ID = "mock:dir-empty"
    const val RESTRICTED_ID = "mock:dir-restricted"
    const val LARGE_ID = "mock:dir-large"
    const val ARCHIVE_ID = "mock:dir-archive"
    const val TRASH_ORIGIN_ID = "mock:dir-trash-origin"

    /** Ids that must never resolve, used by the "unknown id" contract probes. */
    const val MISSING_ID = "mock:does-not-exist"

    val root = MockNode(
        id = ROOT_ID,
        name = "根目录",
        isFolder = true,
        parentId = null,
    )

    val nodes: List<MockNode> = listOf(
        root,

        // -------- top level --------
        MockNode(DOCUMENTS_ID, "文档", true, ROOT_ID),
        MockNode(MEDIA_ID, "媒体", true, ROOT_ID),
        MockNode(PHOTOS_ID, "照片", true, ROOT_ID),
        MockNode(ARCHIVE_ID, "压缩包", true, ROOT_ID),
        MockNode(EMPTY_ID, "空文件夹", true, ROOT_ID),
        MockNode(RESTRICTED_ID, "无权限目录", true, ROOT_ID, readable = false),
        MockNode(LARGE_ID, "大目录", true, ROOT_ID),
        MockNode(TRASH_ORIGIN_ID, "已删除内容", true, ROOT_ID),

        MockNode(
            id = "mock:f-readme",
            name = "readme.txt",
            isFolder = false,
            parentId = ROOT_ID,
            size = 1_024,
            mimeType = "text/plain",
            extension = "txt",
            isImage = false,
            isVideo = false,
            isAudio = false,
            previewable = true,
        ),
        MockNode(
            id = "mock:f-report",
            name = "季度报告.pdf",
            isFolder = false,
            parentId = ROOT_ID,
            size = 2_400_000,
            mimeType = "application/pdf",
            extension = "pdf",
            previewable = true,
        ),
        MockNode(
            id = "mock:f-archive-zip",
            name = "backup.zip",
            isFolder = false,
            parentId = ROOT_ID,
            size = 88_000_000,
            mimeType = "application/zip",
            extension = "zip",
            previewable = false,
        ),
        MockNode(
            id = "mock:f-opaque",
            name = "data.bin",
            isFolder = false,
            parentId = ROOT_ID,
            size = 4_096,
            mimeType = "application/octet-stream",
            extension = "bin",
            previewable = false,
        ),
        MockNode(
            id = "mock:f-office",
            name = "方案.docx",
            isFolder = false,
            parentId = DOCUMENTS_ID,
            size = 512_000,
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            extension = "docx",
            previewable = true,
            officeViewer = true,
        ),

        // -------- documents --------
        MockNode(
            id = "mock:f-notes",
            name = "notes.md",
            isFolder = false,
            parentId = DOCUMENTS_ID,
            size = 2_048,
            mimeType = "text/markdown",
            extension = "md",
            previewable = true,
        ),
        MockNode(DEEP_ID, "深层目录", true, DOCUMENTS_ID),

        // -------- media --------
        MockNode(
            id = "mock:f-video",
            name = "sample-1080p.mp4",
            isFolder = false,
            parentId = MEDIA_ID,
            size = 1_036_870_843,
            mimeType = "video/mp4",
            extension = "mp4",
            isVideo = true,
            previewable = true,
            durationMs = 2_525_000,
            width = 1920,
            height = 804,
        ),
        MockNode(
            id = "mock:f-video-mkv",
            name = "movie.mkv",
            isFolder = false,
            parentId = MEDIA_ID,
            size = 4_200_000_000,
            mimeType = "video/x-matroska",
            extension = "mkv",
            isVideo = true,
            previewable = true,
            durationMs = 7_200_000,
        ),
        MockNode(
            id = "mock:f-audio",
            name = "podcast.mp3",
            isFolder = false,
            parentId = MEDIA_ID,
            size = 45_000_000,
            mimeType = "audio/mpeg",
            extension = "mp3",
            isAudio = true,
            previewable = true,
            durationMs = 3_600_000,
        ),

        // -------- photos --------
        MockNode(
            id = "mock:f-image",
            name = "shot.jpg",
            isFolder = false,
            parentId = PHOTOS_ID,
            size = 3_500_000,
            mimeType = "image/jpeg",
            extension = "jpg",
            isImage = true,
            previewable = true,
        ),
        MockNode(
            id = "mock:f-image-png",
            name = "diagram.png",
            isFolder = false,
            parentId = PHOTOS_ID,
            size = 120_000,
            mimeType = "image/png",
            extension = "png",
            isImage = true,
            previewable = true,
        ),
    )

    /** Children of `大目录`, generated so the pagination probe has something to walk. */
    val largeFolderChildren: List<MockNode> = (1..37).map { index ->
        MockNode(
            id = "mock:f-large-$index",
            name = "file-${index.toString().padStart(3, '0')}.txt",
            isFolder = false,
            parentId = LARGE_ID,
            size = index * 1_000L,
            mimeType = "text/plain",
            extension = "txt",
            previewable = true,
        )
    }

    /** Children of `压缩包`, all non-previewable, to exercise the "no preview" path. */
    val archiveChildren: List<MockNode> = listOf(
        MockNode("mock:f-zip", "a.zip", false, ARCHIVE_ID, 1_000, "application/zip", "zip", previewable = false),
        MockNode("mock:f-rar", "b.rar", false, ARCHIVE_ID, 2_000, "application/vnd.rar", "rar", previewable = false),
    )

    val all: List<MockNode> = nodes + largeFolderChildren + archiveChildren

    private val byId: Map<String, MockNode> = all.associateBy { it.id }

    fun find(id: String): MockNode? = byId[id]

    fun childrenOf(folderId: String): List<MockNode> =
        all.filter { it.parentId == folderId }
            // Stable, vendor-like ordering: folders first, then by name.
            .sortedWith(compareByDescending<MockNode> { it.isFolder }.thenBy { it.name })

    /** Path from the root down to [id], inclusive. */
    fun pathTo(id: String): List<MockNode> {
        val chain = ArrayDeque<MockNode>()
        var current = byId[id] ?: return emptyList()
        var guard = 0
        while (guard++ < 64) {
            chain.addFirst(current)
            val parentId = current.parentId ?: break
            current = byId[parentId] ?: break
        }
        return chain.toList()
    }
}

/**
 * One node of the mock tree.
 *
 * A flat data class rather than a nested structure: it keeps the tree literal above readable and
 * makes "which folder does this belong to" a single field rather than a traversal.
 */
internal data class MockNode(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val parentId: String?,
    val size: Long? = null,
    val mimeType: String? = null,
    val extension: String? = null,
    val isImage: Boolean = false,
    val isVideo: Boolean = false,
    val isAudio: Boolean = false,
    val previewable: Boolean = false,
    val officeViewer: Boolean = false,
    val readable: Boolean = true,
    val starred: Boolean = false,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val contentHash: String = id.removePrefix("mock:").hashCode().toUInt().toString(16).padStart(8, '0').repeat(5),
) {
    /** Deterministic fake timestamps derived from the id, so tests can assert relative order. */
    val createdTime: Long get() = BASE_TIME + (id.hashCode().toLong() and 0xFFFF) * 1_000L
    val modifiedTime: Long get() = BASE_TIME + (id.hashCode().toLong() and 0x7FFF) * 2_000L

    companion object {
        /** 2024-01-01T00:00:00Z — a fixed epoch so generated timestamps never drift between runs. */
        const val BASE_TIME = 1_704_067_200_000L
    }
}

/**
 * An in-memory account store for the Mock provider.
 *
 * Mirrors the real provider's shape (credentials live behind an opaque handle, the profile is
 * separate from the credential) without needing Android's Keystore. It deliberately supports
 * **multiple accounts**, so the multi-account UI and repository code are exercised offline.
 */
internal class MockAccountStore {

    data class Record(
        val accountId: AccountId,
        val userId: String,
        val userName: String,
        var authState: AuthState,
        var membershipLabel: String?,
        val addedAt: Long,
        var lastVerifiedAt: Long?,
        /** Stands in for the credential: a Mock session never leaves the process. */
        var sessionToken: String,
    )

    private val records = LinkedHashMap<AccountId, Record>()

    fun insert(record: Record) {
        records[record.accountId] = record
    }

    fun get(accountId: AccountId): Record? = records[accountId]

    fun all(): List<Record> = records.values.toList()

    fun update(accountId: AccountId, transform: (Record) -> Unit): Record? =
        records[accountId]?.also(transform)

    fun remove(accountId: AccountId): Boolean = records.remove(accountId) != null

    /**
     * Clears every session. Used by the "Token 失效" contract probe to prove the provider reports
     * `TokenExpired` instead of inventing a working session.
     */
    fun invalidateAll() {
        records.values.forEach { it.authState = AuthState.EXPIRED }
    }
}

/** A Mock session: carries only the account scope, no real credential. */
internal data class MockAuthSession(
    override val providerId: ProviderId,
    override val accountId: AccountId,
    override val expiresAt: Long?,
) : AuthSession

internal fun unsupported(capability: String, message: String): CloudResult<Nothing> =
    CloudResult.Failure(CloudError.Unsupported(message = message, capability = capability))

internal fun notFound(id: String): CloudResult<Nothing> =
    CloudResult.Failure(CloudError.NotFound(message = "文件不存在: $id"))

internal fun categoryOf(extension: String?): FileCategory? = when (extension?.lowercase()) {
    "txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx" -> FileCategory.DOCUMENT
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileCategory.IMAGE
    "mp3", "flac", "wav", "aac", "m4a", "ogg" -> FileCategory.AUDIO
    "mp4", "mkv", "avi", "mov", "webm", "ts" -> FileCategory.VIDEO
    "zip", "rar", "7z", "tar", "gz" -> FileCategory.ARCHIVE
    "apk", "exe", "dmg", "iso" -> FileCategory.SOFTWARE
    "epub", "mobi", "azw3" -> FileCategory.BOOK
    else -> null
}

/** Auth flows the Mock provider advertises. Both are fully offline and instantly succeed. */
internal val MOCK_AUTH_FLOWS: Set<AuthFlowKind> = setOf(
    AuthFlowKind.QR_CODE,
    AuthFlowKind.MANUAL_CREDENTIAL,
)
