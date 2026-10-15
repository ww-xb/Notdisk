package com.notdisk.provider.p115

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.LogRedaction
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.MediaAudioTrack
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.MediaRefreshToken
import com.notdisk.core.model.MediaRendition
import com.notdisk.core.model.MediaResource
import com.notdisk.core.model.MediaSubtitleTrack
import com.notdisk.core.model.ProviderId
import com.notdisk.provider.p115.api.P115Endpoints
import com.notdisk.provider.p115.api.P115Hosts
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.asLongOrNull
import com.notdisk.provider.p115.api.asNonBlankStringOrNull
import com.notdisk.provider.p115.api.intOf
import com.notdisk.provider.p115.api.longOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import com.notdisk.provider.p115.auth.P115Session
import kotlinx.serialization.json.JsonObject

/**
 * 115's media surface.
 *
 * ### The shape of 115 playback (from §10)
 * ```
 * GET webapi /files/video?pickcode=<pc>
 *   → { video_url: "https://115.com/api/video/m3u8/<pc>.m3u8", … }
 *       ↓ (the client follows the redirect chain itself)
 *   https://cpats01.115.com/<rand32>/<YYYYMM>/<SHA1>/video-1080p/video.m3u8?u=&se=u,ua&s=&ck=
 *       ↓
 *   video-1080p/video-00001.ts   +   audio/0-00001.ts
 * ```
 *
 * ### The three things that would break a naive implementation
 *
 * 1. **The CDN path is not knowable in advance.** §10.2's "实现建议" is explicit: the random
 *    32-hex segment and the date segment change on every request, so the app must *follow the
 *    redirect* from `video_url` rather than constructing a `cpats01` URL. This implementation puts
 *    `video_url` straight into [MediaResource] and lets the player follow it.
 *
 * 2. **115 transcodes to separated video and audio playlists.** §10.2 items 2 and 4 record that the
 *    HLS output has an independent video track and one `audio/{N}.m3u8` per audio track. The player
 *    must therefore receive [MediaResource.audioTracks], and a quality change is a change of the
 *    `video-{N}p` segment. Both facts are exposed through the unified model rather than being left
 *    for the player to discover.
 *
 * 3. **Subtitles are not in the m3u8.** §10.1's warning: `subtitle_info[].url` carries a trailing
 *    `\r\n` that must be trimmed, or the resulting HTTP request fails with an invalid-URL error.
 *
 * ### Secrets
 * `video_url`, the CDN segment URLs and every `subtitle_info[].url` are short-lived bearer
 * resources: possessing the URL is sufficient to fetch the media. They are therefore never logged
 * verbatim (see [LogRedaction.redactUrl]) and never persisted — only the [MediaRefreshToken], which
 * contains stable identifiers, is retained.
 */
