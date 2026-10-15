package com.notdisk.domain.media

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Resolves a playable [MediaResource] for a file.
 *
 * ### The layer boundary this use case draws
 * The player receives a [MediaResource] and nothing else — no provider, no vendor URL builder, no
 * [ProviderId]. This use case is the boundary that makes that true. Everything upstream of it
 * (the file list, the preview screen) knows a file by *identity*; everything downstream (Media3)
 * knows it by a *signed URL with an expiry*. Bridging those two worlds is the only job here, and
 * keeping it in one place is what stops vendor URL knowledge leaking into the player.
 *
 * ### Why the resource is never cached here
 * [MediaResource.url] is a short-lived signed URL, and the model is explicit that it must not be
 * persisted or logged verbatim. This use case therefore does no caching of its own, not even a
 * short-lived in-memory one. A cache here looks tempting and is wrong for two reasons:
 *
 *  - the URL's lifetime is decided by the vendor and is frequently *shorter* than any TTL the app
 *    could guess, so an entry still "fresh" by the app's clock can already be dead;
 *  - a cache keyed by file id would be shared across two accounts of the same provider, which is
 *    exactly the collision [FileId]'s documentation warns about (it is opaque and *not* globally
 *    unique).
 *
 * Refresh is instead handled explicitly and observably by [RefreshMediaResourceUseCase], driven by
 * the [MediaResource.expiresAt] the vendor actually reported. That makes expiry a *decision* the
 * coordinator takes, with a skew of its choosing, rather than a side effect of cache eviction.
 *
 * ### Why the capability gate here is coarse, on purpose
 * [com.notdisk.core.model.MediaCapabilities.videoStreaming] and `audioStreaming` say whether the
 * *provider* can stream a kind of media. The caller has a [FileId], not a file — whether this item
 * is video, audio or a `.zip` is not knowable without a metadata round trip, and a provider can
 * support streaming in general while refusing one specific item (still transcoding, blocked by
 * moderation, unsupported codec).
 *
 * So the gate fires only in the one case where the answer is unambiguously "no": **neither** media
 * kind is possible. That is a real configuration (a browse-only integration), and refusing it here
 * saves a round trip and yields a clearer message. Every finer distinction is left to the provider,
 * which is required to fail with [com.notdisk.core.common.error.CloudError.Unsupported] for an
 * item it cannot stream — an answer this layer could not compute and must not guess.
 */
class ResolveMediaResourceUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id. Required rather than ambient: the signed URL is issued
     *   for *that account's* session and is not transferable to another.
     * @param fileId the media file to resolve.
     * @param qualityHint a [com.notdisk.core.model.MediaRendition.id] previously returned by this
     *   provider, or `null` for the provider's default. Providers without renditions ignore it.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String? = null,
    ): CloudResult<MediaResource> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            val anyStreaming =
                capabilities.media.videoStreaming != CapabilitySupport.UNSUPPORTED ||
                    capabilities.media.audioStreaming != CapabilitySupport.UNSUPPORTED

            gate.requireFlag(
                available = anyStreaming,
                capability = CAPABILITY_STREAMING,
                message = "该网盘不支持在线播放",
            ).flatMap { provider.media.resolveMedia(accountId, fileId, qualityHint) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_STREAMING: String = "media.streaming"
    }
}
