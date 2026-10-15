package com.notdisk.domain.media

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Reads the vendor's last known playback position for a file ("continue watching").
 *
 * ### Why the position comes from the vendor at all
 * The app could remember positions locally, and for a single device it would even be simpler. The
 * reason to ask the vendor is cross-device continuity: a user who watches half an episode on their
 * phone expects the TV to offer to resume. That data lives in the vendor's history, and 115 exposes
 * it through `GET /history/list`'s `current_time`.
 *
 * The cost is that this endpoint is a *history* endpoint, not a per-file lookup: it is inherently
 * paginated and expensive relative to what it returns. A caller that calls this per list row to
 * decorate a "resume" badge will produce a request storm. The capability
 * [com.notdisk.core.model.MediaCapabilities.playbackProgress] exists so the UI can decide
 * whether the feature is worth that cost, and callers should call this once when a file is opened,
 * not while scrolling.
 *
 * ### Why the return type is `CloudResult<Long?>` rather than `CloudResult<Long>`
 * Because "the vendor says this file was never started" and "the provider cannot report progress"
 * are different states, and both are normal:
 *
 *  - a **success carrying `null`** means the provider answered and has no position for this file —
 *    start from the beginning, no error to show;
 *  - a **failure of type [com.notdisk.core.common.error.CloudError.Unsupported]** means the
 *    provider has no such feature in this build (it is `UNSUPPORTED` on the mock provider, and
 *    `UNVERIFIED` on 115 because the *report* direction was never located) — the UI simply offers
 *    no resume affordance.
 *
 * Collapsing the second into `null` would be worse than untidy: it would make "we cannot ask"
 * indistinguishable from "there is nothing to resume", and the first is a fact the settings screen
 * may want to state honestly. The model already has the right shape — `null` is a *value* here, and
 * the failure channel is separate — so this use case preserves both.
 */
class GetPlaybackProgressUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id.
     * @param fileId the file whose position is wanted.
     * @return the position in milliseconds, or a success carrying `null` when the provider has no
     *   recorded position for this file.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<Long?> =
        registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.media.playbackProgress,
                capability = CAPABILITY_PLAYBACK_PROGRESS,
                message = "该网盘不支持播放进度",
            ).flatMap { provider.media.getPlaybackProgress(accountId, fileId) }
        }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_PLAYBACK_PROGRESS: String = "media.playbackProgress"
    }
}
