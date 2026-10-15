package com.notdisk.provider.baidu

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaRendition
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.ProviderId
import com.notdisk.provider.baidu.api.BaiduEndpoints
import com.notdisk.provider.baidu.api.BaiduErrorFamily
import com.notdisk.provider.baidu.api.BaiduHttpClient
import com.notdisk.provider.baidu.api.longOf
import com.notdisk.provider.baidu.api.stringOf
import com.notdisk.provider.baidu.auth.BaiduSession

/**
 * Baidu Netdisk's media surface.
 *
 * ### The shape of playback (from §7.3), and why it is two requests
 * ```
 * ① GET /api/streaming?path=<enc>&type=M3U8_AUTO_480&vip=0&jsToken=…&nom3u8=1
 *    → {"errno":133,"adTime":8,"adToken":"…","ltime":5}        ← an ad gate, not a failure
 * ② GET /api/streaming?…&isplayer=1&check_blue=1&adToken=<enc>
 *    → HTTP 200, Content-Type: application/x-mpegURL            ← the HLS master playlist
 * ```
 *
 * The reference material measured both phases against a live account. Three consequences shape this
 * class:
 *
 *  1. **`errno 133` is not an error.** It is phase one of a documented handshake, and it is reported
 *     here as `CloudError.Unsupported` *naming a narrow capability*
 *     ([ERRNO_AD_REQUIRED_CAPABILITY]) rather than as a generic failure. That naming is what lets the
 *     shared contract suite distinguish "this provider lied about streaming" from "streaming works but
 *     this particular file is behind an ad gate", and it is what lets a caller decide whether to
 *     surface an ad notice.
 *  2. **The final URL is the API URL itself.** The measured player feeds `getUrl(...)` straight into
 *     `<video src>`; there is no separate CDN URL to construct. That is why [MediaResource.url] is the
 *     `/api/streaming` call with `adToken`, and why `refreshable` is true — re-issuing the request is
 *     exactly how the vendor's own client switches rendition.
 *  3. **The playlist's segments need no credentials.** §7.4 measured a segment fetch with
 *     `credentials: "omit"` returning `HTTP 200`, and `Range: bytes=0-1023` returning `206`. So
 *     [MediaResource.headers] is **empty** for Baidu — a genuine difference from 115, whose CDN
 *     validates `Referer`/`Origin`. Sending cookies here would leak the account credential to a CDN
 *     for no benefit.
 *
 * ### Renditions
 * §7.3.1 documents the ladder (`1080`/`720`/`480`/`360`) and the rule that generates it from the
 * source's resolution. The unified model wants *achievable* renditions, and the vendor's own list is
 * computed client-side from `width`/`height` — which this class does not have before phase one. So
 * the declared set is the full documented ladder, ordered high-to-low, and the server's answer is
 * what actually decides: asking for a rendition the source cannot produce yields `errno 2`, which is
 * reported honestly rather than hidden.
 */
