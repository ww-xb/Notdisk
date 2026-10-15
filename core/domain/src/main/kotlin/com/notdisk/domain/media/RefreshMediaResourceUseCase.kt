package com.notdisk.domain.media

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.flatMap
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.capability.CapabilityGate
import com.notdisk.domain.internal.withProviderAndCapabilities

/**
 * Re-resolves a media resource whose signed URL has expired or is about to.
 *
 * ### The single most important thing this use case does
 * It tells the truth about *whether the situation is recoverable*, and the whole design of its
 * failure path exists for that. When a URL expires there are two very different worlds:
 *
 *  - **expired but recoverable.** The provider can issue a fresh URL. The player can refresh
 *    transparently, mid-stream, and the user sees a brief rebuffer at worst. This is 115: the CDN
 *    segment URLs carry `se`/`ck` parameters whose lifetime is undocumented, but re-requesting
 *    `files/video` always yields a working replacement.
 *  - **expired and stuck.** The provider cannot re-resolve — it has no refresh endpoint, or the
 *    account lost access, or the item was deleted. No amount of retrying helps, and the only
 *    correct UI is a terminal "此视频暂时无法播放" with a *different* remedy (re-authorise, download,
 *    open in the vendor's app).
 *
 * A player that cannot distinguish them does the wrong thing in both cases: it either gives up on a
 * recoverable stream (a bug users experience as "playback randomly stops") or retries forever on a
 * dead one (a bug users experience as a spinner that never ends, draining battery). The distinction
 * is carried by [CloudError.ResourceUrlExpired.refreshable], and **this use case is the only place
 * in the app that is positioned to set it correctly**, because it is the only place that has both
 * the request intent and the provider's capability declaration in hand.
 *
 * ### Why the non-refreshable case is an error and not a success with a flag
 * Because it is not a value — it is a refusal to produce one. The caller asked for a playable
 * resource and there is none to give. Modelling it as `CloudResult.Success<MediaResource?>` would
 * force every call site to handle a nullability that only ever means failure, and would let a
 * careless caller treat "no resource" as "nothing to do". The failure channel carries the
 * *classification* ([ResourceUrlExpired] with `refreshable = false`) that the UI needs to choose
 * its remedy, which is the information a plain null could never carry.
 *
 * ### Why the capability is checked *before* asking the provider
 * The provider would fail anyway — that is what `urlRefresh == UNSUPPORTED` means — but it would
 * fail with whatever its own generic error is, and the caller would receive a failure it cannot
 * classify. By refusing first, this use case converts an unclassifiable provider error into the
 * exact [CloudError.ResourceUrlExpired] with `refreshable = false` that the UI branches on. The
 * refusal is *more* informative than the real thing, which is the point, and it also means a doomed
 * request is never sent.
 *
 * ### Why the token, not the file id, is the parameter
 * [MediaRefreshToken] carries the fully-qualified
 * [com.notdisk.core.model.CloudFileKey] plus a non-secret quality hint, so the caller never has
 * to remember which account a stream belonged to. That matters during playback: the coordinator
 * holds a token for the lifetime of a session, and a user switching accounts mid-session must not
 * cause a refresh to be issued against the wrong credential. It is also the reason the token may be
 * kept for hours while the [MediaResource] it came from must not be kept at all.
 *
 * ### Provider-agnostic by construction
 * The `qualityHint` inside the token is opaque here. Whatever the user selected in the quality
 * picker is passed straight back to the provider that issued it, so re-resolving after a quality
 * change and re-resolving after an expiry are the same code path — and the app never has to know
 * what a "definition id" means to any particular vendor.
 */
class RefreshMediaResourceUseCase(
    private val registry: ProviderRegistry,
    private val gate: CapabilityGate = CapabilityGate.Default,
) {

    /**
     * @param providerId the integration that issued [token]. Must match
     *   `token.key.providerId`; a mismatch is a caller bug, and is reported as
     *   [CloudError.InvalidArgument] rather than silently resolving against the wrong provider.
     * @param accountId the local account id. Must match `token.key.accountId` for the same reason.
     * @param token the stable, non-secret locator obtained from the provider (see
     *   [com.notdisk.core.provider.MediaProvider.refreshTokenFor]).
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        token: MediaRefreshToken,
    ): CloudResult<MediaResource> {
        // The token carries its own key precisely so this can be checked. A mismatch means the
        // caller crossed two accounts or two providers, which on 115 would produce a request that
        // either 404s or — worse, where file ids collide across accounts — streams the wrong file.
        if (token.key.providerId != providerId || token.key.accountId != accountId) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "刷新令牌与目标账号不匹配：令牌属于 " +
                        "${token.key.providerId}/${token.key.accountId}，" +
                        "请求的是 $providerId/$accountId",
                ),
            )
        }

        return registry.withProviderAndCapabilities(providerId) { provider, capabilities ->
            val refreshSupport = capabilities.media.urlRefresh

            if (refreshSupport == CapabilitySupport.UNSUPPORTED) {
                // The classification the whole use case exists to produce. `refreshable = false`
                // is the load-bearing part: it tells the UI to stop offering "retry" and switch to
                // a terminal remedy, instead of looping on a refresh that can never succeed.
                return@withProviderAndCapabilities CloudResult.Failure(
                    CloudError.ResourceUrlExpired(
                        message = "该网盘不支持刷新播放地址，请重新打开或下载后播放",
                        refreshable = false,
                    ),
                )
            }

            // UNVERIFIED is allowed through for the same reason as elsewhere in this module: the
            // vendor documents it and the provider is the authority on whether its own call works.
            gate.require(
                support = refreshSupport,
                capability = CAPABILITY_URL_REFRESH,
                message = "该网盘不支持刷新播放地址",
            ).flatMap {
                provider.media.resolveMedia(
                    accountId = token.key.accountId,
                    fileId = token.key.fileId,
                    qualityHint = token.qualityHint,
                )
            }
        }
    }

    companion object {
        /** Stable capability path, referenced by tests and by the refusal error. */
        const val CAPABILITY_URL_REFRESH: String = "media.urlRefresh"
    }
}
