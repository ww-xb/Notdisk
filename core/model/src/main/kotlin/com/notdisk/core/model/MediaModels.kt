package com.notdisk.core.model

/**
 * How a media resource should be fed to the player.
 *
 * This is a *protocol* statement, not a vendor statement: the player branches on this enum and
 * never on [ProviderId].
 */
enum class MediaProtocol {
    /** A single progressive HTTP(S) resource (MP4/MKV/MP3 over plain HTTP with Range support). */
    PROGRESSIVE_HTTP,

    /** An HLS multivariant/media playlist (`application/vnd.apple.mpegurl`). */
    HLS,

    /** MPEG-DASH manifest. */
    DASH,

    /** A local `content://` / `file://` URI already downloaded by the app. */
    LOCAL_URI,

    /** The provider hands out an opaque URI scheme that only Media3 can resolve. */
    OTHER,
}

/**
 * A resolved, playable media resource.
 *
 * ### What this type is NOT allowed to contain
 *  - a provider instance, a vendor SDK object, or a vendor DTO;
 *  - a *file id* used as a media URL (see `docs/architecture.md` §identity vs. access);
 *  - a token belonging to the account session.
 *
 * It contains a **short-lived signed URL plus the headers required to fetch it**, which is a
 * different class of secret from the account credential. Therefore:
 *  - it is never persisted;
 *  - it is never logged verbatim (use `LogRedaction.redactUrl`);
 *  - it carries [expiresAt] so callers can refresh before use.
 */
data class MediaResource(
    /** The playable location. Signed query parameters live here. Treat as a secret. */
    val url: String,
    val protocol: MediaProtocol,
    /** MIME type of the media, when known. */
    val mimeType: String? = null,
    /**
     * Request headers required to fetch the resource.
     * Typical values: `Referer`, `User-Agent`, `Origin`. Never account credentials.
     */
    val headers: Map<String, String> = emptyMap(),
    /** Epoch millis after which [url] is expected to stop working. `null` = no stated expiry. */
    val expiresAt: Long? = null,
    /** True when the app must re-resolve (rather than reuse) the URL after expiry. */
    val refreshable: Boolean = false,
    /** What the client observed about byte-range support; `null` = not determined. */
    val supportsRangeRequests: Boolean? = null,
    /** Duration in milliseconds when the provider already knows it. */
    val durationMs: Long? = null,
    /** Video dimensions when reported. */
    val width: Int? = null,
    val height: Int? = null,
    /** Externally supplied subtitle tracks. 115 returns sidecar `.srt` URLs. */
    val subtitles: List<MediaSubtitleTrack> = emptyList(),
    /** Alternative renditions (different bitrates/definitions) when the vendor exposes them. */
    val renditions: List<MediaRendition> = emptyList(),
    /** Audio tracks for vendors that deliver separated audio playlists (115 does). */
    val audioTracks: List<MediaAudioTrack> = emptyList(),
    /** Opaque, provider-owned locator used to *re-resolve* this resource. Never a secret. */
    val refreshToken: MediaRefreshToken? = null,
) {
    /**
     * True when the resource is at (or past) its expiry.
     *
     * [nowMillis] is injectable on purpose: expiry is the single most timing-sensitive part of
     * playback, and a method that silently reads the system clock cannot be tested against a
     * virtual clock. Production callers use the default; the coordinator and tests pass their own.
     *
     * @param skewMillis treat the resource as expired this many milliseconds *before* it really
     *   is. Use a positive skew to refresh proactively rather than mid-stream.
     */
    fun isExpired(skewMillis: Long = 0L, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiresAt ?: return false
        return nowMillis + skewMillis >= expiry
    }

    init {
        require(url.isNotBlank()) { "MediaResource.url must not be blank" }
    }
}

/**
 * Everything the resource coordinator needs to ask a provider for a *fresh* [MediaResource].
 *
 * It carries only stable identifiers (provider + account + file), never a signed URL, so it is
 * safe to keep for the lifetime of a playback session.
 */
data class MediaRefreshToken(
    val key: CloudFileKey,
    /** Provider-specific hint, e.g. a preferred definition. Stable, non-secret. */
    val qualityHint: String? = null,
)

data class MediaSubtitleTrack(
    /** Signed, short-lived subtitle URL. Secret; never persisted or logged verbatim. */
    val url: String,
    val label: String,
    val language: String? = null,
    val mimeType: String? = null,
)

/**
 * A selectable video rendition.
 * [id] is a stable, non-secret handle the coordinator passes back in [MediaRefreshToken.qualityHint].
 */
data class MediaRendition(
    val id: String,
    val label: String,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

data class MediaAudioTrack(
    val id: String,
    val label: String,
    val language: String? = null,
)
