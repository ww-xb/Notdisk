package com.notdisk.feature.player

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.ProviderId

/**
 * A hand-written, fully recording [MediaResolver] double.
 *
 * ### Why not `mockk(relaxed = true)`
 * The behaviours under test are *policy*, and policy is only observable through what the ViewModel
 * asked for and when:
 *
 *  - "a refreshable failure triggers **exactly one** refresh" is an assertion about a *count*;
 *  - "the position is preserved across the refresh" is an assertion about the *arguments* of the
 *    second call relative to the player's reported position;
 *  - "the cap is enforced" is an assertion that the fourth attempt is *absent*.
 *
 * A relaxed mock answers `null`/a mock for anything unstubbed and records nothing by default, so a
 * ViewModel that called `refresh` in a loop, or that quietly called `reportProgress` where it should
 * not have, would still pass. This fake makes calls first-class data, and every relevant method has a
 * deterministic default so a test only configures the one behaviour it is about.
 *
 * ### Deliberate absence of `REFRESH_SKEW_MILLIS` / caching
 * This fake models no caching and no expiry skew. Those live in `MediaPlaybackCoordinator` and are
 * tested there; duplicating them here would test the fake instead of the ViewModel.
 */
class FakeMediaResolver : MediaResolver {

    /** What [resolve] returns. Replaceable mid-test to drive the refresh and switch paths. */
    var resolveResult: CloudResult<MediaResource> = CloudResult.Success(defaultResource())

    /** What [refresh] returns. */
    var refreshResult: CloudResult<MediaResource> = CloudResult.Success(defaultResource())

    /** What [resolveAudioTrack] returns. */
    var audioTrackResult: CloudResult<MediaResource> =
        CloudResult.Failure(
            com.notdisk.core.common.error.CloudError.Unsupported(
                message = "该网盘不支持独立音轨",
                capability = "media.audioTrack",
            ),
        )

    /** What [reportProgress] returns. Defaults to the documented `Unsupported` answer. */
    var progressResult: CloudResult<Unit> = CloudResult.Failure(
        com.notdisk.core.common.error.CloudError.Unsupported(
            message = "该网盘不支持记录播放进度",
            capability = "media.progress",
        ),
    )

    /** What [lastKnownPosition] returns. */
    var lastPositionResult: CloudResult<Long?> = CloudResult.Success(null)

    // ---- recorded calls ----

    /** Every `resolve` argument tuple, in order. */
    val resolveCalls = mutableListOf<ResolveCall>()

    /** Every `refresh` argument, in order. Its `size` is the refresh count under test. */
    val refreshCalls = mutableListOf<MediaResource>()

    /** Every `resolveAudioTrack` argument, in order. */
    val audioTrackCalls = mutableListOf<MediaAudioTrack>()

    /** Every `reportProgress` argument, in order. */
    val progressCalls = mutableListOf<ProgressCall>()

    override suspend fun resolve(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        qualityHint: String?,
        forceRefresh: Boolean,
    ): CloudResult<MediaResource> {
        resolveCalls += ResolveCall(accountId, providerId, fileId, qualityHint, forceRefresh)
        return resolveResult
    }

    override suspend fun refresh(resource: MediaResource): CloudResult<MediaResource> {
        refreshCalls += resource
        return refreshResult
    }

    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> {
        audioTrackCalls += track
        return audioTrackResult
    }

    override suspend fun reportProgress(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> {
        progressCalls += ProgressCall(accountId, providerId, fileId, positionMs, durationMs)
        return progressResult
    }

    override suspend fun lastKnownPosition(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ): CloudResult<Long?> = lastPositionResult

    data class ResolveCall(
        val accountId: AccountId,
        val providerId: ProviderId,
        val fileId: FileId,
        val qualityHint: String?,
        val forceRefresh: Boolean,
    )

    data class ProgressCall(
        val accountId: AccountId,
        val providerId: ProviderId,
        val fileId: FileId,
        val positionMs: Long,
        val durationMs: Long?,
    )

    companion object {
        /**
         * A playable resource with a refresh token, i.e. one the app is allowed to renew.
         *
         * [expiresAt] is far in the future and never compared by the ViewModel — it is the
         * coordinator that reads expiry — so a fixed large value keeps these tests independent of the
         * wall clock.
         */
        fun defaultResource(
            url: String = "https://example.invalid/stream.m3u8",
            refreshable: Boolean = true,
        ): MediaResource = MediaResource(
            url = url,
            protocol = MediaProtocol.HLS,
            mimeType = "application/vnd.apple.mpegurl",
            expiresAt = Long.MAX_VALUE,
            refreshable = refreshable,
            durationMs = 3_600_000L,
            refreshToken = if (refreshable) {
                MediaRefreshToken(
                    key = CloudFileKey(
                        providerId = ProviderId("test-provider"),
                        accountId = AccountId("test-account"),
                        fileId = FileId("test-file"),
                    ),
                )
            } else {
                null
            },
        )
    }
}
