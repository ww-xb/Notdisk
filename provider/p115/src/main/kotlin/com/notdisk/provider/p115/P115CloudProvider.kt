package com.notdisk.provider.p115

import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountCapabilities
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.DownloadCapabilities
import com.notdisk.core.model.FileOperationCapabilities
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileSystemCapabilities
import com.notdisk.core.model.MediaCapabilities
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.PagingModel
import com.notdisk.core.model.PreviewCapabilities
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.TrashCapabilities
import com.notdisk.core.model.UploadCapabilities
import com.notdisk.core.provider.AuthProvider
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.FileSystemProvider
import com.notdisk.core.provider.MediaProvider
import com.notdisk.core.provider.PreviewProvider
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.auth.InMemoryP115AccountMetadataStore
import com.notdisk.provider.p115.auth.InMemoryP115CredentialStore
import com.notdisk.provider.p115.auth.P115AccountMetadataStore
import com.notdisk.provider.p115.auth.P115AuthProvider
import com.notdisk.provider.p115.auth.P115CredentialStore
import com.notdisk.provider.p115.auth.P115Session

/**
 * The 115 cloud provider.
 *
 * ### Capability declarations are the point of this class
 * The brief is emphatic that a provider must not claim a capability merely because the shared
 * interface has the method, and that "不支持的功能能返回明确结果". Every flag below is therefore
 * traceable to one of exactly three states, and the mapping is documented in `docs/capabilities.md`:
 *
 *  - **SUPPORTED** — the reference manual marks the endpoint `✅ 实测` *and* this build has a
 *    complete request/response/error implementation for it.
 *  - **UNVERIFIED** — the endpoint is documented `📖 源码` (source-derived only) or the manual's
 *    §15.4 lists it as not yet verified. The method exists and returns a typed `Unsupported` with an
 *    explanation, because acting on an unverified protocol against a real account risks corrupting
 *    user data (a duplicate file, a public share).
 *  - **UNSUPPORTED** — the vendor has no such endpoint, or this build deliberately does not offer it.
 *
 * ### Deliberate refusals, and why
 * | Capability        | State      | Reason                                                     |
 * |-------------------|------------|------------------------------------------------------------|
 * | `upload`          | UNVERIFIED | §6's OSS-direct protocol is `📖`; §15.4 confirms the callback was never exercised. |
 * | `share`           | UNVERIFIED | §12.2's create endpoint is `📖`. Publishing a file to a URL on an unverified protocol is the highest-risk action available. |
 * | `delete.permanent`| SUPPORTED via trash only | §8.4: permanent deletion is `rb/clean`, needs a safe key. Plain `delete` never silently permanently deletes. |
 * | `media.playbackProgress` | UNVERIFIED | §10.3: the progress endpoint is not located; §15.4 lists it as unknown. |
 * | `folderArchive`   | UNSUPPORTED | §7.2 documents a page-level endpoint, not an API this client can drive. |
 *
 * ### Pagination model
 * §3.1 documents `offset` + `limit`. `PagingModel.OFFSET_LIMIT` is declared so the UI knows a page
 * number is meaningful and that "load more" is the correct affordance.
 *
 * ### Retry safety
 * `idempotentWrites = false`. This is not conservatism: §5.4's `files/copy` is genuinely
 * non-idempotent, so a transport-level retry after a timeout would create a second copy.
 */
