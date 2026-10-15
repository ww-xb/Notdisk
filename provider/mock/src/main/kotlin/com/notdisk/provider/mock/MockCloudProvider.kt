package com.notdisk.provider.mock

import com.notdisk.core.model.AccountCapabilities
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

/**
 * The offline cloud provider.
 *
 * ### Why it exists (and why it is not "just a stub")
 *  1. It implements the **identical** [CloudProvider] contract as the real 115 integration, and is
 *     held to the same `ProviderContractRules` suite. That is what makes it a usable validator of
 *     the shared abstraction rather than a UI mock.
 *  2. It lets the whole application — accounts, browsing, playback, preview, transfers — be
 *     developed and tested with no network, no account and no rate limits.
 *  3. It is a second, independent implementation. If the shared interfaces only fit 115, this
 *     provider will not compile against them, which surfaces over-fitting early.
 *
 * ### Why it is explicitly marked as development-only
 * [com.notdisk.core.provider.ProviderDescriptor.isDevelopmentOnly] is `true`, so the UI can badge
 * it. Nothing here ever masquerades as a real cloud account: the profile is named "Mock 用户" and
 * the default singleton account is labelled as such.
 */
class MockCloudProvider(
    private val latencyMs: Long = 0,
    private val clock: () -> Long = System::currentTimeMillis,
    /** When `true`, a demo account is created eagerly so the UI has something to browse on first run. */
    seedDemoAccount: Boolean = true,
) : CloudProvider {

    override val id: ProviderId = MOCK_PROVIDER_ID

    override val displayName: String = MOCK_DISPLAY_NAME

    private val accountStore = MockAccountStore()

    /**
     * One overlay per provider instance, shared by the file-system and operation surfaces so that
     * a write is immediately visible to a read. Without the sharing, "create a folder then list it"
     * would silently pass while testing a provider that never actually changes.
     */
    private val sessionState = MockSessionState()

    private val authProvider = MockAuthProvider(accountStore, clock = clock)

    private val fileSystemProvider = MockFileSystemProvider(accountStore, sessionState, latencyMs)

    private val operationProvider = MockFileOperationProvider(accountStore, sessionState, latencyMs, clock)

    private val mediaProvider = MockMediaProvider(accountStore, latencyMs, clock)

    private val previewProvider = MockPreviewProvider(accountStore, latencyMs, clock)

    override val auth: AuthProvider get() = authProvider
    override val files: FileSystemProvider get() = fileSystemProvider
    override val operations: FileOperationProvider get() = operationProvider
    override val media: MediaProvider get() = mediaProvider
    override val preview: PreviewProvider get() = previewProvider

    /** Account created by [seedDemoAccount], for tests and for the first-run UI. */
    @Volatile
    var demoAccountId: com.notdisk.core.model.AccountId? = null
        private set

    init {
        if (seedDemoAccount) {
            demoAccountId = seedAccount()
        }
    }

    /**
     * Creates a Mock account synchronously (without going through the async challenge API).
     *
     * Used for seeding and by tests that need a bound account without exercising the flow.
     */
    fun seedAccount(userName: String = "Mock 用户"): com.notdisk.core.model.AccountId {
        val accountId = com.notdisk.core.model.AccountId("mock-account-seed-${accountStore.all().size + 1}")
        accountStore.insert(
            MockAccountStore.Record(
                accountId = accountId,
                userId = "mock-user-${accountStore.all().size + 1}",
                userName = userName,
                authState = com.notdisk.core.model.AuthState.AUTHORIZED,
                membershipLabel = "Mock 会员",
                addedAt = clock(),
                lastVerifiedAt = clock(),
                sessionToken = "mock-session-token",
            ),
        )
        return accountId
    }

    /** Test hook: forces every stored session to look expired, to exercise re-auth handling. */
    fun invalidateAllSessions() {
        accountStore.invalidateAll()
    }

    /**
     * Test hook: drives an upload/download to completion so its progress flow can be asserted.
     *
     * Exposed deliberately instead of hiding the Mock's transfer engine behind reflection: the
     * "progress stream terminates" contract is important enough to be directly testable.
     */
    suspend fun completeTransfer(transferId: String, steps: Int = 4) {
        operationProvider.completeTransfer(transferId, steps)
    }

    /**
     * The Mock provider's capability declaration.
     *
     * Note the deliberate gaps, which keep the UI honest offline:
     *  - `share` is UNVERIFIED even though `MockFileOperationProvider.share` returns a value, because
     *    the *real* contract for 115's share endpoint is unverified and the Mock must not imply
     *    otherwise;
     *  - `playbackProgress` is UNSUPPORTED, mirroring the 115 gap;
     *  - `operations.idempotentWrites` is `false`: a retried mutation is never automatically safe.
     */
    override fun capabilities(): CloudCapabilities = CloudCapabilities(
        providerId = id,
        account = AccountCapabilities(
            interactiveFlows = MOCK_AUTH_FLOWS,
            silentRefresh = CapabilitySupport.UNSUPPORTED,
            multipleAccounts = true,
            logout = CapabilitySupport.SUPPORTED,
            storageQuota = CapabilitySupport.SUPPORTED,
        ),
        fileSystem = FileSystemCapabilities(
            listChildren = CapabilitySupport.SUPPORTED,
            getFile = CapabilitySupport.SUPPORTED,
            getPath = CapabilitySupport.SUPPORTED,
            search = CapabilitySupport.SUPPORTED,
            serverSideBreadcrumb = true,
            paging = PagingModel.OFFSET_LIMIT,
            serverSideSortFields = FileSortField.entries.toSet(),
            categoryFilter = CapabilitySupport.SUPPORTED,
            suffixFilter = CapabilitySupport.SUPPORTED,
        ),
        operations = FileOperationCapabilities(
            createFolder = CapabilitySupport.SUPPORTED,
            rename = CapabilitySupport.SUPPORTED,
            move = CapabilitySupport.SUPPORTED,
            copy = CapabilitySupport.SUPPORTED,
            delete = CapabilitySupport.SUPPORTED,
            batchOperations = CapabilitySupport.SUPPORTED,
            share = CapabilitySupport.UNVERIFIED,
            upload = UploadCapabilities(
                supported = CapabilitySupport.SUPPORTED,
                maxFileSizeBytes = 200L * 1024 * 1024,
                maxSingleRequestBytes = 200L * 1024 * 1024,
                resumable = false,
                serverSideDuplicateDetection = false,
            ),
            download = DownloadCapabilities(
                supported = CapabilitySupport.SUPPORTED,
                directLink = true,
                directLinkExpires = true,
                folderArchive = CapabilitySupport.UNSUPPORTED,
            ),
            trash = TrashCapabilities(
                supported = CapabilitySupport.SUPPORTED,
                list = CapabilitySupport.SUPPORTED,
                restore = CapabilitySupport.SUPPORTED,
                purge = CapabilitySupport.SUPPORTED,
                purgeRequiresSecret = false,
            ),
            idempotentWrites = false,
        ),
        media = MediaCapabilities(
            videoStreaming = CapabilitySupport.SUPPORTED,
            audioStreaming = CapabilitySupport.SUPPORTED,
            protocols = setOf(MediaProtocol.HLS, MediaProtocol.PROGRESSIVE_HTTP),
            rangeRequests = true,
            urlExpires = true,
            urlRefresh = CapabilitySupport.SUPPORTED,
            subtitles = CapabilitySupport.SUPPORTED,
            multipleRenditions = CapabilitySupport.SUPPORTED,
            separateAudioTracks = CapabilitySupport.SUPPORTED,
            playbackProgress = CapabilitySupport.UNSUPPORTED,
            requiresRequestHeaders = true,
        ),
        preview = PreviewCapabilities(
            image = CapabilitySupport.SUPPORTED,
            thumbnail = CapabilitySupport.SUPPORTED,
            text = CapabilitySupport.SUPPORTED,
            pdf = CapabilitySupport.SUPPORTED,
            officeDocument = CapabilitySupport.SUPPORTED,
            supportedExtensions = MockTree.all
                .filter { !it.isFolder && it.previewable }
                .mapNotNull { it.extension?.lowercase() }
                .toSet(),
        ),
    )
}
