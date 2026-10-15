package com.notdisk.core.data.media

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.provider.MediaProvider
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaResource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The single place that turns "the user tapped a media file" into a playable [MediaResource], and
 * the single place that knows how to refresh an expired one.
 *
 * ### Why this class exists (and why the player must not do this itself)
 * The brief's §16.1 requires the player to be completely decoupled from providers. That is only
 * achievable if *something* owns the provider-facing side of playback, and that something must not
 * be the player. This coordinator is that owner:
 *
 * ```
 * Player/ViewModel ──► MediaPlaybackCoordinator ──► ProviderRegistry ──► MediaProvider ──► MediaResource
 *        ▲                                                                                      │
 *        └──────────────────────── only ever sees MediaResource ────────────────────────────────┘
 * ```
 *
 * The player therefore has no import of, and no reference to, `ProviderRegistry` or any vendor. It
 * cannot construct a vendor URL and cannot branch on a provider id — there is nothing in scope to
 * branch on. That property is what makes adding a second cloud a provider-only change.
 *
 * ### Refresh handling
 * A resolved URL is a short-lived signed resource. The coordinator:
 *  1. proactively refreshes when the resource is within [REFRESH_SKEW_MILLIS] of expiry;
 *  2. re-resolves on demand when the player reports a failure that looks like expiry;
 *  3. distinguishes "expired but refreshable" from "expired and stuck" so the UI can offer download
 *     instead of a retry button that can never work.
 *
 * ### Concurrency
 * Refresh is serialised per file. A player that both proactively refreshes and reacts to an error
 * would otherwise fire two resolutions for the same file and race to install the result.
 */
