package com.notdisk.core.model

/**
 * Declares what a provider *actually* supports, so the UI can hide impossible entry points.
 *
 * ### Rules
 *  1. A capability is only `SUPPORTED` when it has been verified against the real API (a
 *     documented, observed response), not merely because the shared interface has the method.
 *  2. Anything not verified is [CapabilitySupport.UNVERIFIED] or [CapabilitySupport.UNSUPPORTED].
 *     The UI must treat [CapabilitySupport.UNVERIFIED] conservatively (hide the entry point,
 *     or show it disabled with an explanation) rather than assuming it works.
 *  3. Capabilities are *hints*. An operation can still fail at runtime (permission changes,
 *     server-side policy, file state). Providers must still return honest errors.
 *
 * The shared provider contract test asserts that a provider never reports `SUPPORTED` for a
 * capability whose method returns `CloudError.Unsupported` for every input, and vice versa.
 */
enum class CapabilitySupport {
    /** Verified against the live API. */
    SUPPORTED,

    /** The vendor documents it, but this build has not verified it end-to-end. */
    UNVERIFIED,

    /** The vendor (or this implementation) does not offer it. */
    UNSUPPORTED,
    ;

    val isSupported: Boolean get() = this == SUPPORTED
}

/** Capabilities of the file-system surface. */
data class FileSystemCapabilities(
    val listChildren: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val getFile: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val getPath: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val search: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the vendor's list endpoint returns a real breadcrumb chain. */
    val serverSideBreadcrumb: Boolean = false,
    /** How the vendor paginates; drives whether the UI can deep-link into page N. */
    val paging: PagingModel = PagingModel.OFFSET_LIMIT,
    /** Sort fields the vendor can apply server-side. Others must be sorted client-side. */
    val serverSideSortFields: Set<FileSortField> = emptySet(),
    val categoryFilter: CapabilitySupport = CapabilitySupport.UNVERIFIED,
    val suffixFilter: CapabilitySupport = CapabilitySupport.UNVERIFIED,
)

/** How a provider expresses "the next page". */
enum class PagingModel {
    /** `offset` + `limit`, with a total count (115's `/files`). */
    OFFSET_LIMIT,

    /** An opaque cursor returned by the previous page. */
    CURSOR,

    /** No pagination; the vendor returns everything at once. */
    NONE,
}

/** Capabilities of the mutating surface. */
data class FileOperationCapabilities(
    val createFolder: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val rename: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val move: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val copy: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val delete: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val batchOperations: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val share: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val upload: UploadCapabilities = UploadCapabilities.Unsupported,
    val download: DownloadCapabilities = DownloadCapabilities.Unsupported,
    val trash: TrashCapabilities = TrashCapabilities.Unsupported,
    /**
     * True when a write request is safe to retry blindly after a timeout.
     * `false` (the honest default for most vendors) means the transport must NOT auto-retry a
     * mutation; the caller must re-read state and reconcile.
     */
    val idempotentWrites: Boolean = false,
)

data class UploadCapabilities(
    val supported: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val maxFileSizeBytes: Long? = null,
    /** Single-request vs. multipart/OSS-direct upload. */
    val maxSingleRequestBytes: Long? = null,
    val resumable: Boolean = false,
    val serverSideDuplicateDetection: Boolean = false,
) {
    companion object {
        val Unsupported = UploadCapabilities()
    }
}

data class DownloadCapabilities(
    val supported: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the vendor hands out a direct HTTP(S) link the app can fetch itself. */
    val directLink: Boolean = false,
    /** True when the direct link is time-limited and must be re-resolved before each use. */
    val directLinkExpires: Boolean = false,
    val folderArchive: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
) {
    companion object {
        val Unsupported = DownloadCapabilities()
    }
}

data class TrashCapabilities(
    val supported: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val list: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val restore: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** Permanent deletion. 115 requires the account's safe key for this. */
    val purge: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val purgeRequiresSecret: Boolean = false,
) {
    companion object {
        val Unsupported = TrashCapabilities()
    }
}

/** Capabilities of the media surface. */
data class MediaCapabilities(
    /** Can the provider produce a playable URL for video files? */
    val videoStreaming: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val audioStreaming: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** Media protocols this provider can emit. */
    val protocols: Set<MediaProtocol> = emptySet(),
    /** `true` = verified that the resolved URL honours `Range:`; `null` = not determined. */
    val rangeRequests: Boolean? = null,
    /** True when resolved URLs carry an expiry that forces periodic refresh. */
    val urlExpires: Boolean = false,
    /** True when the coordinator may re-resolve an expired URL. */
    val urlRefresh: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the vendor delivers sidecar subtitles. */
    val subtitles: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the vendor exposes multiple video renditions. */
    val multipleRenditions: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the vendor separates audio into its own playlist (115's transcoder does). */
    val separateAudioTracks: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the vendor can report/resume playback position. */
    val playbackProgress: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /**
     * True when media URLs require headers beyond a bare GET.
     * 115's HLS output is fetched with `Referer`/`Origin`/`User-Agent`.
     */
    val requiresRequestHeaders: Boolean = false,
)

/** Capabilities of the preview surface. */
data class PreviewCapabilities(
    val image: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val thumbnail: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val text: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val pdf: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val officeDocument: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** MIME types / extensions the provider explicitly knows how to preview. */
    val supportedExtensions: Set<String> = emptySet(),
)

/** Capabilities of the account surface. */
data class AccountCapabilities(
    /** Interactive flows the provider can run in this build. */
    val interactiveFlows: Set<AuthFlowKind> = emptySet(),
    /** True when the provider can silently renew a credential without user interaction. */
    val silentRefresh: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val multipleAccounts: Boolean = true,
    val logout: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** True when the provider reports storage usage. */
    val storageQuota: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
)

/**
 * Interactive authorisation flows a provider may offer.
 * A provider advertises the flow(s) it can actually run; the accounts UI renders a button per
 * advertised flow and nothing else.
 */
enum class AuthFlowKind {
    /** Scan a QR code rendered by the app. No developer credentials required. (115 supports this.) */
    QR_CODE,

    /** Account + password typed in the app. (115 supports this; discouraged.) */
    PASSWORD,

    /** Redirect-based OAuth 2.0 / PKCE. Requires a registered client id + redirect URI. */
    OAUTH_REDIRECT,

    /** Device authorisation grant (user enters a code on a vendor page). */
    DEVICE_CODE,

    /** A pasted token/cookie, for vendors whose only documented path is manual. */
    MANUAL_CREDENTIAL,
}

/**
 * The complete capability declaration for one provider.
 *
 * The `authoredAt`/`verifiedAt` fields exist so the accounts/settings UI can be honest with the
 * user about which parts of an integration have actually been exercised against the live API.
 */
data class CloudCapabilities(
    val providerId: ProviderId,
    val account: AccountCapabilities = AccountCapabilities(),
    val fileSystem: FileSystemCapabilities = FileSystemCapabilities(),
    val operations: FileOperationCapabilities = FileOperationCapabilities(),
    val media: MediaCapabilities = MediaCapabilities(),
    val preview: PreviewCapabilities = PreviewCapabilities(),
) {
    /**
     * `true` when at least one core browsing capability is verified. Used by the UI to warn
     * before the user binds an account to a half-verified integration.
     */
    val hasVerifiedBrowsing: Boolean
        get() = fileSystem.listChildren.isSupported && fileSystem.getFile.isSupported
}
