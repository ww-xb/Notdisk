package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.MediaRendition
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.MediaSubtitleTrack
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.provider.MediaProvider
import kotlinx.coroutines.delay

/**
 * Offline [MediaProvider].
 *
 * Reproduces the parts of the real media contract that the player and the coordinator depend on,
 * and that are easy to get wrong:
 *  - **short-lived URLs.** Every resolved resource carries an `expiresAt` in the near future, so
 *    the coordinator's refresh path is genuinely exercised instead of being dead code.
 *  - **HLS with separated audio.** The Mock video resource is an [MediaProtocol.HLS] resource whose
 *    `audioTracks` are separate playlists, mirroring 115's transcoder output. A player that only
 *    handles muxed HLS fails here, which is exactly the regression this fixture should catch.
 *  - **Renditions.** Two quality levels with stable ids, so quality switching can be tested.
 *  - **Honest gaps.** Audio-track resolution is supported; progress reporting is *not* (matching
 *    115, whose progress endpoint is undocumented), so it returns `Unsupported`.
 */
internal class MockMediaProvider(
    private val accounts: MockAccountStore,
    private val latencyMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
) : MediaProvider {

    override val providerId: ProviderId = MOCK_PROVIDER_ID

    override suspend fun resolveMedia(
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String?,
    ): CloudResult<MediaResource> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()

        val node = MockTree.find(fileId.value) ?: return notFound(fileId.value)
        if (node.isFolder) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "「${node.name}」是文件夹，无法播放"),
            )
        }
        if (!node.isVideo && !node.isAudio) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "「${node.name}」不是媒体文件，无法播放",
                    capability = "media.streaming",
                ),
            )
        }

        val expiresAt = clock() + URL_TTL_MS
        val isVideo = node.isVideo

        return CloudResult.Success(
            MediaResource(
                // The issue stamp is not decoration: a refreshed resource must be *observably*
                // different, otherwise the coordinator's refresh path cannot be asserted on.
                url = if (isVideo) {
                    "mock://hls/${node.id}/video-1080p/video.m3u8?t=$expiresAt"
                } else {
                    "mock://audio/${node.id}/audio.m3u8?t=$expiresAt"
                },
                protocol = MediaProtocol.HLS,
                mimeType = if (isVideo) "application/vnd.apple.mpegurl" else node.mimeType,
                // Signed resources in the wild require attribution headers; the Mock keeps the
                // shape so header plumbing is exercised end-to-end.
                headers = mapOf(
                    "Referer" to "https://mock.notdisk.invalid/",
                    "User-Agent" to "NotDisk-Mock/1.0",
                ),
                expiresAt = expiresAt,
                refreshable = true,
                supportsRangeRequests = true,
                durationMs = node.durationMs,
                width = node.width,
                height = node.height,
                subtitles = if (isVideo) {
                    listOf(
                        MediaSubtitleTrack(
                            url = "mock://subtitle/${node.id}/0.srt",
                            label = "中文",
                            language = "zh",
                            mimeType = "application/x-subrip",
                        ),
                    )
                } else {
                    emptyList()
                },
                renditions = if (isVideo) {
                    listOf(
                        MediaRendition(id = "1080p", label = "1080P", bitrate = 3_000_000, width = 1920, height = 1080),
                        MediaRendition(id = "720p", label = "720P", bitrate = 1_500_000, width = 1280, height = 720),
                    )
                } else {
                    emptyList()
                },
                audioTracks = if (isVideo) {
                    listOf(
                        MediaAudioTrack(id = "0", label = "立体声：中文", language = "zh"),
                        MediaAudioTrack(id = "1", label = "5.1 环绕声：中文", language = "zh"),
                    )
                } else {
                    emptyList()
                },
            ),
        )
    }

    override fun refreshTokenFor(
        accountId: AccountId,
        fileId: FileId,
        resource: MediaResource,
        qualityHint: String?,
    ): MediaRefreshToken = MediaRefreshToken(
        key = CloudFileKey(MOCK_PROVIDER_ID, accountId, fileId),
        qualityHint = qualityHint ?: resource.renditions.firstOrNull()?.id,
    )

    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> {
        accounts.requireAuthorized(accountId)?.let { return it }
        await()
        val node = MockTree.find(fileId.value) ?: return notFound(fileId.value)
        return CloudResult.Success(
            MediaResource(
                url = "mock://hls/${node.id}/audio/${track.id}.m3u8?t=${clock() + URL_TTL_MS}",
                protocol = MediaProtocol.HLS,
                mimeType = "application/vnd.apple.mpegurl",
                headers = mapOf("Referer" to "https://mock.notdisk.invalid/"),
                expiresAt = clock() + URL_TTL_MS,
                refreshable = true,
                supportsRangeRequests = true,
            ),
        )
    }

    override suspend fun reportPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> {
        accounts.requireAuthorized(accountId)?.let { return it }
        // Deliberately unsupported: the Mock mirrors 115, where no progress-report endpoint is
        // documented. Returning success here would hide a real gap from the UI.
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "Mock Provider 未实现播放进度上报",
                capability = "media.playbackProgress",
            ),
        )
    }

    override suspend fun getPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<Long?> {
        accounts.requireAuthorized(accountId)?.let { return it }
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "Mock Provider 未实现播放进度查询",
                capability = "media.playbackProgress",
            ),
        )
    }

    private suspend fun await() {
        if (latencyMs > 0) delay(latencyMs)
    }

    private companion object {
        /**
         * Intentionally short (2 minutes): long enough for a playback session to start, short
         * enough that a test can advance a virtual clock past it and assert the refresh path.
         */
        const val URL_TTL_MS = 2 * 60 * 1000L
    }
}
