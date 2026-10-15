package com.notdisk.domain.files

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.domain.Fixtures
import com.notdisk.domain.failureOfType
import com.notdisk.domain.registryOf
import com.notdisk.domain.requireSuccess
import com.notdisk.domain.testing.CapabilityFixtures
import com.notdisk.domain.testing.FakeCloudProvider
import com.notdisk.domain.testing.ProviderSurface
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [GetRootUseCase], [GetFileUseCase], [GetFilePathUseCase], [ResolvePathUseCase],
 * [SearchFilesUseCase] and [GetCapabilitiesUseCase].
 */
class FileUseCasesTest {

    // ---------------------------------------------------------------------------------------------
    // GetRootUseCase / GetFileUseCase / GetFilePathUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `getRoot resolves through the registry and forwards the account`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val root = GetRootUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT)
            .requireSuccess()

        assertTrue(root.isFolder)
        assertEquals(Fixtures.ACCOUNT, alpha.callsOn(ProviderSurface.FILES).single()["accountId"])
    }

    @Test
    fun `getRoot on an unknown provider is Unsupported, not a crash`() = runTest {
        val result = GetRootUseCase(registryOf())(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unsupported>()
    }

    @Test
    fun `getFile forwards the fully-qualified triple`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val fileId = FileId("pickcode-abc")

        GetFileUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT, fileId)
            .requireSuccess()

        val call = alpha.callsOn(ProviderSurface.FILES).single()
        assertEquals(fileId, call["fileId"])
        assertEquals(Fixtures.ACCOUNT, call["accountId"])
    }

    @Test
    fun `getPath returns the breadcrumb chain in root-to-file order`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.path = com.notdisk.core.model.FilePath(
            listOf(
                com.notdisk.core.model.FilePathSegment(id = null, name = "根目录"),
                com.notdisk.core.model.FilePathSegment(id = FileId("d1"), name = "电视剧"),
                com.notdisk.core.model.FilePathSegment(id = FileId("f1"), name = "第一季"),
            ),
        )

        val path = GetFilePathUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            FileId("f1"),
        ).requireSuccess()

        assertEquals("根目录/电视剧/第一季", path.displayPath)
        assertEquals(null, path.segments.first().id, "the synthetic root is not addressable")
    }

    // ---------------------------------------------------------------------------------------------
    // ResolvePathUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolvePath forwards the vendor-facing path unsplit`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        // The domain layer must not guess the separator: a vendor may allow '/' inside a name.
        val path = "我的文件/电视剧/第一季"

        ResolvePathUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT, path)
            .requireSuccess()

        assertEquals(path, alpha.callsOn(ProviderSurface.FILES).single()["path"])
    }

    @Test
    fun `resolvePath rejects a blank path without calling the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = ResolvePathUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT, "   ")

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `resolvePath rejects a NUL byte without calling the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = ResolvePathUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            "a\u0000b",
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `resolvePath allows a dot-dot segment and defers to the vendor`() = runTest {
        // Whether '..' means anything is a property of the vendor's path grammar, not this layer.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = ResolvePathUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            "../sibling",
        )

        assertTrue(result.isSuccess())
        assertEquals(1, alpha.totalCalls())
    }

    // ---------------------------------------------------------------------------------------------
    // SearchFilesUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `search forwards the full query including category and suffixes`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val query = FileSearchQuery(
            keyword = "报告",
            parentId = FileId("d1"),
            category = FileCategory.DOCUMENT,
            suffixes = setOf("pdf", "docx"),
        )

        SearchFilesUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT, query)
            .requireSuccess()

        val call = alpha.callsOn(ProviderSurface.FILES).single()
        assertEquals("报告", call["keyword"])
        assertEquals(FileId("d1"), call["parentId"])
        assertEquals(FileCategory.DOCUMENT, call["category"])
        assertEquals(setOf("pdf", "docx"), call["suffixes"])
    }

    @Test
    fun `search refuses a blank keyword without calling the provider`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        // Constructed via the raw string overload so the model's own init block is not what
        // rejects it: this test is about the *use case* producing a typed error, because a blank
        // query is a normal user action (tapping search on an empty field), not a crash.
        val useCase = SearchFilesUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, "   ")

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(error.message.contains("搜索关键词"))
        assertTrue(alpha.wasNeverCalled(), "a doomed search must never leave the device")
    }

    @Test
    fun `search refuses before the network when the provider cannot search`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            fileSystem = caps.fileSystem.copy(search = CapabilitySupport.UNSUPPORTED),
        )
        val useCase = SearchFilesUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, "keyword")

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(SearchFilesUseCase.CAPABILITY_SEARCH, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `search passes through a provider-declared UNVERIFIED capability`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            fileSystem = caps.fileSystem.copy(search = CapabilitySupport.UNVERIFIED),
        )

        val result = SearchFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            "keyword",
        )

        assertTrue(result.isSuccess(), "UNVERIFIED must stay reachable")
        assertEquals(1, alpha.totalCalls())
    }

    // ---------------------------------------------------------------------------------------------
    // GetCapabilitiesUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `getCapabilities returns the declaration without any provider call`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.capabilityDeclaration = CapabilityFixtures.allUnsupported(Fixtures.ALPHA)

        val caps = GetCapabilitiesUseCase(registryOf(alpha))(Fixtures.ALPHA).requireSuccess()

        assertEquals(Fixtures.ALPHA, caps.providerId)
        assertTrue(!caps.hasVerifiedBrowsing)
        assertTrue(
            alpha.wasNeverCalled(),
            "capabilities() is contracted to be side-effect free and must not be recorded as a call",
        )
    }

    @Test
    fun `getCapabilities on an unknown provider is Unsupported`() = runTest {
        val result = GetCapabilitiesUseCase(registryOf())(Fixtures.ALPHA)

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertTrue(error.capability!!.contains(Fixtures.ALPHA.value))
    }

    @Test
    fun `getCapabilities reflects a provider that changed its declaration`() = runTest {
        // The use case must not cache: a declaration is expected to be cheap and read on every
        // composition that decides which affordances to render.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = GetCapabilitiesUseCase(registryOf(alpha))

        assertTrue(useCase(Fixtures.ALPHA).requireSuccess().hasVerifiedBrowsing)

        alpha.capabilityDeclaration = CapabilityFixtures.allUnsupported(Fixtures.ALPHA)
        assertTrue(!useCase(Fixtures.ALPHA).requireSuccess().hasVerifiedBrowsing)
    }
}
