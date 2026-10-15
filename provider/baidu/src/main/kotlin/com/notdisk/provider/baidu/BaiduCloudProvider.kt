package com.notdisk.provider.baidu

import com.notdisk.core.common.logging.CloudLogger
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
import com.notdisk.provider.baidu.api.BaiduHttpClient
import com.notdisk.provider.baidu.auth.BaiduAccountMetadataStore
import com.notdisk.provider.baidu.auth.BaiduAuthProvider
import com.notdisk.provider.baidu.auth.BaiduCredentialStore
import com.notdisk.provider.baidu.auth.InMemoryBaiduAccountMetadataStore
import com.notdisk.provider.baidu.auth.InMemoryBaiduCredentialStore

/**
 * The Baidu Netdisk provider.
 *
 * ### Capability declarations are the point of this class
 * The project is emphatic that a provider must not claim a capability merely because the shared
 * interface has the method, and that an unsupported feature must return a clear result. Every flag
 * below is therefore traceable to one of exactly three states, using the reference material's own
 * evidence markers (§`BaiduVerification`):
 *
 *  - **SUPPORTED** — the endpoint is measured (`✅`) *and* this build has a complete
 *    request/response/error implementation for it.
 *  - **UNVERIFIED** — the endpoint is implied by the vendor's bundle but never measured (`⚠️`), or it
 *    is documented but cannot be driven by this build. The method exists and returns a typed
 *    `Unsupported` with an explanation, because acting on an unmeasured protocol against a real
 *    account risks the user's data.
 *  - **UNSUPPORTED** — the vendor has no such endpoint, or this build deliberately does not offer it.
 *
 * ### The deliberate refusals, and why each one is a boundary rather than an omission
 * | Capability | State | Reason |
 * |---|---|---|
 * | `rename` | SUPPORTED | §3.6 — the **only** `opera` value with a complete URL literal in the vendor's bundle. |
 * | `move`/`copy`/`delete` | UNVERIFIED | §3.6 ⚠️ — their `opera` values are inferred from `actionType` pass-throughs. The code is complete; the *evidence* is not. |
 * | `upload` | UNVERIFIED | §4 documents the protocol fully, but it writes to a real account and the chunk target comes from a 60-second host pool. |
 * | `download` | UNSUPPORTED | §5.2 — the `dlink` signature requires executing **JavaScript the server sends at runtime**. Kotlin cannot, without an embedded engine. |
 * | `share` | UNVERIFIED | §6.1 — the measured response demands a phone-bound account, and the **success** shape was never observed. |
 * | `media.playbackProgress` | UNVERIFIED | §7 — progress is kept in the vendor's `localStorage`; no endpoint is documented. |
 * | `separateAudioTracks` | UNSUPPORTED | §7.6 — audio uses the `dlink` path, and the track-naming convention is undocumented for Baidu. |
 *
 * ### Pagination model
 * §3.2 documents `page` + `num`. `PagingModel.OFFSET_LIMIT` is declared because the unified cursor is
 * a page number and "load more" is therefore the correct affordance. Note the vendor returns **no**
 * "has more" field, so the end of a listing is decided by the returned page's length — a rule the
 * shared contract suite verifies cannot loop.
 *
 * ### Retry safety
 * `idempotentWrites = false`. This is not conservatism: §3.6 documents that `copy` defaults to
 * `ondup: newcopy`, so a transport-level retry after a timeout creates a **second copy** rather than
 * failing.
 */
