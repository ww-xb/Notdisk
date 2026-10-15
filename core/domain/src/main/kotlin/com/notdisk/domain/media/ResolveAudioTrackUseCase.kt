package com.notdisk.domain.media

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Resolves one alternate audio track into its own playable resource.
 *
 * ### The vendor reality this exists for
 * Most players expect audio to be an `#EXT-X-MEDIA` group *inside* the HLS manifest they already
 * loaded, and for most vendors it is. 115's transcoder does something different: it emits an
 * **entirely separate `audio/{N}.m3u8` playlist per track**, which means the audio is not reachable
 * from the video's manifest at all, and a player that only knows how to switch `EXT-X-MEDIA` groups
 * will show a single, wrong-language, or silent track.
 *
 * A client can respond in two ways: teach the player to open a second concurrent stream, or resolve
 * the alternate track to its own [MediaResource] and hand it over. This use case is the second
 * option, and it is the reason the capability model distinguishes
 * [com.notdisk.core.model.MediaCapabilities.separateAudioTracks] from a generic "has audio".
 * The UI reads that flag to decide whether to offer a track picker at all.
 *
 * ### Why this is gated while [ResolveMediaResourceUseCase] deliberately is not
 * Because here the capability *does* partition the input precisely. `separateAudioTracks` is a
 * statement about the provider's delivery model, not about any one file: a provider that reports
 * [com.notdisk.core.model.CapabilitySupport.UNSUPPORTED] will never produce a resolvable track
 * resource, for any file, ever. That is exactly the situation a gate is for — a request that cannot
 * succeed should not be sent, and the caller should get "该网盘不支持独立音轨" rather than a vendor error
 * that looks like a transient failure.
 *
 * ### Why the track is passed as a value, not an index
 * [MediaAudioTrack.id] is a stable, non-secret handle the provider issued. Passing it back rather
 * than an index means the identifier round-trips through whatever representation the vendor uses
 * (115's numeric `{N}` path segment), so the domain layer never has to know how a track is
 * addressed. Passing an index would also be fragile: the track list a caller holds can be stale
 * relative to the vendor's current manifest, and an off-by-one would silently play the wrong
 * language — a bug users notice but rarely report accurately.
 */
class ResolveAudioTrackUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id.
     * @param fileId the media file whose audio is being switched.
     * @param track the track to resolve, as returned in the parent
     *   [MediaResource.audioTracks]. Must be one this provider issued.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.media.separateAudioTracks,
                capability = CAPABILITY_SEPARATE_AUDIO,
                message = "该网盘不支持独立音轨",
            ).flatMap { provider.media.resolveAudioTrack(accountId, fileId, track) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_SEPARATE_AUDIO: String = "media.separateAudioTracks"
    }
}
