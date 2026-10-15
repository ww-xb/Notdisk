package com.notdisk.core.model

/**
 * The single, closed set of ways the app can present a file to the user.
 *
 * Every provider classifies its files into one of these. The UI renders one branch per case and
 * never constructs a vendor URL itself.
 */
sealed interface PreviewKind {

    /** Inline image, resolved through a provider URL at display time. */
    data class Image(
        val thumbnail: ThumbnailRef? = null,
        /** True when the provider can deliver the untouched original. */
        val originalAvailable: Boolean = true,
    ) : PreviewKind

    /** Delegate to the shared media player (see [MediaResource]). */
    data object Video : PreviewKind

    /** Delegate to the shared media player with an audio-only surface. */
    data object Audio : PreviewKind

    /** Render text content. Either fetched inline or streamed from a provider URL. */
    data class Text(
        val encoding: String = "UTF-8",
        /** Characters above which the UI must switch to paged/streamed rendering. */
        val inlineLimitChars: Int = 256 * 1024,
    ) : PreviewKind

    /** Hand off to the platform PDF viewer. */
    data object Pdf : PreviewKind

    /**
     * Office documents.
     *  - 115 returns a WPS cloud-document URL plus a token — the app opens it in a WebView.
     *  - Providers without an online viewer return `null` from `PreviewProvider` instead.
     */
    data class OfficeDocument(
        val viewer: OfficeViewer,
    ) : PreviewKind

    /** Thumbnail only; no inline full-content preview. */
    data class ThumbnailOnly(val thumbnail: ThumbnailRef?) : PreviewKind

    /** No native preview. The UI must offer download / external open. */
    data class Unsupported(val reason: String) : PreviewKind
}

/**
 * An online office-document viewer handoff.
 *
 * [url] and [token] are short-lived signed material: they are resolved on demand, never
 * persisted, and never logged.
 */
data class OfficeViewer(
    val url: String,
    val token: String?,
    val expiresAt: Long? = null,
    /** Vendor's own suggestion for how long before expiry a refresh should happen. */
    val refreshBeforeSeconds: Long? = null,
    val canEdit: Boolean = false,
)

/**
 * The result of asking a provider "how do I show this file?".
 *
 * A provider must return exactly one of:
 *  - a [PreviewKind.Image]/[PreviewKind.Text]/[PreviewKind.OfficeDocument]/… **with** the
 *    resource access information the UI needs ([PreviewResult.resource]);
 *  - [PreviewKind.Unsupported] when it genuinely cannot preview;
 *  - a `CloudError.Unsupported` failure when the *provider* has no preview capability at all.
 *
 * The distinction matters: "this provider cannot preview anything" is a capability failure,
 * whereas "this provider can preview, but not this .psd file" is a successful call returning
 * [PreviewKind.Unsupported].
 */
data class PreviewResult(
    val key: CloudFileKey,
    val kind: PreviewKind,
    val mimeType: String? = null,
    /**
     * Access information for the generic cases (image bytes, text bytes).
     * Signed and short-lived; never persisted.
     */
    val resource: PreviewResource? = null,
    /** Vendor-reported expiry for [resource]. */
    val expiresAt: Long? = null,
    val refreshable: Boolean = false,
)

/**
 * Where the preview renderer should fetch content from.
 *
 * Kept separate from [PreviewKind] because the *kind* is stable while the *access* expires.
 */
data class PreviewResource(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
) {
    init {
        require(url.isNotBlank()) { "PreviewResource.url must not be blank" }
    }
}