class BaiduCloudProvider(
    configuration: BaiduConfiguration = BaiduConfiguration(),
    logger: CloudLogger,
    credentialStore: BaiduCredentialStore = InMemoryBaiduCredentialStore(),
    accountMetadataStore: BaiduAccountMetadataStore = InMemoryBaiduAccountMetadataStore(),
    /**
     * Host overrides for tests, keyed by the canonical host constant. Production passes nothing and
     * the real hosts in [com.notdisk.provider.baidu.api.BaiduHosts] are used.
     */
    hostOverrides: Map<String, String> = emptyMap(),
    okHttpClient: okhttp3.OkHttpClient? = null,
    clock: () -> Long = System::currentTimeMillis,
) : CloudProvider {

    override val id: ProviderId = BaiduProviderId

    override val displayName: String = BAIDU_DISPLAY_NAME

    private val http: BaiduHttpClient = BaiduHttpClient(
        logger = logger,
        baseOverride = hostOverrides,
        okHttpClient = okHttpClient,
    )

    private val authProvider = BaiduAuthProvider(
        http = http,
        credentials = credentialStore,
        accounts = accountMetadataStore,
        logger = logger,
        clock = clock,
    )

    /**
     * Every data-facing provider resolves the session through the auth provider.
     *
     * This is the only place a credential is turned into a `Cookie` header, which keeps the "one owner
     * per secret" property: no provider builds its own authentication.
     */
    private val sessionProvider: suspend (AccountId) -> com.notdisk.core.common.result.CloudResult<com.notdisk.provider.baidu.auth.BaiduSession> =
        { accountId -> authProvider.session(accountId) }

    private val fileSystemProvider = BaiduFileSystemProvider(
        http = http,
        sessionProvider = sessionProvider,
        configuration = configuration,
        logger = logger,
    )

    private val operationProvider = BaiduFileOperationProvider(
        http = http,
        sessionProvider = sessionProvider,
        authProvider = authProvider,
        logger = logger,
        clock = clock,
    )

    private val mediaProvider = BaiduMediaProvider(
        http = http,
        sessionProvider = sessionProvider,
        logger = logger,
    )

    private val previewProvider = BaiduPreviewProvider(
        http = http,
        sessionProvider = sessionProvider,
        logger = logger,
    )

    override val auth: AuthProvider get() = authProvider
    override val files: FileSystemProvider get() = fileSystemProvider
    override val operations: FileOperationProvider get() = operationProvider
    override val media: MediaProvider get() = mediaProvider
    override val preview: PreviewProvider get() = previewProvider

    override fun capabilities(): CloudCapabilities = CloudCapabilities(
        providerId = id,

        account = AccountCapabilities(
            // Exactly one flow, and it needs no developer credential at all: the user pastes their own
            // browser session cookie (§1.5). Baidu's own login chain is refused because its
            // credential-producing step needs a `sig` computed by Passport's private front-end
            // JavaScript (§2.2 step 7), which the reference material says would have to be reverse
            // engineered. Advertising a flow that cannot reach its final step is exactly what §18
            // forbids, so it is not advertised.
            interactiveFlows = setOf(AuthFlowKind.MANUAL_CREDENTIAL),
            // Baidu documents no silent refresh: `BDUSS` is replaced by the user re-authenticating.
            silentRefresh = CapabilitySupport.UNSUPPORTED,
            // Credentials are per-login, so several accounts can be bound independently.
            multipleAccounts = true,
            logout = CapabilitySupport.SUPPORTED,
            // §9.2 ✅ `total`/`used`.
            storageQuota = CapabilitySupport.SUPPORTED,
        ),

        fileSystem = FileSystemCapabilities(
            // §3.2 ✅, §3.5 ✅, §3.4 ✅.
            listChildren = CapabilitySupport.SUPPORTED,
            getFile = CapabilitySupport.SUPPORTED,
            // Exact and free for this provider: the id *is* the absolute path.
            getPath = CapabilitySupport.SUPPORTED,
            search = CapabilitySupport.SUPPORTED,
            // §3.2's listing response carries no breadcrumb array (unlike 115's `path`), so this is
            // false and the UI builds the chain from `getPath`, which is exact here.
            serverSideBreadcrumb = false,
            // §3.2 ✅ — `page` + `num`, no cursor.
            paging = PagingModel.OFFSET_LIMIT,
            // §3.2 documents exactly three sort keys (`name`/`time`/`size`). Declaring only those is
            // what tells the UI that a sort by type or created-time needs a local re-sort.
            serverSideSortFields = setOf(
                FileSortField.NAME,
                FileSortField.MODIFIED_TIME,
                FileSortField.SIZE,
            ),
            // §3.3 ✅ — `category` (1/2/3/4/6/7/all). Note the mapping is *not* the identity: the
            // vendor has no archive bucket, so `ARCHIVE` maps onto "其他" (6) and `SOFTWARE` onto 7.
            categoryFilter = CapabilitySupport.SUPPORTED,
            // §3.2 documents no suffix parameter, and `/api/search` takes only `key`. Declaring this
            // honestly keeps the UI from offering a filter the vendor would ignore.
            suffixFilter = CapabilitySupport.UNSUPPORTED,
        ),

        operations = FileOperationCapabilities(
            // §3.7 ✅ — `/api/create?a=commit` with a measured response shape.
            createFolder = CapabilitySupport.SUPPORTED,
            // §3.6 ✅ — `opera=rename` has a complete URL literal in the vendor's own bundle.
            rename = CapabilitySupport.SUPPORTED,
            // §3.6 ⚠️ — `move`/`copy`/`delete` are inferred from `actionType` pass-throughs. The
            // implementation is complete and tested against the documented request shape; what is
            // missing is *evidence* that the vendor accepts these exact `opera` strings, so the honest
            // declaration is UNVERIFIED rather than SUPPORTED.
            move = CapabilitySupport.UNVERIFIED,
            copy = CapabilitySupport.UNVERIFIED,
            delete = CapabilitySupport.UNVERIFIED,
            batchOperations = CapabilitySupport.UNVERIFIED,

            // §6.1 — see the class KDoc. Creating a public link on a protocol whose success shape was
            // never observed is the highest-risk action available.
            share = CapabilitySupport.UNVERIFIED,

            upload = UploadCapabilities(
                // §4 documents the protocol completely, but it writes to a real account and its chunk
                // target comes from a host pool with a 60-second lifetime (§4.2).
                supported = CapabilitySupport.UNVERIFIED,
                // §4.1.2 documents a 20 GiB single-file limit.
                maxFileSizeBytes = 20L * 1024 * 1024 * 1024,
                // §4.1.2: chunks are 4 MiB, so nothing is uploaded in a single request.
                maxSingleRequestBytes = null,
                // §4.1's chunking is documented with three retries, but resumability across a process
                // restart is not documented, so it is not claimed.
                resumable = false,
                // §4.1's `rapidupload` is server-side deduplication by content MD5, which *is*
                // documented — but it only runs inside the upload flow this build does not perform.
                serverSideDuplicateDetection = false,
            ),

            download = DownloadCapabilities(
                // §5.2 — the signature needs `new Function("return " + sign2)()`, i.e. executing
                // JavaScript the server supplies at runtime. This is a hard boundary, not caution.
                supported = CapabilitySupport.UNSUPPORTED,
                directLink = false,
                directLinkExpires = false,
                // §5.5 lists packaging endpoints, but they need a `dlink` to retrieve the result.
                folderArchive = CapabilitySupport.UNSUPPORTED,
            ),

            trash = TrashCapabilities(
                // §3.10 ✅ — all four operations have source literals and the list endpoint was
                // measured. Restore/purge reuse the same `fidlist` shape as the measured operations.
                supported = CapabilitySupport.SUPPORTED,
                list = CapabilitySupport.SUPPORTED,
                restore = CapabilitySupport.SUPPORTED,
                purge = CapabilitySupport.SUPPORTED,
                // Baidu's recycle bin has no password (unlike 115's `rb_pass`), which §3.10's
                // documented parameter set confirms by omitting it.
                purgeRequiresSecret = false,
            ),

            // §3.6's `copy` defaults to `ondup: newcopy`, so a retry creates a duplicate.
            idempotentWrites = false,
        ),

        media = MediaCapabilities(
            // §7.3 ✅ — both phases of `/api/streaming` were measured against a live account.
            videoStreaming = CapabilitySupport.SUPPORTED,
            // §7.6 documents that audio has **no** streaming endpoint: it uses the `dlink` path, which
            // this build cannot drive. Claiming audio streaming would be false.
            audioStreaming = CapabilitySupport.UNSUPPORTED,
            // §7.3's phase two answers `application/x-mpegURL`; §7.4 confirms the segments are
            // range-sliced pieces of one container.
            protocols = setOf(MediaProtocol.HLS),
            // §7.4 measured `Range: bytes=0-1023` → `HTTP 206` with `Accept-Ranges: bytes`.
            rangeRequests = true,
            // The playlist URL is the API call itself, so it is not a *signed* URL with a lifetime —
            // re-issuing it re-derives everything. `refreshable` carries that contract instead.
            urlExpires = false,
            urlRefresh = CapabilitySupport.SUPPORTED,
            // §7.5 ✅ — the subtitle *list* endpoint (`type=M3U8_SUBTITLE_SRT`) was measured, but
            // consuming it means parsing an HLS `#EXT-X-MEDIA` manifest and mapping its tracks onto
            // the unified model, which this build does not do. Declared UNVERIFIED rather than
            // SUPPORTED so the UI does not offer a subtitle menu that returns nothing.
            subtitles = CapabilitySupport.UNVERIFIED,
            // §7.3.1 documents the rendition ladder (1080/720/480/360).
            multipleRenditions = CapabilitySupport.SUPPORTED,
            // §7.6: audio is a separate dlink path, and the video transcoder's `multitrack_list`
            // naming convention is undocumented for Baidu.
            separateAudioTracks = CapabilitySupport.UNSUPPORTED,
            // §7 — progress lives in the vendor's `localStorage`; no report endpoint is documented.
            playbackProgress = CapabilitySupport.UNVERIFIED,
            // §7.4 measured that segment URLs are self-signed and need **no** Cookie/UA/Referer, so
            // unlike 115 this is false. It matters: it is what stops the player being handed the
            // account credential.
            requiresRequestHeaders = false,
        ),

        preview = PreviewCapabilities(
            // §8.2 ✅ — thumbnail URLs arrive inside listing responses, and this build reads the
            // descriptor from them. §8.5 ✅ — `picdocpreview` was measured and is used for text.
            image = CapabilitySupport.SUPPORTED,
            thumbnail = CapabilitySupport.SUPPORTED,
            text = CapabilitySupport.SUPPORTED,
            // §8.1's registry routes PDF to `pdf-plugin`, i.e. the platform's own reader. The file is
            // correctly classified; producing the bytes needs a `dlink`, which this build cannot sign.
            pdf = CapabilitySupport.UNVERIFIED,
            // §8.4 measured that `/disk/docview` redirects into a **separate OAuth application**
            // (`client_id=9dgBV9yesuBVOXaxls7aVHbLBLqU8yyg`). Driving it means a WebView consent flow
            // this build cannot complete, so no viewer is offered.
            officeDocument = CapabilitySupport.UNVERIFIED,
            supportedExtensions = BaiduFileMapper.PREVIEWABLE_EXTENSIONS,
        ),
    )

    companion object {
        /**
         * A provider wired entirely in memory.
         *
         * Exists so tests and tooling can construct one without an Android `Context`. It is **not**
         * used by the production dependency graph — see `BaiduProviderModule`, which supplies the
         * Keystore-backed store.
         */
        fun inMemory(
            logger: CloudLogger,
            hostOverrides: Map<String, String> = emptyMap(),
            clock: () -> Long = System::currentTimeMillis,
        ): BaiduCloudProvider = BaiduCloudProvider(
            configuration = BaiduConfiguration(),
            logger = logger,
            credentialStore = InMemoryBaiduCredentialStore(),
            accountMetadataStore = InMemoryBaiduAccountMetadataStore(),
            hostOverrides = hostOverrides,
            clock = clock,
        )
    }
}