internal class BaiduMediaProvider(
    private val http: BaiduHttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<BaiduSession>,
    private val logger: CloudLogger,
) : com.notdisk.core.provider.MediaProvider {

    override val providerId: ProviderId = BaiduProviderId

    override suspend fun resolveMedia(
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String?,
    ): CloudResult<MediaResource> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = BaiduFileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的百度网盘文件标识: $fileId"),
            )
        if (ref.isFolder) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "文件夹无法播放",
                    capability = "media.videoStreaming",
                ),
            )
        }

        // The rendition is chosen from the caller's hint when it names a documented one, and the
        // vendor's default (`480`) otherwise — §7.3.1 documents `DEFAULT_RESOLUTION = 480`.
        // `firstOrNull` rather than `takeIf`, because the two branches must have the same type: a
        // `String? ?: MediaRendition` expression would infer `Any` and silently widen the parameter.
        val rendition = qualityHint
            ?.let { hint -> RENDITIONS.firstOrNull { it.id == hint } }
            ?: DEFAULT_RENDITION

        return streaming(session, ref.path, rendition, adToken = null, accountId = accountId, fileId = fileId)
    }

    /**
     * Fetches phase one, and phase two when the response is an ad gate.
     *
     * A single request is attempted first because §7.3's measured table shows that a request **without**
     * `nom3u8` can return the playlist directly when the server decides no ad applies ("无广告时直接
     * 返回播放列表"). Only if the answer is `errno 133` is the second phase issued — so a file that
     * needs no ad costs one request rather than two.
     *
     * `depth` bounds the recursion: the handshake is documented to have exactly two phases, so a third
     * attempt would mean the server is gating again for a reason this client cannot satisfy, and
     * looping would turn a clear failure into a hang.
     */
    private suspend fun streaming(
        session: BaiduSession,
        path: String,
        rendition: MediaRendition,
        adToken: String?,
        accountId: AccountId,
        fileId: FileId,
        depth: Int = 0,
    ): CloudResult<MediaResource> {
        val request = http.get(
            url = http.resolve(BaiduEndpoints.STREAMING),
            query = buildMap {
                put("path", path)
                put("type", rendition.id)
                // §7.3.1: `vip` selects the entitlement tier. `0` is the honest value for a session
                // whose membership this client has not verified; sending `1` would claim an
                // entitlement the account may not have.
                put("vip", "0")
                if (adToken != null) {
                    // §7.3's phase two: the three parameters the measured player adds.
                    put("isplayer", "1")
                    put("check_blue", "1")
                    put("adToken", adToken)
                }
            },
            cookies = session.credential.cookieHeader(),
        )

        // The raw-envelope path is used rather than `execute` because phase one's `errno 133` is a
        // documented handshake step whose payload carries the token phase two needs — classifying it
        // through the shared error mapper would either lose the token or push vendor fields into
        // `CloudError`. Transport failures are still typed by the client.
        return when (val result = http.executeRawEnvelope(request, tag = "streaming")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)

            is CloudResult.Success -> {
                val raw = result.data

                when {
                    // Phase two (or a no-ad phase one) answered with the playlist itself. §5.1 of the
                    // media report records that the player feeds this very URL into the video element,
                    // so the request URL *is* the playable location.
                    raw.isPlaylist -> {
                        logger.i(
                            TAG,
                            "resolved HLS for file=${LogRedaction.redactId(fileId.value)} " +
                                "rendition=${rendition.label}",
                        )
                        CloudResult.Success(
                            MediaResource(
                                url = raw.requestUrl,
                                protocol = MediaProtocol.HLS,
                                mimeType = "application/vnd.apple.mpegurl",
                                // §7.4 measured that segment URLs are self-signed and need **no** Cookie,
                                // UA or Referer. The playlist request itself carries the session cookie
                                // (through this client), but the player must not be handed the account
                                // credential to forward to a CDN — so this is deliberately empty. This is
                                // a real difference from 115, whose CDN validates attribution headers.
                                headers = emptyMap(),
                                expiresAt = null,
                                refreshable = true,
                                supportsRangeRequests = true,
                                durationMs = null,
                                renditions = RENDITIONS,
                                refreshToken = MediaRefreshToken(
                                    key = CloudFileKey(BaiduProviderId, accountId, fileId),
                                    qualityHint = rendition.id,
                                ),
                            ),
                        )
                    }

                    // §7.3's ad gate. Retry once with the supplied token.
                    raw.code == AD_REQUIRED_CODE -> {
                        val token = raw.root?.stringOf("adToken")
                        val adSeconds = raw.root?.longOf("adTime")
                        if (token.isNullOrBlank() || depth >= MAX_HANDSHAKE_DEPTH) {
                            // The gate fired but no usable token arrived, or it fired twice. Reporting
                            // the gate itself is truthful; retrying without a token would loop.
                            logger.w(TAG, "ad gate at depth $depth without a usable adToken")
                            CloudResult.Failure(adGateError(adSeconds))
                        } else {
                            logger.i(TAG, "ad gate (${adSeconds ?: "?"}s); issuing phase two")
                            streaming(
                                session = session,
                                path = path,
                                rendition = rendition,
                                adToken = token,
                                accountId = accountId,
                                fileId = fileId,
                                depth = depth + 1,
                            )
                        }
                    }

                    else -> CloudResult.Failure(
                        com.notdisk.provider.baidu.api.mapBaiduError(
                            code = raw.code,
                            message = raw.root?.stringOf("show_msg", "errmsg", "error_msg"),
                            httpStatus = raw.httpStatus,
                            family = BaiduErrorFamily.MEDIA,
                        ),
                    )
                }
            }
        }
    }

    /**
     * The typed failure for an ad gate that could not be passed.
     *
     * `capability` names the **narrow** hole rather than the broad `media.videoStreaming`, which is
     * what lets the shared contract suite accept a `SUPPORTED` streaming declaration while this file
     * still reports an honest failure for the specific case.
     */
    private fun adGateError(adSeconds: Long?): CloudError = CloudError.Unsupported(
        message = buildString {
            append("百度网盘要求先观看广告才能播放")
            if (adSeconds != null) append("（约 ${adSeconds} 秒）")
            append("，本版本未接入广告流程")
        },
        capability = ERRNO_AD_REQUIRED_CAPABILITY,
        vendorCode = AD_REQUIRED_CODE.toString(),
    )

    /**
     * Alternate audio playlists.
     *
     * §7.6 is explicit that Baidu's audio files go through the **dlink** path rather than
     * `/api/streaming`, and that the only audio-track separation is the video transcoder's
     * `multitrack_list` — which this provider does not surface, because doing so correctly requires
     * reading `extra_info` from `/api/filemetas` and mapping positional indices onto `audio/{N}.m3u8`
     * paths whose naming convention the reference material does not document for Baidu (it documents
     * it for 115).
     *
     * Guessing that convention would produce a URL that 404s. So this returns a typed `Unsupported`
     * and `separateAudioTracks` is declared `UNSUPPORTED`.
     */
    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> {
        sessionProvider(accountId).let { if (it is CloudResult.Failure) return CloudResult.Failure(it.error) }
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "百度网盘的音轨分离命名规则未在文档中确定，本版本不提供单独音轨地址",
                capability = "media.separateAudioTracks",
            ),
        )
    }

    /**
     * §7 — playback-position reporting.
     *
     * **Not implemented**, and the reference material is the reason: §7 records that the vendor's own
     * progress is kept **entirely in `localStorage`** under `video_<md5>` and that the only
     * cross-device trace is a `video_progress` parameter appended to a *QR code URL*. There is no
     * progress-report endpoint in the material at all.
     *
     * Reporting success here would fabricate a capability, so the capability is `UNVERIFIED` and this
     * returns a typed `Unsupported`. The user-visible consequence — "continue watching" is not synced
     * across devices — is documented rather than hidden.
     */
    override suspend fun reportPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> {
        sessionProvider(accountId).let { if (it is CloudResult.Failure) return CloudResult.Failure(it.error) }
        return CloudResult.Failure(
            CloudError.Unsupported(
                message = "百度网盘 Web 端未公开播放进度上报接口（进度保存在本地 localStorage）",
                capability = "media.playbackProgress",
            ),
        )
    }

    /**
     * The last known position.
     *
     * Returns a **successful `null`** rather than a failure: "this provider cannot report progress" is
     * a capability statement, while "there is no stored position for this file" is a normal answer the
     * UI renders as "start from the beginning". Conflating them would make every playback show an
     * error banner.
     */
    override suspend fun getPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<Long?> {
        when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> Unit
        }
        return CloudResult.Success(null)
    }

    override fun refreshTokenFor(
        accountId: AccountId,
        fileId: FileId,
        resource: MediaResource,
        qualityHint: String?,
    ): MediaRefreshToken = MediaRefreshToken(
        key = CloudFileKey(BaiduProviderId, accountId, fileId),
        qualityHint = qualityHint ?: resource.refreshToken?.qualityHint,
    )

    private companion object {
        const val TAG = "BaiduMedia"

        /** §7.3's ad gate. Named here because it is a handshake step, not an error. */
        const val AD_REQUIRED_CODE = 133

        /** The handshake is documented to have exactly two phases. */
        const val MAX_HANDSHAKE_DEPTH = 2

        /**
         * The narrow capability name reported when phase one's ad gate fires.
         *
         * Kept as a constant so the shared contract suite's "a broad capability may have narrow holes,
         * but the hole must be identifiable" rule is satisfied by a value that cannot drift.
         */
        const val ERRNO_AD_REQUIRED_CAPABILITY = "media.streaming.adToken"

        /** §7.3.1: the vendor's own default is 480. */
        val DEFAULT_RENDITION = MediaRendition(id = "M3U8_AUTO_480", label = "流畅 480P", height = 480)

        /**
         * §7.3.1's documented ladder, high to low.
         *
         * `M3U8_AUTO_2160` exists in the parameter table but the vendor's UI never exposes it, and
         * §7.3.1 records that "没有「原画」「蓝光」，最高只有 1080P 超清" — so 2160 is deliberately absent
         * rather than offered as a rendition that would answer `errno 31023`.
         */
        val RENDITIONS: List<MediaRendition> = listOf(
            MediaRendition(id = "M3U8_AUTO_1080", label = "超清 1080P", height = 1080),
            MediaRendition(id = "M3U8_AUTO_720", label = "高清 720P", height = 720),
            MediaRendition(id = "M3U8_AUTO_480", label = "流畅 480P", height = 480),
            MediaRendition(id = "M3U8_AUTO_360", label = "省流 360P", height = 360),
        )
    }
}
