package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.OfficeViewer
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResource
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.PreviewHint
import com.notdisk.core.provider.PreviewProvider
import com.notdisk.core.provider.PreviewResourceOrNull
import kotlinx.coroutines.delay

/**
 * Offline [PreviewProvider].
 *
 * Encodes the classification rules the real provider must also follow, so the UI's preview
 * routing can be developed and tested offline:
 *
 * | Mock input                | Classification             |
 * |---------------------------|----------------------------|
 * | `shot.jpg`, `diagram.png` | [PreviewKind.Image]        |
 * | `sample-1080p.mp4`, `movie.mkv` | [PreviewKind.Video]  |
 * | `podcast.mp3`             | [PreviewKind.Audio]        |
 * | `readme.txt`, `notes.md`  | [PreviewKind.Text]         |
 * | `季度报告.pdf`             | [PreviewKind.Pdf]          |
 * | `方案.docx`                | [PreviewKind.OfficeDocument] |
 * | `backup.zip`, `data.bin`  | [PreviewKind.Unsupported]  |
 */
internal class MockPreviewProvider(
    private val accounts: MockAccountStore,
    private val latencyMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) : PreviewProvider {

    override suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean,
        hint: PreviewHint,
    ): CloudResult<PreviewResult> {
        accounts.requireAuthorized(accountId)?.let { return it }
        val node = MockTree.find(fileId.value) ?: return notFound(fileId.value)
        // Classification is local; only the access resolution is allowed to "cost" anything.
        if (resolveAccess) await()

        val key = CloudFileKey(MOCK_PROVIDER_ID, accountId, fileId)
        val expiresAt = clock() + ACCESS_TTL_MS

        if (node.isFolder) {
            return CloudResult.Success(
                PreviewResult(
                    key = key,
                    kind = PreviewKind.Unsupported("文件夹不支持预览"),
                    mimeType = null,
                ),
            )
        }

        val kind: PreviewKind = when {
            node.isVideo -> PreviewKind.Video
            node.isAudio -> PreviewKind.Audio
            node.isImage -> PreviewKind.Image(
                thumbnail = ThumbnailRef(sizeHint = "480", contentHash = node.contentHash),
                originalAvailable = true,
            )

            node.officeViewer -> PreviewKind.OfficeDocument(
                viewer = OfficeViewer(
                    url = "mock://wps/view/${node.id}",
                    token = "mock-token-${node.id}",
                    expiresAt = expiresAt,
                    refreshBeforeSeconds = 3_540,
                    canEdit = true,
                ),
            )

            node.extension == "pdf" -> PreviewKind.Pdf
            node.extension in TEXT_EXTENSIONS -> PreviewKind.Text(encoding = "UTF-8")
            !node.previewable -> PreviewKind.Unsupported(
                reason = "「${node.name}」暂不支持在线预览，可下载后使用本地应用打开",
            )

            else -> PreviewKind.ThumbnailOnly(thumbnail = null)
        }

        val resource = if (resolveAccess) {
            when (kind) {
                is PreviewKind.Image -> PreviewResource(url = "mock://image/${node.id}?size=0")
                is PreviewKind.Text -> PreviewResource(url = "mock://text/${node.id}")
                is PreviewKind.Pdf -> PreviewResource(url = "mock://pdf/${node.id}")
                else -> null
            }
        } else {
            null
        }

        return CloudResult.Success(
            PreviewResult(
                key = key,
                kind = kind,
                mimeType = node.mimeType,
                resource = resource,
                expiresAt = if (resource != null) expiresAt else null,
                refreshable = resource != null,
            ),
        )
    }

    override suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: ThumbnailRef?,
    ): CloudResult<PreviewResourceOrNull> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val node = MockTree.find(fileId.value) ?: return notFound(fileId.value)
        val key = CloudFileKey(MOCK_PROVIDER_ID, accountId, fileId)

        // "This item has no thumbnail" is a *successful* answer with a null resource, which is
        // precisely the distinction the contract test pins down.
        if (!node.isImage) {
            return CloudResult.Success(PreviewResourceOrNull(key = key, resource = null))
        }

        val size = ref?.sizeHint ?: "480"
        return CloudResult.Success(
            PreviewResourceOrNull(
                key = key,
                resource = PreviewResource(
                    url = "mock://thumb/${node.contentHash}_$size",
                    headers = emptyMap(),
                ),
            ),
        )
    }

    private suspend fun await() {
        if (latencyMs > 0) delay(latencyMs)
    }

    private companion object {
        val TEXT_EXTENSIONS = setOf("txt", "md", "log", "json", "xml", "csv", "kt", "java", "js", "ts")
        const val ACCESS_TTL_MS = 2 * 60 * 1000L
    }
}
