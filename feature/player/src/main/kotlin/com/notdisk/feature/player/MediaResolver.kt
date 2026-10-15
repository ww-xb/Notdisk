package com.notdisk.feature.player

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.data.media.MediaPlaybackCoordinator
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId

/**
 * The player's **entire** view of "where does playable media come from".
 *
 * ### Why this interface exists (and why it is not a wrapper for its own sake)
 * `MediaPlaybackCoordinator` is a *final* class with a constructor that requires a
 * `ProviderRegistry` and a `CloudLogger`. Two consequences follow, and both are reasons this
 * interface earns its place rather than being ceremony:
 *
 *  1. **Testability of the refresh policy.** The refresh cap, the position-preservation rule and the
 *     "Unsupported progress must not surface an error" rule are all decided in `PlayerViewModel`.
 *     Those are exactly the behaviours a regression would silently break, so they need direct unit
 *     tests. Without a seam the only way to construct the ViewModel in a test would be to build a
 *     real registry, real providers and a real cache — a test that verifies its own arrangement.
 *     With this interface, [FakeMediaResolver] answers in one line and records what it was asked,
 *     which is what makes "exactly one refresh happened" assertable.
 *  2. **A stated, narrow dependency.** The ViewModel can call five things. Widening the player's
 *     reach onto the coordinator (account invalidation, cache control, token minting) would now be
 *     a visible edit to this file rather than an accident.
 *
 * ### What it must never grow
 * No `ProviderId`-shaped decision, no vendor type, no vendor-named method. Every parameter and
 * return type here is a shared model type. The moment a method takes something that only one cloud
 * can produce, the player has acquired provider knowledge and §16.1 of the brief is violated — see
 * the module invariant documented at the top of `PlayerScreen.kt`.
 *
 * The production implementation is [CoordinatorMediaResolver]; the binding is in [MediaResolverModule].
 */
interface MediaResolver {

    /**
     * Resolves a playable resource for one file.
     *
     * @param qualityHint a rendition id from a previous result, or `null` for the provider's own
     *   default. The player never interprets this string; it only echoes one back.
     * @param forceRefresh bypass the resolver's cache after playback failed on a stale URL.
     */
    suspend fun resolve(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        qualityHint: String? = null,
        forceRefresh: Boolean = false,
    ): CloudResult<MediaResource>

    /**
     * Re-resolves [resource] because the player found it unusable.
     *
     * The failure carries the distinction the UI depends on: a `ResourceUrlExpired` with
     * `refreshable = false` means no retry can ever work and the user must be offered a download.
     */
    suspend fun refresh(resource: MediaResource): CloudResult<MediaResource>

    /** Resolves an alternate audio track into its own playable resource. */
    suspend fun resolveAudioTrack(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource>

    /**
     * Reports the playback position to the vendor so "continue watching" can work.
     *
     * Deliberately best-effort: a provider that has no progress endpoint answers
     * `CloudError.Unsupported`, which is a *normal* outcome and must never be shown as a failure.
     */
    suspend fun reportProgress(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit>

    /** The vendor's last known position for this file, when it exposes one. */
    suspend fun lastKnownPosition(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ): CloudResult<Long?>
}

/**
 * Adapts the real [MediaPlaybackCoordinator] to [MediaResolver].
 *
 * A pure delegation with no added behaviour, on purpose: every policy decision (caching, per-file
 * refresh serialisation, capability gating) stays inside the coordinator, so there is exactly one
 * implementation of "how a URL is refreshed" in the app. This class adds no branch, no cache and no
 * provider reference.
 */
class CoordinatorMediaResolver(
    private val coordinator: MediaPlaybackCoordinator,
) : MediaResolver {

    override suspend fun resolve(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        qualityHint: String?,
        forceRefresh: Boolean,
    ): CloudResult<MediaResource> = coordinator.resolve(
        accountId = accountId,
        providerId = providerId,
        fileId = fileId,
        qualityHint = qualityHint,
        forceRefresh = forceRefresh,
    )

    override suspend fun refresh(resource: MediaResource): CloudResult<MediaResource> =
        coordinator.refresh(resource)

    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> =
        coordinator.resolveAudioTrack(accountId, providerId, fileId, track)

    override suspend fun reportProgress(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> =
        coordinator.reportProgress(accountId, providerId, fileId, positionMs, durationMs)

    override suspend fun lastKnownPosition(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ): CloudResult<Long?> = coordinator.lastKnownPosition(accountId, providerId, fileId)
}
