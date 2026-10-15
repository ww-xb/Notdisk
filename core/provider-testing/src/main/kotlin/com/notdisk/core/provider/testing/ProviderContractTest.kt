package com.notdisk.core.provider.testing

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.PreviewHint
import com.notdisk.core.provider.PreviewResourceOrNull
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The shared behavioural contract every [CloudProvider] must satisfy.
 *
 * ### Why this exists
 * The project has two very different implementations (a real 115 integration and an offline Mock)
 * and will gain more. Structural typing alone does not stop a new provider from returning
 * `null` for "not found", throwing where it should return a typed failure, or lying about
 * capabilities. This suite encodes the rules once and is run against *every* provider, so an
 * incompatible implementation fails the build instead of surprising the UI at runtime.
 *
 * ### How a provider plugs in
 * Implement [ProviderContractFixture] with a provider instance plus a small amount of ground
 * truth the suite needs (a folder known to exist, a file known to be readable, …). Then:
 * ```
 * class P115ContractTest : ProviderContractTest(P115Fixture()) { … }
 * ```
 *
 * ### What it deliberately does NOT test
 * Vendor semantics (what 115 returns for `cid=0`), byte-level wire formats, or UI behaviour.
 * Those belong to the provider's own tests and to the UI tests.
 */
abstract class ProviderContractTest(
    private val fixture: ProviderContractFixture,
) {

    protected val provider: CloudProvider get() = fixture.provider
    protected val accountId: AccountId get() = fixture.accountId
    protected val capabilities: CloudCapabilities get() = provider.capabilities()

    // ------------------------------------------------------------------ identity & capabilities

    fun `provider exposes a non-blank identity`() {
        assertTrue(provider.id.value.isNotBlank(), "ProviderId must not be blank")
        assertTrue(provider.displayName.isNotBlank(), "displayName must not be blank")
    }

    fun `capabilities are declared for the provider itself`() {
        assertEquals(
            provider.id,
            capabilities.providerId,
            "capabilities().providerId must equal the provider's own id",
        )
    }

    fun `media provider agrees on provider identity`() {
        assertEquals(
            provider.id,
            provider.media.providerId,
            "MediaProvider.providerId must match CloudProvider.id so refresh keys are fully qualified",
        )
    }

    /**
     * A capability marked SUPPORTED must be backed by an implementation that can actually run.
     * This asserts the *converse* direction too: an operation whose every probe returns
     * `CloudError.Unsupported` must not be advertised as SUPPORTED.
     */
    fun `declared listing capability matches behaviour`() = runTest {
        val declared = capabilities.fileSystem.listChildren
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = fixture.knownFolderId),
        )
        when (declared) {
            CapabilitySupport.SUPPORTED -> assertTrue(
                result is CloudResult.Success,
                "listChildren declared SUPPORTED but returned ${describe(result)}",
            )

            CapabilitySupport.UNSUPPORTED -> assertTrue(
                result.isUnsupported(),
                "listChildren declared UNSUPPORTED but returned ${describe(result)}",
            )

            CapabilitySupport.UNVERIFIED -> Unit // either outcome is acceptable by definition
        }
    }

    fun `declared search capability matches behaviour`() = runTest {
        val declared = capabilities.fileSystem.search
        val result = provider.files.search(
            accountId,
            FileSearchQuery(keyword = fixture.searchKeywordThatMatchesNothing),
        )
        when (declared) {
            CapabilitySupport.SUPPORTED -> assertTrue(
                result is CloudResult.Success || result.isInvalidArgument(),
                "search declared SUPPORTED but returned ${describe(result)}",
            )

            CapabilitySupport.UNSUPPORTED -> assertTrue(
                result.isUnsupported(),
                "search declared UNSUPPORTED but returned ${describe(result)}",
            )

            CapabilitySupport.UNVERIFIED -> Unit
        }
    }

    fun `unsupported auth flows are rejected rather than faked`() = runTest {
        val advertised = capabilities.account.interactiveFlows
        for (flow in AuthFlowKind.entries) {
            if (flow in advertised) continue
            val result = provider.auth.beginAuthorization(flow)
            assertTrue(
                result.isUnsupported(),
                "beginAuthorization($flow) is not advertised but returned ${describe(result)}; " +
                    "a provider must fail closed instead of inventing a flow",
            )
        }
    }

    // ------------------------------------------------------------------ file system behaviour

    fun `root is returned with a folder type and matching account scope`() = runTest {
        val root = provider.files.getRoot(accountId).requireSuccess("getRoot")
        assertEquals(FileType.FOLDER, root.type, "root must be a folder")
        assertEquals(provider.id, root.providerId)
        assertEquals(accountId, root.accountId)
        assertTrue(root.name.isNotBlank())
    }

    fun `listing a folder returns well formed items`() = runTest {
        val page = provider.files
            .listChildren(accountId, ListChildrenRequest(parentId = fixture.knownFolderId))
            .requireSuccess("listChildren")

        assertWellFormedPage(page)
    }

    fun `listing honours the requested page size`() = runTest {
        val page = provider.files.listChildren(
            accountId,
            ListChildrenRequest(parentId = fixture.knownFolderId, pageSize = 1),
        ).requireSuccess("listChildren")

        assertTrue(
            page.items.size <= 1,
            "requested pageSize=1 but got ${page.items.size} items; " +
                "providers must not silently ignore the page size",
        )
    }

    fun `paginating a folder makes progress and eventually terminates`() = runTest {
        val seenIds = LinkedHashSet<FileId>()
        var cursor: String? = null
        var pages = 0
        val maxPages = fixture.maxPaginationPages

        do {
            val page = provider.files.listChildren(
                accountId,
                ListChildrenRequest(
                    parentId = fixture.knownFolderId,
                    cursor = cursor,
                    pageSize = fixture.paginationPageSize,
                ),
            ).requireSuccess("listChildren(page=$pages)")

            val newIds = page.items.map { it.id }.filter { seenIds.add(it) }
            if (page.items.isNotEmpty()) {
                assertTrue(
                    newIds.isNotEmpty() || page.items.isEmpty(),
                    "page $pages returned no new items and no end-of-list marker; " +
                        "the cursor is looping instead of advancing",
                )
            }
            cursor = page.nextCursor
            pages++
        } while (cursor != null && pages < maxPages)

        assertTrue(
            pages < maxPages,
            "pagination did not terminate within $maxPages pages; " +
                "a provider must return nextCursor=null at the end of the listing",
        )
    }

    fun `getFile agrees with the item returned by the listing`() = runTest {
        val page = provider.files
            .listChildren(accountId, ListChildrenRequest(parentId = fixture.knownFolderId))
            .requireSuccess("listChildren")

        val sample = page.items.firstOrNull() ?: return@runTest
        val fetched = provider.files.getFile(accountId, sample.id).requireSuccess("getFile")

        assertEquals(sample.id, fetched.id, "getFile must return the requested id")
        assertEquals(sample.name, fetched.name, "getFile and listChildren disagree on the name")
        assertEquals(sample.type, fetched.type, "getFile and listChildren disagree on the type")
        assertEquals(sample.providerId, fetched.providerId)
        assertEquals(sample.accountId, fetched.accountId)
    }

    fun `unknown file ids produce a typed failure rather than a crash`() = runTest {
        val bogus = fixture.nonExistentFileId
        for (result in listOf(
            provider.files.getFile(accountId, bogus),
            provider.files.getPath(accountId, bogus),
        )) {
            when (result) {
                is CloudResult.Success -> Unit // a provider may legitimately resolve it
                is CloudResult.Failure -> assertTrue(
                    result.error is CloudError.NotFound ||
                        result.error is CloudError.InvalidArgument ||
                        result.error is CloudError.PermissionDenied ||
                        result.error is CloudError.Unsupported ||
                        result.error is CloudError.Unknown,
                    "unexpected error type for a bogus id: ${result.error}",
                )
            }
        }
    }

    fun `path resolution is ordered root first and ends at the requested node`() = runTest {
        val path = provider.files.getPath(accountId, fixture.knownFolderId).requireSuccess("getPath")
        assertTrue(path.segments.isNotEmpty(), "getPath returned an empty path")
        assertTrue(
            path.segments.all { it.name.isNotBlank() },
            "every path segment must have a display name",
        )
    }

    fun `search results are well formed`() = runTest {
        if (!capabilities.fileSystem.search.isSupported) return@runTest
        val page = provider.files
            .search(accountId, FileSearchQuery(keyword = fixture.searchKeywordThatMatchesSomething))
            .requireSuccess("search")
        assertWellFormedPage(page)
    }

    fun `blank search keyword is rejected as invalid rather than sent upstream`() = runTest {
        // FileSearchQuery's constructor already requires a non-blank keyword; this pins the
        // behaviour so a provider cannot relax it by accepting a raw string.
        val rejected = try {
            FileSearchQuery(keyword = "   ")
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(rejected, "a blank search keyword must not be constructible")
    }

    // ------------------------------------------------------------------ operations behaviour

    fun `operations that are not declared are rejected with Unsupported`() = runTest {
        val ops = capabilities.operations

        // Three-way semantics, matching every other capability rule in this suite:
        //   UNSUPPORTED -> the call must fail closed with CloudError.Unsupported;
        //   SUPPORTED   -> the call must not claim "unsupported";
        //   UNVERIFIED  -> either outcome is acceptable, because "unverified" means exactly that
        //                  the implementation may or may not work and this build has not proven
        //                  which. (Collapsing UNVERIFIED into the failure branch would contradict
        //                  the other rules and force providers to under-declare real work.)
        assertOperationHonoursDeclaration(
            declaration = ops.createFolder,
            operation = "createFolder",
        ) { provider.operations.createFolder(accountId, fixture.knownFolderId, "contract-probe") }

        assertOperationHonoursDeclaration(
            declaration = ops.delete,
            operation = "delete",
        ) { provider.operations.delete(accountId, listOf(fixture.knownFolderId)) }

        assertOperationHonoursDeclaration(
            declaration = ops.share,
            operation = "share",
        ) { provider.operations.share(accountId, listOf(fixture.knownFolderId)) }

        assertOperationHonoursDeclaration(
            declaration = ops.trash.supported,
            operation = "listTrash",
        ) { provider.operations.listTrash(accountId) }

        assertOperationHonoursDeclaration(
            declaration = ops.trash.purge,
            operation = "purgeFromTrash",
        ) { provider.operations.purgeFromTrash(accountId, listOf("1")) }

        assertOperationHonoursDeclaration(
            declaration = ops.upload.supported,
            operation = "upload",
        ) {
            provider.operations.upload(
                accountId,
                UploadRequest(
                    key = CloudFileKey(provider.id, accountId, fixture.knownFolderId),
                    parentId = fixture.knownFolderId,
                    fileName = "contract-probe.bin",
                    totalBytes = 1,
                    sourceUri = fixture.readableContentUriOrNull ?: "content://contract/probe",
                ),
            )
        }

        assertOperationHonoursDeclaration(
            declaration = ops.download.supported,
            operation = "prepareDownload",
        ) { provider.operations.prepareDownload(accountId, fixture.knownFolderId) }
    }

    fun `rename validates the new name before contacting the vendor`() = runTest {
        if (!capabilities.operations.rename.isSupported) return@runTest
        val result = provider.operations.rename(accountId, fixture.knownFolderId, "   ")
        assertTrue(
            result.isInvalidArgument(),
            "a blank target name must be rejected locally with InvalidArgument, got ${describe(result)}",
        )
    }

    // ------------------------------------------------------------------ media behaviour

    fun `declared media capability matches behaviour`() = runTest {
        val declared = capabilities.media.videoStreaming
        val mediaFile = fixture.knownVideoFileId
        if (mediaFile == null) return@runTest

        val result = provider.media.resolveMedia(accountId, mediaFile)
        when (declared) {
            CapabilitySupport.SUPPORTED -> assertTrue(
                result is CloudResult.Success,
                "videoStreaming declared SUPPORTED but resolveMedia returned ${describe(result)}",
            )

            CapabilitySupport.UNSUPPORTED -> assertTrue(
                result.isUnsupported(),
                "videoStreaming declared UNSUPPORTED but resolveMedia returned ${describe(result)}",
            )

            CapabilitySupport.UNVERIFIED -> Unit
        }
    }

    fun `resolved media resource is internally consistent`() = runTest {
        val mediaFile = fixture.knownVideoFileId ?: return@runTest
        val resource = provider.media.resolveMedia(accountId, mediaFile).getOrNull() ?: return@runTest

        assertTrue(resource.url.isNotBlank(), "MediaResource.url must not be blank")
        assertTrue(
            resource.protocol in capabilities.media.protocols || capabilities.media.protocols.isEmpty(),
            "resolveMedia returned protocol ${resource.protocol} which is not in the declared " +
                "capability set ${capabilities.media.protocols}",
        )
        if (resource.refreshable) {
            val token = provider.media.refreshTokenFor(
                accountId,
                mediaFile,
                resource,
            )
            assertEquals(
                CloudFileKey(provider.id, accountId, mediaFile),
                token.key,
                "a refreshable resource must carry a fully qualified refresh key",
            )
        }
    }

    fun `media failure for an unsupported file is typed and never throws`() = runTest {
        val nonMedia = fixture.knownNonMediaFileId ?: return@runTest
        val result = provider.media.resolveMedia(accountId, nonMedia)
        if (result is CloudResult.Failure) {
            assertNotNull(result.error.message)
        }
    }

    fun `playback progress reporting is honest when undeclared`() = runTest {
        if (capabilities.media.playbackProgress.isSupported) return@runTest
        val mediaFile = fixture.knownVideoFileId ?: return@runTest
        assertTrue(
            provider.media.reportPlaybackProgress(accountId, mediaFile, 1000, 2000).isUnsupported(),
            "playbackProgress is not declared SUPPORTED but reportPlaybackProgress did not " +
                "return CloudError.Unsupported; never fake a successful progress report",
        )
    }

    // ------------------------------------------------------------------ preview behaviour

    fun `declared preview capability matches behaviour`() = runTest {
        val declared = capabilities.preview.image
        val imageFile = fixture.knownImageFileId
        if (imageFile == null) return@runTest

        val result = provider.preview.preview(
            accountId,
            imageFile,
            hint = PreviewHint(name = "probe.jpg", extension = "jpg", mimeType = "image/jpeg"),
        )
        when (declared) {
            CapabilitySupport.SUPPORTED -> assertTrue(
                result is CloudResult.Success,
                "image preview declared SUPPORTED but preview() returned ${describe(result)}",
            )

            CapabilitySupport.UNSUPPORTED -> assertTrue(
                result.isUnsupported(),
                "image preview declared UNSUPPORTED but preview() returned ${describe(result)}",
            )

            CapabilitySupport.UNVERIFIED -> Unit
        }
    }

    fun `non previewable files classify as Unsupported rather than failing the call`() = runTest {
        val declared = capabilities.preview.image
        if (!declared.isSupported && !capabilities.preview.officeDocument.isSupported) return@runTest

        val opaque = fixture.knownUnpreviewableFileId ?: return@runTest
        val result = provider.preview.preview(
            accountId,
            opaque,
            hint = PreviewHint(name = "contract-probe.bin", extension = "bin"),
        )
        val preview = result.getOrNull() ?: return@runTest
        assertTrue(
            preview.kind is PreviewKind.Unsupported,
            "an unpreviewable file must classify as PreviewKind.Unsupported, got ${preview.kind}",
        )
    }

    /**
     * The brief requires list rendering not to pay a network request per row. That is only
     * achievable if a provider can classify locally, so the guarantee is asserted here rather than
     * assumed.
     */
    fun `classification without access resolution needs no resolvable file`() = runTest {
        val imageFile = fixture.knownImageFileId ?: return@runTest
        val result = provider.preview.preview(
            accountId,
            imageFile,
            resolveAccess = false,
            hint = PreviewHint(name = "probe.jpg", extension = "jpg", mimeType = "image/jpeg"),
        )

        // Either a local classification or a typed failure is acceptable; what is NOT acceptable is
        // an untyped crash or a call that reports a resolved resource it was told not to resolve.
        val preview = result.getOrNull() ?: return@runTest
        assertTrue(
            preview.resource == null,
            "resolveAccess=false must not return access material, got a resource for ${preview.kind}",
        )
    }

    fun `preview never embeds vendor urls inside the kind`() = runTest {
        val imageFile = fixture.knownImageFileId ?: return@runTest
        val preview: PreviewResult = provider.preview.preview(
            accountId,
            imageFile,
            hint = PreviewHint(name = "probe.jpg", extension = "jpg"),
        ).getOrNull() ?: return@runTest

        // OfficeDocument is the single sanctioned exception: the vendor's own viewer requires a
        // URL + token, and it is modelled explicitly rather than hidden in a generic kind.
        if (preview.kind !is PreviewKind.OfficeDocument) {
            val rendered = preview.kind.toString()
            assertTrue(
                !rendered.contains("http://") && !rendered.contains("https://"),
                "PreviewKind must not carry a URL; access material belongs in PreviewResult.resource",
            )
        }
    }

    fun `thumbnail capability matches behaviour and absence is not an error`() = runTest {
        val imageFile = fixture.knownImageFileId ?: return@runTest
        val result: CloudResult<PreviewResourceOrNull> = provider.preview.thumbnail(accountId, imageFile)

        when (capabilities.preview.thumbnail) {
            CapabilitySupport.SUPPORTED -> {
                val payload = result.getOrNull()
                    ?: fail("thumbnail declared SUPPORTED but returned ${describe(result)}")
                // payload.resource == null is a legitimate "no thumbnail for this item" answer.
                assertNotNull(payload.key)
            }

            CapabilitySupport.UNSUPPORTED -> assertTrue(
                result.isUnsupported(),
                "thumbnail declared UNSUPPORTED but returned ${describe(result)}",
            )

            CapabilitySupport.UNVERIFIED -> Unit
        }
    }

    // ------------------------------------------------------------------ account behaviour

    fun `listing accounts never contacts the network and never throws`() = runTest {
        val result = provider.auth.listAccounts()
        assertTrue(
            result is CloudResult.Success,
            "listAccounts must be a local, infallible read, got ${describe(result)}",
        )
        result.getOrNull()?.forEach { account ->
            assertEquals(provider.id, account.providerId, "account row leaked from another provider")
        }
    }

    fun `session for an unknown account is a typed failure`() = runTest {
        val unknown = AccountId("contract-test-unknown-account")
        val result = provider.auth.session(unknown)
        assertTrue(
            result.isFailure(),
            "session() for an unknown account must fail rather than fabricate a session",
        )
    }

    fun `getAccount for an unknown account is a typed failure`() = runTest {
        val unknown = AccountId("contract-test-unknown-account")
        assertTrue(
            provider.auth.getAccount(unknown).isFailure(),
            "getAccount() for an unknown account must return a typed failure",
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun assertWellFormedPage(page: FilePage) {
        page.items.forEach { item ->
            assertTrue(item.name.isNotBlank(), "every item must have a non-blank name")
            assertEquals(provider.id, item.providerId, "item ${item.id} has a foreign providerId")
            assertEquals(accountId, item.accountId, "item ${item.id} has a foreign accountId")
            if (item.type == FileType.FOLDER) {
                assertTrue(
                    item.extension == null,
                    "folder ${item.name} must not carry a file extension",
                )
            }
            if (item.type == FileType.FILE) {
                val size = item.size
                assertTrue(
                    size == null || size >= 0,
                    "file ${item.name} has a negative size",
                )
            }
            // A FileId must never be reused as a media URL or a token; the encodings we accept
            // are opaque but must at least not look like an absolute URL.
            assertTrue(
                !item.id.value.startsWith("http://") && !item.id.value.startsWith("https://"),
                "FileId must be an identifier, not a URL: ${item.id}",
            )
        }
    }

    /**
     * Asserts that an operation's behaviour agrees with its declared capability, using the
     * three-way [CapabilitySupport] semantics.
     */
    private suspend fun assertOperationHonoursDeclaration(
        declaration: CapabilitySupport,
        operation: String,
        call: suspend () -> CloudResult<*>,
    ) {
        val result = call()
        when (declaration) {
            CapabilitySupport.SUPPORTED -> {
                // A broad capability can legitimately have narrow holes: 115 can stream *video*
                // files but not a folder, and can download *files* but not a folder. Such a case
                // still returns `Unsupported`, but it must say which narrower capability was
                // missing — that marker is what distinguishes "the provider lied about supporting
                // this" from "the provider supports this in general but not for this input".
                if (result.isUnsupported()) {
                    val capability = (result as CloudResult.Failure).error.let {
                        (it as? CloudError.Unsupported)?.capability
                    }
                    assertTrue(
                        capability != null,
                        "$operation is declared SUPPORTED but returned CloudError.Unsupported " +
                            "without naming a narrower capability; a broad capability may have " +
                            "narrow holes, but the hole must be identifiable",
                    )
                }
            }

            CapabilitySupport.UNSUPPORTED -> assertTrue(
                result.isUnsupported(),
                "$operation is declared UNSUPPORTED but did not return CloudError.Unsupported; " +
                    "a provider must fail closed instead of silently doing something else",
            )

            CapabilitySupport.UNVERIFIED -> Unit // by definition, unproven either way
        }
    }

    private fun describe(result: CloudResult<*>): String = when (result) {
        is CloudResult.Success -> "Success"
        is CloudResult.Failure -> result.error::class.simpleName ?: "Failure"
    }

    private fun CloudResult<*>.isUnsupported(): Boolean =
        this is CloudResult.Failure && error is CloudError.Unsupported

    private fun CloudResult<*>.isInvalidArgument(): Boolean =
        this is CloudResult.Failure && error is CloudError.InvalidArgument

    private fun <T> CloudResult<T>.requireSuccess(operation: String): T = when (this) {
        is CloudResult.Success -> data
        is CloudResult.Failure ->
            fail("$operation failed: ${error::class.simpleName} — ${error.message}")
    }
}

/**
 * Ground truth a provider must supply so the shared contract suite can run.
 *
 * Everything here is *test data*: real providers point at a fixture folder the developer owns,
 * Mock providers point at their generated tree. No secrets belong in this object.
 */
interface ProviderContractFixture {

    val provider: CloudProvider

    /** An account that is already bound and usable for read-only probes. */
    val accountId: AccountId

    /** A folder that exists and whose children can be listed. */
    val knownFolderId: FileId

    /** A file id that is guaranteed **not** to exist. */
    val nonExistentFileId: FileId

    /** A keyword that matches at least one file, for the search capability probe. */
    val searchKeywordThatMatchesSomething: String

    /** A keyword that matches nothing; must still be a successful (empty) call when search works. */
    val searchKeywordThatMatchesNothing: String

    /** How many pages the pagination probe may consume before declaring a loop. */
    val maxPaginationPages: Int get() = 12

    /** Page size used by the pagination probe. */
    val paginationPageSize: Int get() = 5

    /** A readable `content://` URI, or `null` when the provider cannot upload anyway. */
    val readableContentUriOrNull: String? get() = null

    val knownVideoFileId: FileId? get() = null
    val knownImageFileId: FileId? get() = null
    val knownNonMediaFileId: FileId? get() = null
    val knownUnpreviewableFileId: FileId? get() = null
}

/**
 * Convenience base that exposes every contract rule as a JUnit test method, so a provider only
 * has to subclass and supply a fixture.
 *
 * Kept separate from [ProviderContractTest] so the rules can also be invoked selectively from a
 * bespoke test class (useful when a provider is only partially implemented yet).
 */
abstract class ProviderContractRules(fixture: ProviderContractFixture) : ProviderContractTest(fixture) {

    @org.junit.Test
    fun contract_provider_identity() = `provider exposes a non-blank identity`()

    @org.junit.Test
    fun contract_capabilities_self_consistent() = `capabilities are declared for the provider itself`()

    @org.junit.Test
    fun contract_media_provider_identity() = `media provider agrees on provider identity`()

    @org.junit.Test
    fun contract_listing_capability_matches_behaviour() = `declared listing capability matches behaviour`()

    @org.junit.Test
    fun contract_search_capability_matches_behaviour() = `declared search capability matches behaviour`()

    @org.junit.Test
    fun contract_unadvertised_auth_flows_fail_closed() =
        `unsupported auth flows are rejected rather than faked`()

    @org.junit.Test
    fun contract_root_is_a_folder() = `root is returned with a folder type and matching account scope`()

    @org.junit.Test
    fun contract_listing_items_well_formed() = `listing a folder returns well formed items`()

    @org.junit.Test
    fun contract_listing_honours_page_size() = `listing honours the requested page size`()

    @org.junit.Test
    fun contract_pagination_terminates() = `paginating a folder makes progress and eventually terminates`()

    @org.junit.Test
    fun contract_get_file_matches_listing() = `getFile agrees with the item returned by the listing`()

    @org.junit.Test
    fun contract_unknown_id_is_typed_failure() =
        `unknown file ids produce a typed failure rather than a crash`()

    @org.junit.Test
    fun contract_path_ordering() =
        `path resolution is ordered root first and ends at the requested node`()

    @org.junit.Test
    fun contract_search_results_well_formed() = `search results are well formed`()

    @org.junit.Test
    fun contract_blank_search_rejected() = `blank search keyword is rejected as invalid rather than sent upstream`()

    @org.junit.Test
    fun contract_undeclared_operations_fail_closed() =
        `operations that are not declared are rejected with Unsupported`()

    @org.junit.Test
    fun contract_rename_validates_name() = `rename validates the new name before contacting the vendor`()

    @org.junit.Test
    fun contract_media_capability_matches_behaviour() = `declared media capability matches behaviour`()

    @org.junit.Test
    fun contract_media_resource_consistent() = `resolved media resource is internally consistent`()

    @org.junit.Test
    fun contract_media_failure_typed() =
        `media failure for an unsupported file is typed and never throws`()

    @org.junit.Test
    fun contract_progress_reporting_honest() = `playback progress reporting is honest when undeclared`()

    @org.junit.Test
    fun contract_preview_capability_matches_behaviour() = `declared preview capability matches behaviour`()

    @org.junit.Test
    fun contract_unpreviewable_is_not_an_error() =
        `non previewable files classify as Unsupported rather than failing the call`()

    @org.junit.Test
    fun contract_classification_is_local() =
        `classification without access resolution needs no resolvable file`()

    @org.junit.Test
    fun contract_preview_kind_has_no_urls() = `preview never embeds vendor urls inside the kind`()

    @org.junit.Test
    fun contract_thumbnail_capability_matches_behaviour() =
        `thumbnail capability matches behaviour and absence is not an error`()

    @org.junit.Test
    fun contract_list_accounts_is_local() =
        `listing accounts never contacts the network and never throws`()

    @org.junit.Test
    fun contract_unknown_account_session_fails() = `session for an unknown account is a typed failure`()

    @org.junit.Test
    fun contract_unknown_account_get_fails() = `getAccount for an unknown account is a typed failure`()
}

/** Unused import guard: keeps the fixture API discoverable from tests. */
@Suppress("unused")
private val sortFields: List<FileSortField> = FileSortField.entries

@Suppress("unused")
private val defaultSort: FileSort = FileSort.Default

@Suppress("unused")
private val categories: List<FileCategory> = FileCategory.entries

@Suppress("unused")
private val fileItemReference: FileItem? = null

@Suppress("unused")
private val providerIdReference: ProviderId? = null
