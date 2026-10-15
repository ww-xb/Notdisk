package com.notdisk.domain.media

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.invalidArgument
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Reports playback position so the vendor can offer "continue watching" on another device.
 *
 * ### The rule this use case exists to enforce: never fake success
 * The provider contract is unambiguous — "Never fake a successful report" — and this is the use
 * case where that rule is easiest to break by accident, because of how the caller wants to use it.
 * A player reports progress on a timer, and a progress report that fails is *not* something the
 * user should ever see: it would be absurd to interrupt playback with "could not save your
 * position". The natural, wrong response is to swallow the error and return success. That is how a
 * client ends up telling the user their progress is synced when it is not, and they find out on the
 * TV.
 *
 * So the result propagates faithfully and the *caller* decides to ignore it:
 *
 * ```kotlin
 * // In the player: report, log, and carry on. The failure is real; the reaction is silence.
 * reportProgress(...).onFailure { log.warn("progress not saved: ${it.userFacingMessage}") }
 * ```
 *
 * The distinction between "the provider has no such feature" and "the provider has it and the
 * report failed" therefore survives all the way to the caller, which can show a one-time,
 * non-blocking notice ("此网盘不支持云端进度") for the first case and stay silent for the second.
 *
 * ### Why `UNVERIFIED` is allowed through but not treated as working
 * [com.notdisk.core.model.MediaCapabilities.playbackProgress] is
 * [com.notdisk.core.model.CapabilitySupport.UNVERIFIED] on 115: `GET /history/list` demonstrably
 * exposes `current_time`, but the *report* direction (`POST /history/update` or equivalent) was
 * never located in this build, and the provider consequently returns
 * [com.notdisk.core.common.error.CloudError.Unsupported] from `reportPlaybackProgress`.
 *
 * The gate lets `UNVERIFIED` through rather than refusing it, because refusing would make a
 * documented-but-unexercised endpoint permanently unreachable — the provider is the authority on
 * whether its own call works, and it answers honestly. What the domain layer must not do is
 * *upgrade* that uncertainty: this use case never reports success on the provider's behalf, and the
 * settings screen reads the three-state capability to phrase the feature honestly rather than
 * promising sync.
 *
 * ### Validation
 * A negative position is refused locally: it cannot come from a real player, so it means the caller
 * has a clock bug, and forwarding it would have a vendor either reject the request or — worse —
 * store a corrupt resume point that the user then hits on their next device. A non-positive
 * `durationMs` is refused for the same reason; `null` is allowed, because a live stream genuinely
 * has no duration.
 */
class ReportPlaybackProgressUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that owns the file.
     * @param accountId the local account id.
     * @param fileId the file being played.
     * @param positionMs current playback position. Must not be negative.
     * @param durationMs total duration when known; `null` for live/unknown-length media.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long? = null,
    ): CloudResult<Unit> {
        if (positionMs < 0L) {
            return invalidArgument("播放位置", "不能为负数")
        }
        if (durationMs != null && durationMs <= 0L) {
            return invalidArgument("播放时长", "必须为正数")
        }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            gate.require(
                support = capabilities.media.playbackProgress,
                capability = CAPABILITY_PLAYBACK_PROGRESS,
                message = "该网盘不支持上报播放进度",
            ).flatMap {
                provider.media.reportPlaybackProgress(accountId, fileId, positionMs, durationMs)
            }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_PLAYBACK_PROGRESS: String = "media.playbackProgress"
    }
}