class P115CloudProvider(
    configuration: P115Configuration = P115Configuration(),
    logger: CloudLogger,
    credentialStore: P115CredentialStore = InMemoryP115CredentialStore(),
    accountMetadataStore: P115AccountMetadataStore = InMemoryP115AccountMetadataStore(),
    /**
     * Host overrides for tests, keyed by the canonical host constant. Production passes nothing and
     * the real hosts in [com.notdisk.provider.p115.api.P115Hosts] are used.
     */
    hostOverrides: Map<String, String> = emptyMap(),
    okHttpClient: okhttp3.OkHttpClient? = null,
    clock: () -> Long = System::currentTimeMillis,
) : CloudProvider {

    override val id: ProviderId = P115ProviderId

    override val displayName: String = P115_DISPLAY_NAME

    private val http: P115HttpClient = P115HttpClient(
        logger = logger,
        baseOverride = hostOverrides,
        okHttpClient = okHttpClient,
    )

    private val authProvider = P115AuthProvider(
        http = http,
        credentials = credentialStore,
        accounts = accountMetadataStore,
        configuration = configuration,
        logger = logger,
        clock = clock,
    )

    /**
     * Every data-facing provider resolves the session through the auth provider.
     *
     * This is the only place a credential is turned into a `Cookie` header, which keeps the "one
     * owner per secret" property: no provider builds its own authentication.
     */
    private val sessionProvider: suspend (AccountId) -> CloudResult<P115Session> = { accountId ->
        authProvider.session(accountId)
    }

    /**
     * One pickcode cache for the whole provider.
     *
     * Sharing it is what makes the "listing a folder makes playback a single request" property hold:
     * the file-system provider fills it while the user browses, and the media/preview/operation
     * providers read it back. A per-provider cache would throw that away and add a request to every
     * playback, preview and download.
     */
    private val pickcodeCache = P115PickcodeCache()

    private val pickcodeResolver = P115PickcodeResolver(http, pickcodeCache)

    private val fileSystemProvider = P115FileSystemProvider(
        http = http,
        sessionProvider = sessionProvider,
        configuration = configuration,
        logger = logger,
        pickcodes = pickcodeCache,
    )

    private val operationProvider = P115FileOperationProvider(
        http = http,
        sessionProvider = sessionProvider,
        pickcodes = pickcodeResolver,
        logger = logger,
        clock = clock,
    )

    private val mediaProvider = P115MediaProvider(
        http = http,
        sessionProvider = sessionProvider,
        pickcodes = pickcodeResolver,
        logger = logger,
    )

    private val previewProvider = P115PreviewProvider(
        http = http,
        sessionProvider = sessionProvider,
        pickcodes = pickcodeResolver,
        logger = logger,
        clock = clock,
    )

    override val auth: AuthProvider get() = authProvider
    override val files: FileSystemProvider get() = fileSystemProvider
    override val operations: FileOperationProvider get() = operationProvider
    override val media: MediaProvider get() = mediaProvider
    override val preview: PreviewProvider get() = previewProvider

    /** Exposed so the accounts UI can offer the safe-key prompt only when it is actually required. */
    suspend fun isSafeKeySet(accountId: AccountId): CloudResult<Boolean> =
        authProvider.isSafeKeySet(accountId)

    override fun capabilities(): CloudCapabilities = CloudCapabilities(
        providerId = id,

        account = AccountCapabilities(
            // Two flows this build runs end-to-end, and neither needs developer credentials:
            //  - QR_CODE, §2.1's measured scan flow;
            //  - MANUAL_CREDENTIAL, for a user already logged into 115 in a browser who would
            //    rather paste the session cookie than scan. The credential lands in the same
            //    Keystore-backed store, so offering it does not weaken storage security.
            // OAuth is deliberately absent: §2.4 needs an `app_id` this project has not been
            // issued, and advertising a flow that cannot start is exactly what §18 forbids.
            interactiveFlows = setOf(AuthFlowKind.QR_CODE, AuthFlowKind.MANUAL_CREDENTIAL),
            // 115's cookie session has no documented refresh mechanism; `verifyAccount` re-checks it
            // and the user re-scans when it is gone.
            silentRefresh = CapabilitySupport.UNSUPPORTED,
            // §2.1's credential set is per-login, so several accounts can be bound independently.
            multipleAccounts = true,
            logout = CapabilitySupport.SUPPORTED,
            storageQuota = CapabilitySupport.SUPPORTED,
        ),

        fileSystem = FileSystemCapabilities(
            // §3.1, §3.2, §3.5 — all `✅`.
            listChildren = CapabilitySupport.SUPPORTED,
            getFile = CapabilitySupport.SUPPORTED,
            getPath = CapabilitySupport.SUPPORTED,
            // §3.4 `✅`. Note it returns a single page with no cursor, so `search` is not paginated.
            search = CapabilitySupport.SUPPORTED,
            // §3.1's response carries the vendor's own `path` array.
            serverSideBreadcrumb = true,
            paging = PagingModel.OFFSET_LIMIT,
            // §3.1's `o` parameter values.
            serverSideSortFields = setOf(
                FileSortField.NAME,
                FileSortField.MODIFIED_TIME,
                FileSortField.SIZE,
                FileSortField.CREATED_TIME,
                FileSortField.TYPE,
            ),
            // §9.1's `type` parameter (`0`–`7`) maps 1:1 onto the unified FileCategory.
            categoryFilter = CapabilitySupport.SUPPORTED,
            // §3.1's `suffix` parameter.
            suffixFilter = CapabilitySupport.SUPPORTED,
        ),

        operations = FileOperationCapabilities(
            // §5.1 `✅`, §5.2 `✅`, §5.3 `✅`, §5.4 `✅`.
            createFolder = CapabilitySupport.SUPPORTED,
            rename = CapabilitySupport.SUPPORTED,
            move = CapabilitySupport.SUPPORTED,
            copy = CapabilitySupport.SUPPORTED,
            delete = CapabilitySupport.SUPPORTED,
            batchOperations = CapabilitySupport.SUPPORTED,

            // §12.2 is `📖` only. Creating a public link on an unverified protocol is refused.
            share = CapabilitySupport.UNVERIFIED,

            upload = UploadCapabilities(
                // §6 is `📖` and §15.4 confirms the OSS callback was never exercised.
                supported = CapabilitySupport.UNVERIFIED,
                // §6.3: the web client's own limit is 200 MB (`UPLOAD_CONFIG_H5.size_limit`).
                maxFileSizeBytes = 200L * 1024 * 1024,
                maxSingleRequestBytes = 200L * 1024 * 1024,
                // §6.1 describes plupload chunking with `withCredentials: true`; the resumability of
                // that flow is not documented, so it is not claimed.
                resumable = false,
                // §5.9 documents `get_repeat_sha` / `shasearch`, i.e. the vendor does detect
                // duplicates server-side, but the *upload* flow was not verified end to end.
                serverSideDuplicateDetection = false,
            ),

            download = DownloadCapabilities(
                // §7.1 `✅`.
                supported = CapabilitySupport.SUPPORTED,
                directLink = true,
                // §7.1: `file_url` is explicitly "短时效签名链接 … 不要缓存".
                directLinkExpires = true,
                // §7.2 is a page-level endpoint (`?ct=browser&ac=download_folder`), not a JSON API.
                folderArchive = CapabilitySupport.UNSUPPORTED,
            ),

            trash = TrashCapabilities(
                // §8.1 `✅`, §8.3 `✅`, §8.4 `✅`.
                supported = CapabilitySupport.SUPPORTED,
                list = CapabilitySupport.SUPPORTED,
                restore = CapabilitySupport.SUPPORTED,
                purge = CapabilitySupport.SUPPORTED,
                // §8.4: `rb/clean` requires the account's safe key when one is set (`rb_pass`).
                purgeRequiresSecret = true,
            ),

            // §5.4's copy is not idempotent, so nothing in this provider is auto-retried.
            idempotentWrites = false,
        ),

        media = MediaCapabilities(
            // §10.1 `✅` (video metadata) and §10.2 `✅` (the measured CDN redirect chain).
            videoStreaming = CapabilitySupport.SUPPORTED,
            // Audio files have no dedicated documented metadata endpoint in this build; §10.4's
            // endpoints are listed but unverified. Nothing is claimed.
            audioStreaming = CapabilitySupport.UNVERIFIED,
            // §10.2: the measured product is an HLS playlist.
            protocols = setOf(MediaProtocol.HLS),
            // §10.2's URLs carry no documented range semantics, so this stays undetermined rather
            // than asserted. The field is nullable precisely so "unknown" is expressible.
            rangeRequests = null,
            // The CDN segment URLs carry `se`/`ck` parameters whose lifetime is undocumented, but a
            // refresh is always possible by re-requesting `files/video`.
            urlExpires = true,
            urlRefresh = CapabilitySupport.SUPPORTED,
            // §10.1's `subtitle_info` array, measured.
            subtitles = CapabilitySupport.SUPPORTED,
            // §10.1's `definition_list` / `definition_index`, measured.
            multipleRenditions = CapabilitySupport.SUPPORTED,
            // §10.2 item 2: the transcoder emits a separate `audio/{N}.m3u8` per track.
            separateAudioTracks = CapabilitySupport.SUPPORTED,
            // §10.3: the report endpoint was never located; §15.4 lists it as unknown.
            playbackProgress = CapabilitySupport.UNVERIFIED,
            // §10.2 / §1.4: the CDN validates `Referer`/`Origin`/`User-Agent`.
            requiresRequestHeaders = true,
        ),

        preview = PreviewCapabilities(
            // §11.2 `✅` (image + thumbnail), §11.1 `✅` (the WPS document handoff).
            image = CapabilitySupport.SUPPORTED,
            thumbnail = CapabilitySupport.SUPPORTED,
            // §11.3 records that `.txt` also returns a WPS link; this build reads text through the
            // download link instead, which is the manual's own "轻量场景" suggestion.
            text = CapabilitySupport.SUPPORTED,
            // PDF goes through the same WPS path, measured by §11.1's response shape.
            pdf = CapabilitySupport.SUPPORTED,
            officeDocument = CapabilitySupport.SUPPORTED,
            supportedExtensions = P115FileMapper.PREVIEWABLE_EXTENSIONS,
        ),
    )
}