internal class P115MediaProvider(
    private val http: P115HttpClient,
    private val sessionProvider: suspend (AccountId) -> CloudResult<P115Session>,
    private val pickcodes: P115PickcodeResolver,
    private val logger: CloudLogger,
) : com.notdisk.core.provider.MediaProvider {

    override val providerId: ProviderId = P115ProviderId

    override suspend fun resolveMedia(
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String?,
    ): CloudResult<MediaResource> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val ref = P115FileIdCodec.decode(fileId)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(message = "无法解析的 115 文件标识: $fileId"),
            )

        // §1.5 / §15.2 item 7: single-file operations are addressed by pickcode, not file id. The
        // resolver consults the cache first, so a file the user just saw in a list costs no extra
        // request; a cold deep link costs exactly one.
        val pickcode = when (val result = pickcodes.resolve(accountId, fileId, session)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val request = http.get(
            url = http.resolve(P115Endpoints.FILES_VIDEO),
            query = mapOf("pickcode" to pickcode),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "files.video")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> mapVideoResponse(result.data.root, accountId, fileId, qualityHint)
        }
    }

    /**
     * Maps `files/video` onto [MediaResource].
     *
     * Note that §10.1's payload is returned at the **top level**, not wrapped in `data` — unlike
     * most other webapi endpoints. Both shapes are accepted so that a future server-side change
     * does not silently break playback.
     */
    private fun mapVideoResponse(
        root: JsonObject,
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String?,
    ): CloudResult<MediaResource> {
        val payload = root.objOf("data") ?: root

        // `file_status` gates playback: the manual lists it as `1` for a playable file. Anything
        // else means the vendor is still transcoding or has flagged the file, which is a *normal*
        // outcome rather than a transport error, so it is reported as Unsupported-with-reason.
        val fileStatus = payload.intOf("file_status")
        if (fileStatus != null && fileStatus != 1) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "115 暂不可播放该文件（file_status=$fileStatus），可能仍在转码或已被限制",
                    capability = "media.videoStreaming",
                    vendorCode = "file_status=$fileStatus",
                ),
            )
        }

        val videoUrl = payload.stringOf("video_url", "video_url_demo")
            ?: return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "115 未返回播放地址，该文件可能不是视频或暂不支持转码播放",
                    capability = "media.videoStreaming",
                ),
            )

        val definitions = payload.objOf("definition_list")
        val definitionIndex = payload.objOf("definition_index")

        // §10.1: `definition_list` maps a bitrate to a display name ("1080P"), and
        // `definition_index` maps the same bitrate to an ordinal. The bitrate is the stable id.
        val renditions = definitions?.entries.orEmpty().mapNotNull { (bitrate, nameElement) ->
            val label = nameElement.asNonBlankStringOrNull() ?: return@mapNotNull null
            MediaRendition(
                id = bitrate,
                label = label,
                bitrate = bitrate.toLongOrNull(),
                height = parseDefinitionHeight(label),
            )
        }.sortedByDescending { it.bitrate ?: 0L }

        val selectedQuality = qualityHint
            ?.takeIf { hint -> renditions.any { it.id == hint } }
            ?: renditions.firstOrNull()?.id

        val audioTracks = payload.arrayOf("multitrack_list")
            ?.mapIndexedNotNull { index, element ->
                val node = element as? JsonObject ?: return@mapIndexedNotNull null
                // §10.1: each entry is `{"title": "5.1环绕声：中文：国语·杜比音效"}` with no id, so
                // the positional index is the only stable handle — and §10.2 item 4 confirms the
                // audio playlist is named `audio/{N}.m3u8`, i.e. by that same position.
                val title = node.stringOf("title") ?: return@mapIndexedNotNull null
                MediaAudioTrack(id = index.toString(), label = title)
            }
            .orEmpty()

        val subtitles = payload.arrayOf("subtitle_info")
            ?.mapNotNull { element ->
                val node = element as? JsonObject ?: return@mapNotNull null
                // §10.1's explicit warning: this URL carries a trailing `\r\n`, which must be
                // trimmed or the request that uses it is malformed.
                val rawUrl = node.stringOf("url")?.trim() ?: return@mapNotNull null
                MediaSubtitleTrack(
                    url = rawUrl,
                    label = node.stringOf("title") ?: "字幕",
                    mimeType = guessSubtitleMimeType(rawUrl),
                )
            }
            .orEmpty()

        val durationSeconds = payload.longOf("play_long")

        logger.i(
            TAG,
            "resolved media for file=${LogRedaction.redactId(fileId.value)}: " +
                "${renditions.size} renditions, ${audioTracks.size} audio tracks, " +
                "${subtitles.size} subtitles",
        )
        logger.d(TAG, "media url host=${LogRedaction.redactUrl(videoUrl)}")

        return CloudResult.Success(
            MediaResource(
                url = videoUrl,
                // §10.2: `video_url` is an `.m3u8` logical entry that redirects into the CDN, so
                // HLS is the correct protocol. It is *not* a progressive file, which is why the
                // player must follow the redirect and handle a multivariant playlist.
                protocol = MediaProtocol.HLS,
                mimeType = "application/vnd.apple.mpegurl",
                // §10.2 / §1.4: the CDN expects browser-like attribution headers. A bare GET with
                // no Referer is rejected by the media CDN, which is a failure mode that looks like
                // a network problem if the headers are omitted.
                headers = mapOf(
                    "Referer" to "https://115.com/",
                    "Origin" to "https://115.com",
                    "User-Agent" to P115HttpClient.DEFAULT_USER_AGENT,
                ),
                // §10.1 gives no expiry for `video_url`. The value is *not* invented: the CDN
                // segment URLs carry `se`/`ck` parameters whose lifetime is undocumented, so this
                // stays `null` and [refreshable] carries the recoverable-failure contract instead.
                expiresAt = null,
                refreshable = true,
                // §10.2's URLs carry no documented range semantics — HLS is fetched by segment, so
                // byte-range seeking is a property of the segment list, not of the container. The
                // value is left undetermined rather than asserted.
                supportsRangeRequests = null,
                durationMs = durationSeconds?.let { it * 1000L },
                width = payload.intOf("width"),
                height = payload.intOf("height"),
                subtitles = subtitles,
                renditions = renditions,
                audioTracks = audioTracks,
                // The token carries only stable, non-secret data, so it is safe to keep for the
                // whole playback session and to use for a refresh.
                refreshToken = MediaRefreshToken(
                    key = com.notdisk.core.model.CloudFileKey(P115ProviderId, accountId, fileId),
                    qualityHint = selectedQuality,
                ),
            ),
        )
    }

    override fun refreshTokenFor(
        accountId: AccountId,
        fileId: FileId,
        resource: MediaResource,
        qualityHint: String?,
    ): MediaRefreshToken = MediaRefreshToken(
        key = com.notdisk.core.model.CloudFileKey(P115ProviderId, accountId, fileId),
        qualityHint = qualityHint ?: resource.refreshToken?.qualityHint,
    )

    /**
     * Resolves an alternate audio playlist.
     *
     * 115 cannot serve an individual audio track without the video metadata (the playlist path is
     * derived from the same `pickcode` + SHA1), so this re-resolves the media and extracts the
     * playlist for [track]. The CDN layout is documented in §10.2: `audio/{N}.m3u8`, where `N` is
     * the index into `multitrack_list`.
     */
    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        fileId: FileId,
        track: MediaAudioTrack,
    ): CloudResult<MediaResource> {
        val base = when (val result = resolveMedia(accountId, fileId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        if (base.audioTracks.none { it.id == track.id }) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "该音轨不属于此文件: ${track.label}",
                    vendorCode = "P115_UNKNOWN_AUDIO_TRACK",
                ),
            )
        }

        // §10.2 records the playlist naming pattern. The full CDN URL is discovered by following
        // the video playlist, so the caller (the resource coordinator) performs that resolution;
        // here the *relative* track reference is surfaced through the same MediaResource shape.
        val audioUrl = base.url.substringBeforeLast('/') + "/audio/${track.id}.m3u8"

        return CloudResult.Success(
            base.copy(
                url = audioUrl,
                mimeType = "application/vnd.apple.mpegurl",
                // An audio-only playlist carries no separate tracks of its own.
                audioTracks = emptyList(),
                renditions = emptyList(),
                subtitles = emptyList(),
            ),
        )
    }

    /**
     * §10.3: playback-position reporting.
     *
     * The reference manual is explicit that this endpoint was **not** located: §10.3 only lists
     * `GET aps.115.com/getFolderPlaylong` and a *speculated* `setPlaylong`, and §15.4 lists
     * "播放进度上报接口" as unverified. Reporting success here would fabricate a capability, so this
     * returns `Unsupported` and the capability is declared `UNVERIFIED`. The user-visible
     * consequence is that "continue watching" is not offered for 115.
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
                message = "115 未公开播放进度上报接口，本版本不上报播放进度",
                capability = "media.playbackProgress",
            ),
        )
    }

    /**
     * Reads the last position.
     *
     * §3.8 documents `GET /history/list` as the only *measured* source of `current_time` +
     * `play_long`, i.e. the position. It is a history feed rather than a per-file lookup, so this
     * scans the returned page for a matching `pick_code`. That is honest: when the file is not in
     * the recent history the answer is `null` ("unknown"), not `0` ("start from the beginning"),
     * because the two lead to different UI behaviour.
     */
    override suspend fun getPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<Long?> {
        val session = when (val result = sessionProvider(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }
        // `history/list` keys on `pick_code`, so the resolver is used here too. A miss means the
        // file is simply not in the history, which is `null` ("unknown"), not `0` ("start over").
        val pickcode = pickcodes.resolve(accountId, fileId, session).getOrNull()
            ?: return CloudResult.Success(null)

        val request = http.get(
            url = http.resolve(P115Endpoints.HISTORY_LIST),
            query = mapOf("offset" to "0", "limit" to "30"),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "history.list")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val entry = result.data.dataObj("list")
                    ?.let { list -> (list as? kotlinx.serialization.json.JsonArray) }
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { it.stringOf("pick_code", "pc") == pickcode }

                // §3.8: `current_time` is the position. Its unit is not stated in the manual, and
                // the sibling field `play_long` is documented there as seconds ("play_long:2525"
                // alongside a 2525-second video), so `current_time` is interpreted as seconds too.
                val seconds = entry?.longOf("current_time")
                CloudResult.Success(
                    seconds?.takeIf { it > 0 }?.let { it * 1000L },
                )
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Derives a pixel height from a `definition_list` label.
     *
     * 115's measured labels use **two** conventions: a `p`-suffixed line count (`"1080P"`) and a
     * `K`-suffixed width class (`"4K"`). Handling only the first — as an earlier version did — left
     * every `K` rendition with a `null` height, which cost the UI its resolution label and any
     * height-based quality comparison. Both are mapped onto a height because height is what the
     * unified `MediaRendition` exposes.
     *
     * Returns `null` for a label that carries neither, rather than inventing a value.
     */
    private fun parseDefinitionHeight(label: String): Int? {
        PROGRESSIVE_HEIGHT_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return it }

        // "K" is a *width* class; map it onto the conventional 16:9 height for that class so a
        // 4K rendition is comparable with a 1080p one on the same axis.
        K_CLASS_REGEX.find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { klass ->
            return when (klass) {
                8 -> 4320
                4 -> 2160
                2 -> 1440
                1 -> 720
                else -> null
            }
        }

        return null
    }

    private fun guessSubtitleMimeType(url: String): String? =
        when (url.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "vtt" -> "text/vtt"
            else -> null
        }

    private companion object {
        const val TAG = "P115Media"

        /** `1080P`, `720p`, `2160P`. */
        val PROGRESSIVE_HEIGHT_REGEX = Regex("(\\d{3,4})\\s*[pP]\\b")

        /** `4K`, `8k`, `2K`. */
        val K_CLASS_REGEX = Regex("(\\d{1,2})\\s*[kK]\\b")
    }
}
