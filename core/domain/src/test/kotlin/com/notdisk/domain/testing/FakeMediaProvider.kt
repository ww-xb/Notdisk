package com.notdisk.domain.testing

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.MediaProvider

/**
 * Configurable [MediaProvider] fake.
 *
 * ### Why every resource-producing method takes a fresh URL by default
 * [resolveMedia] and [resolveAudioTrack] mint a **new** URL on every call (a monotonically
 * increasing counter). That is deliberate, and it is what makes the refresh tests meaningful: a
 * test asserting that [com.notdisk.domain.media.RefreshMediaResourceUseCase] produced a *new*
 * resource would pass trivially against a fake that returned a cached constant, and would then fail
 * to catch a production bug where refresh returned the caller's original resource unchanged.
 *
 * ### Why [resolveMediaFailure] is separate from the refresh capability
 * The two are independent facts and the domain layer must treat them so: a provider can declare
 * `urlRefresh = SUPPORTED` and still fail a particular resolve (the item was deleted since), and it
 * can declare `urlRefresh = UNSUPPORTED` while `resolveMedia` works fine. A fake with one "media
 * broken" switch could not produce the second combination, which is exactly the one
 * [com.notdisk.domain.media.RefreshMediaResourceUseCase] must classify as non-refreshable
 * expiry.
 */
class FakeMediaProvider(
    private val owner: FakeCloudProvider,
    /** Mirrors `CloudProvider.id` by default; overridable to test the identity mismatch guard. */
    override val providerId: ProviderId = owner.id,
) : MediaProvider {

    /** Bumped per resolved resource so a refresh is observably a *new* URL. */
    private var urlCounter: Int = 0

    var resolveMediaFailure: CloudError? = null
    var resolveAudioTrackFailure: CloudError? = null
    var reportProgressFailure: CloudError? = null
    var getProgressFailure: CloudError? = null

    /** What [getPlaybackProgress] reports. `null` = the vendor has no recorded position. */
    var playbackProgressMs: Long? = null

    /**
     * Whether the resources this fake mints claim to be refreshable.
     *
     * Defaults to `true` so a test that refreshes does not have to configure it; the refresh uses
     * [MediaProvider.refreshTokenFor], which the fake implements faithfully.
     */
    var resourceRefreshable: Boolean = true

    var expiresAt: Long? = null

    override suspend fun resolveMedia(
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String?,
    ): CloudResult<MediaResource> {
        owner.record(
            ProviderSurface.MEDIA,
            "resolveMedia",
            "accountId" to accountId,
            "fileId" to fileId,
            "qualityHint" to qualityHint,
        )
        resolveMediaFailure?.let { return CloudResult.Failure(it) }

        urlCounter += 1
        val resource = MediaResource(
            url = "https://cdn.invalid/stream/$fileId?v=$urlCounter",
            protocol = MediaProtocol.HLS,
            mimeType = "video/mp4",
            headers = mapOf("Referer" to "https://vendor.invalid/"),
            expiresAt = expiresAt,
            refreshable = resourceRefreshable,
            supportsRangeRequests = true,
            // The token is only attached when the resource claims to be refreshable, mirroring the
            // provider contract: a refreshable resource with no token is unusable.
            refreshToken = if (resourceRefreshable) {
                refreshTokenFor(accountId, fileId, PLACEHOLDER_RESOURCE, qualityHint)
            } else {
                null
            },
        )
        return CloudResult.Success(resource)
    }

    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> {
        owner.record(
            ProviderSurface.MEDIA,
            "resolveAudioTrack",
            "accountId" to accountId,
            "fileId" to fileId,
            "trackId" to track.id,
        )
        resolveAudioTrackFailure?.let { return CloudResult.Failure(it) }

        urlCounter += 1
        return CloudResult.Success(
            MediaResource(
                url = "https://cdn.invalid/audio/$fileId/${track.id}?v=$urlCounter",
                protocol = MediaProtocol.HLS,
                mimeType = "audio/mp4",
            ),
        )
    }

    override suspend fun reportPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> {
        owner.record(
            ProviderSurface.MEDIA,
            "reportPlaybackProgress",
            "accountId" to accountId,
            "fileId" to fileId,
            "positionMs" to positionMs,
            "durationMs" to durationMs,
        )
        reportProgressFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(Unit)
    }

    override suspend fun getPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<Long?> {
        owner.record(
            ProviderSurface.MEDIA,
            "getPlaybackProgress",
            "accountId" to accountId,
            "fileId" to fileId,
        )
        getProgressFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(playbackProgressMs)
    }

    /** The last URL this fake minted, for assertions that a refresh produced a new one. */
    fun lastMintedUrl(): String? = "https://cdn.invalid/stream/?v=$urlCounter".takeIf { urlCounter > 0 }

    private companion object {
        /**
         * A throwaway resource passed to [refreshTokenFor].
         *
         * The default implementation of that method only uses `providerId` and the caller's
         * account/file ids, so the resource body is irrelevant — but it must still be a valid
         * [MediaResource], which requires a URL and a protocol.
         */
        val PLACEHOLDER_RESOURCE = MediaResource(
            url = "https://cdn.invalid/placeholder",
            protocol = MediaProtocol.PROGRESSIVE_HTTP,
        )
    }
}

/** Helper for tests that need a fully-qualified key without spelling out the constructor. */
internal fun keyFor(providerId: ProviderId, accountId: AccountId, fileId: String) =
    CloudFileKey(providerId, accountId, FileId(fileId))

/** Helper for tests that need a refresh token whose key deliberately disagrees with the target. */
internal fun tokenFor(
    providerId: ProviderId,
    accountId: AccountId,
    fileId: String,
    qualityHint: String? = null,
) = MediaRefreshToken(
    key = CloudFileKey(providerId, accountId, FileId(fileId)),
    qualityHint = qualityHint,
)
