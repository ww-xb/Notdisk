package com.notdisk.core.provider

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId

/**
 * Resolves playable media locations.
 *
 * ### What the player is allowed to know
 * The player (Media3) receives a [MediaResource] and nothing else. It must never see a
 * [MediaProvider], a [ProviderId], or a vendor URL builder. The coordination lives in
 * `core:data` (`MediaPlaybackCoordinator`), which is the only place that knows how to turn
 * "the user tapped this file" into a [MediaResource] and how to refresh it.
 */
interface MediaProvider {

    /** The provider this media surface belongs to; used to build fully-qualified refresh keys. */
    val providerId: ProviderId

    /**
     * Resolves a playable resource for a media file.
     *
     * Fails with:
     *  - `CloudError.Unsupported` when the provider cannot stream at all, or cannot stream this
     *    particular item (e.g. still transcoding);
     *  - `CloudError.NotFound` when the file no longer exists;
     *  - `CloudError.TokenExpired` when the account must re-authorise.
     *
     * @param qualityHint a [com.notdisk.core.model.MediaRendition.id] previously returned by
     *   this provider. Providers that expose no renditions must ignore it.
     */
    suspend fun resolveMedia(
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String? = null,
    ): CloudResult<MediaResource>

    /**
     * Builds the token the coordinator stores so it can re-resolve an expired [resource].
     *
     * Carries only stable identifiers plus a non-secret quality hint, so it is safe to keep for
     * the whole playback session.
     */
    fun refreshTokenFor(
        accountId: AccountId,
        fileId: FileId,
        resource: MediaResource,
        qualityHint: String? = null,
    ): MediaRefreshToken = MediaRefreshToken(
        key = CloudFileKey(providerId = providerId, accountId = accountId, fileId = fileId),
        qualityHint = qualityHint,
    )

    /**
     * Resolves an alternate audio track into its own playable resource.
     *
     * 115 delivers separated audio playlists (`audio/{N}.m3u8`); players that cannot consume two
     * concurrent `#EXT-X-MEDIA` groups need this. Providers without separated audio return
     * `CloudError.Unsupported`.
     */
    suspend fun resolveAudioTrack(
        accountId: AccountId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource>

    /**
     * Reports playback position so the vendor can offer "continue watching".
     *
     * 115's progress-report endpoint is **not** documented in the source material (only
     * `GET /history/list` exposes `current_time`), so this returns `CloudError.Unsupported` for
     * 115 and the capability is declared `UNVERIFIED`. Never fake a successful report.
     */
    suspend fun reportPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit>

    /** Last known playback position from the vendor, when it exposes one. */
    suspend fun getPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<Long?>
}