class MediaPlaybackCoordinator(
    private val registry: ProviderRegistry,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The most recent resolution per file, so a redundant resolve is served from memory. */
    private val resolved = mutableMapOf<CloudFileKey, MediaResource>()

    /** One lock per file: refreshes for the same file queue, different files stay concurrent. */
    private val locks = mutableMapOf<CloudFileKey, Mutex>()

    private val mapLock = Mutex()

    /**
     * Resolves a playable resource for [fileId].
     *
     * @param forceRefresh skip the cache and ask the provider again. Used when the player reports a
     *   failure that suggests the URL died earlier than its stated expiry.
     */
    suspend fun resolve(
        accountId: AccountId,
        providerId: com.notdisk.core.model.ProviderId,
        fileId: FileId,
        qualityHint: String? = null,
        forceRefresh: Boolean = false,
    ): CloudResult<MediaResource> {
        val key = CloudFileKey(providerId, accountId, fileId)

        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        if (!forceRefresh) {
            cachedUsable(key, qualityHint)?.let { return CloudResult.Success(it) }
        }

        return lockFor(key).withLock {
            // Re-check inside the lock: a concurrent caller may have just resolved it, and issuing a
            // second request would waste a rate-limited call.
            if (!forceRefresh) {
                cachedUsable(key, qualityHint)?.let { return@withLock CloudResult.Success(it) }
            }

            when (val result = provider.media.resolveMedia(accountId, fileId, qualityHint)) {
                is CloudResult.Failure -> {
                    // A failure invalidates any cached resource for this file: keeping a URL that the
                    // provider just refused to re-issue would make the UI retry a dead link.
                    forget(key)
                    CloudResult.Failure(result.error)
                }

                is CloudResult.Success -> {
                    install(key, result.data)
                    logger.i(
                        TAG,
                        "resolved media for ${LogRedaction.redactId(fileId.value)} " +
                            "protocol=${result.data.protocol} " +
                            "expiresIn=${result.data.expiresAt?.minus(clock())?.div(1000)}s",
                    )
                    CloudResult.Success(result.data)
                }
            }
        }
    }

    /**
     * Re-resolves [resource] because the player found it unusable.
     *
     * This is the method the player calls when playback fails. It reports a precise outcome so the
     * UI can choose between "retry silently" and "offer a download":
     *
     * | Outcome | Meaning | UI |
     * |---|---|---|
     * | `Success` | got a fresh URL | resume playback at the previous position |
     * | `ResourceUrlExpired(refreshable = false)` | the link died and cannot be renewed | offer download / external open |
     * | `TokenExpired` | the *account* needs re-authorisation | route to the accounts screen |
     * | other | a real failure | show the error |
     */
    suspend fun refresh(resource: MediaResource): CloudResult<MediaResource> {
        val token = resource.refreshToken
            ?: return CloudResult.Failure(
                CloudError.ResourceUrlExpired(
                    message = "该资源无法刷新（缺少刷新上下文）",
                    refreshable = false,
                ),
            )

        val provider = when (val result = registry.require(token.key.providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        // Check the *declared* capability before asking. A provider that says it cannot refresh must
        // not be probed: the answer would be `Unsupported` either way, and asking wastes a call and
        // muddies the log with a failure that was never going to succeed.
        val capabilities = provider.capabilities()
        if (capabilities.media.urlRefresh != com.notdisk.core.model.CapabilitySupport.SUPPORTED &&
            capabilities.media.urlRefresh != com.notdisk.core.model.CapabilitySupport.UNVERIFIED
        ) {
            return CloudResult.Failure(
                CloudError.ResourceUrlExpired(
                    message = "该网盘不支持刷新播放地址，请下载后播放",
                    refreshable = false,
                ),
            )
        }

        return resolve(
            accountId = token.key.accountId,
            providerId = token.key.providerId,
            fileId = token.key.fileId,
            qualityHint = token.qualityHint ?: resource.renditions.firstOrNull()?.id,
            // A refresh must actually re-ask: the cached entry is exactly what just failed.
            forceRefresh = true,
        )
    }

    /**
     * True when [resource] is close enough to expiry that the coordinator should refresh now rather
     * than let playback run into a dead URL mid-stream.
     *
     * [nowMillis] is injectable so this can be asserted against a virtual clock instead of by
     * sleeping.
     */
    fun shouldRefreshProactively(
        resource: MediaResource,
        nowMillis: Long = clock(),
    ): Boolean = resource.isExpired(skewMillis = REFRESH_SKEW_MILLIS, nowMillis = nowMillis)

    /** Resolves an alternate audio track (for providers whose HLS separates audio). */
    suspend fun resolveAudioTrack(
        accountId: AccountId,
        providerId: com.notdisk.core.model.ProviderId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }
        return provider.media.resolveAudioTrack(accountId, fileId, track)
    }

    /**
     * Reports playback position to the vendor.
     *
     * Deliberately pass-through: for 115 the documented endpoint does not exist, so this returns
     * `Unsupported` and the UI simply does not offer "continue watching" for that provider. No
     * coordinator-level faking happens here (e.g. persisting a local position and pretending the
     * vendor accepted it) — that would misrepresent the provider's capability.
     */
    suspend fun reportProgress(
        accountId: AccountId,
        providerId: com.notdisk.core.model.ProviderId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }
        return provider.media.reportPlaybackProgress(accountId, fileId, positionMs, durationMs)
    }

    /** Last known position, when the vendor exposes one. */
    suspend fun lastKnownPosition(
        accountId: AccountId,
        providerId: com.notdisk.core.model.ProviderId,
        fileId: FileId,
    ): CloudResult<Long?> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }
        return provider.media.getPlaybackProgress(accountId, fileId)
    }

    /**
     * Builds the token a caller keeps for the whole playback session.
     *
     * Exposed so a ViewModel can persist a *stable* handle across a configuration change without
     * holding — or being tempted to persist — the signed URL itself.
     */
    fun refreshTokenFor(
        accountId: AccountId,
        providerId: com.notdisk.core.model.ProviderId,
        fileId: FileId,
        resource: MediaResource,
        qualityHint: String? = null,
    ): CloudResult<MediaRefreshToken> {
        val provider = registry.find(providerId)
            ?: return CloudResult.Failure(
                CloudError.Unsupported(message = "未注册的网盘 Provider: $providerId"),
            )
        return CloudResult.Success(
            provider.media.refreshTokenFor(accountId, fileId, resource, qualityHint),
        )
    }

    /** Drops cached resolutions for one account, e.g. after logout. */
    suspend fun invalidateAccount(accountId: AccountId) {
        mapLock.withLock {
            resolved.keys.filter { it.accountId == accountId }.forEach { resolved.remove(it) }
            locks.keys.filter { it.accountId == accountId }.forEach { locks.remove(it) }
        }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Returns a cached resource only when it is still comfortably valid *and* was resolved for the
     * same quality. A stale or wrong-quality entry is treated as a miss, which is what makes a
     * quality switch take effect.
     */
    private suspend fun cachedUsable(key: CloudFileKey, qualityHint: String?): MediaResource? =
        mapLock.withLock {
            val resource = resolved[key] ?: return@withLock null
            if (qualityHint != null && resource.refreshToken?.qualityHint != qualityHint) {
                return@withLock null
            }
            resource.takeIf { !shouldRefreshProactively(it) }
        }

    private suspend fun install(key: CloudFileKey, resource: MediaResource) {
        mapLock.withLock { resolved[key] = resource }
    }

    private suspend fun forget(key: CloudFileKey) {
        mapLock.withLock { resolved.remove(key) }
    }

    private suspend fun lockFor(key: CloudFileKey): Mutex = mapLock.withLock {
        locks.getOrPut(key) { Mutex() }
    }

    companion object {
        private const val TAG = "MediaCoordinator"

        /**
         * Refresh this long before the stated expiry.
         *
         * Two minutes is chosen against 115's observed lifetimes: long enough to cover a slow
         * re-resolve without stalling playback, short enough that the refresh happens while the
         * current URL is still usable (so a failed refresh does not interrupt what is playing).
         */
        const val REFRESH_SKEW_MILLIS = 2 * 60 * 1000L
    }
}

/** Unused-import guard: keeps `getOrNull` referenced for readers of the log-redaction examples. */
@Suppress("unused")
private val unusedGetOrNull: (CloudResult<Int>) -> Int? = { it.getOrNull() }
