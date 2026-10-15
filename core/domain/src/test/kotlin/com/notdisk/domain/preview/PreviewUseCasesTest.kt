package com.notdisk.domain.preview

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResource
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.PreviewHint
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the preview use cases.
 *
 * ### The two properties with teeth here
 *  1. **The hint is forwarded faithfully.** `PreviewHint` is advisory, so dropping it breaks
 *     nothing visibly — the app just becomes slower, issuing an extra round trip per list row. That
 *     kind of regression is invisible to a test that only checks the return value, so these tests
 *     assert on the *recorded hint object*, nulls and all.
 *  2. **A successful "cannot preview this file" is not turned into an error.** The provider contract
 *     distinguishes "this provider has no preview at all" ([CloudError.Unsupported]) from "preview
 *     works, but not for this file" ([PreviewKind.Unsupported] inside a success). Collapsing the
 *     second into the first shows the user an error dialog for a `.psd` — a normal outcome.
 */
class PreviewUseCasesTest {

    private val fileId = FileId("file-1")

    // ---------------------------------------------------------------------------------------------
    // ResolvePreviewUseCase — hint forwarding
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `forwards the hint to the provider unchanged`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val hint = PreviewHint(
            name = "假期视频.mp4",
            extension = "mp4",
            mimeType = "video/mp4",
            sizeBytes = 4_194_304L,
        )

        ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            hint = hint,
        ).requireSuccess()

        assertEquals(
            hint,
            alpha.callsOn(ProviderSurface.PREVIEW).single()["hint"],
            "dropping the hint costs an extra round trip per list row and fails silently",
        )
    }

    @Test
    fun `forwards a partial hint with its absent fields still absent`() = runTest {
        // A test that reconstructed the hint could not tell "forwarded faithfully" from
        // "forwarded after losing the null fields".
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val hint = PreviewHint(name = "photo.jpg")

        ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            hint = hint,
        ).requireSuccess()

        val forwarded = alpha.callsOn(ProviderSurface.PREVIEW).single()["hint"] as PreviewHint
        assertEquals("photo.jpg", forwarded.name)
        assertNull(forwarded.extension)
        assertNull(forwarded.mimeType)
        assertNull(forwarded.sizeBytes)
        assertEquals("jpg", forwarded.normalisedExtension)
    }

    @Test
    fun `defaults to an empty hint when the caller knows nothing`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        ResolvePreviewUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT, fileId)
            .requireSuccess()

        assertEquals(
            PreviewHint.Empty,
            alpha.callsOn(ProviderSurface.PREVIEW).single()["hint"],
            "an absent hint must never be a failure condition",
        )
    }

    @Test
    fun `forwards resolveAccess so classification can be kept off the network`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            resolveAccess = false,
        ).requireSuccess()

        assertEquals(false, alpha.callsOn(ProviderSurface.PREVIEW).single()["resolveAccess"])
    }

    @Test
    fun `returns the classified kind and its access material`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            hint = PreviewHint(name = "a.jpg", mimeType = "image/jpeg"),
        ).requireSuccess()

        assertTrue(result.kind is PreviewKind.Image)
        assertEquals("image/jpeg", result.mimeType)
        assertTrue(result.resource != null)
    }

    @Test
    fun `a successful cannot-preview-this-file result is not rewritten into a failure`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.previewFake.previewResult = PreviewResult(
            key = com.notdisk.core.model.CloudFileKey(Fixtures.ALPHA, Fixtures.ACCOUNT, fileId),
            kind = PreviewKind.Unsupported(reason = "暂不支持此文件格式预览"),
            resource = null,
        )

        val result = ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isSuccess(), "an unsupported *format* is a normal outcome, not an error")
        val kind = result.requireSuccess().kind
        assertTrue(kind is PreviewKind.Unsupported)
        assertEquals("暂不支持此文件格式预览", kind.reason)
    }

    @Test
    fun `a provider-level Unsupported stays a failure`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.previewFake.previewFailure = CloudError.Unsupported(capability = "preview")

        val result = ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure(), "'this provider cannot preview' is a different screen")
        result.failureOfType<CloudError.Unsupported>()
    }

    @Test
    fun `an unknown provider is Unsupported`() = runTest {
        val result = ResolvePreviewUseCase(registryOf())(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unsupported>()
    }

    @Test
    fun `an office viewer handoff carries its url and token together`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.previewFake.previewResult = PreviewResult(
            key = com.notdisk.core.model.CloudFileKey(Fixtures.ALPHA, Fixtures.ACCOUNT, fileId),
            kind = PreviewKind.OfficeDocument(
                viewer = com.notdisk.core.model.OfficeViewer(
                    url = "https://view.invalid/doc",
                    token = "short-lived",
                    canEdit = false,
                ),
            ),
        )

        val kind = ResolvePreviewUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        ).requireSuccess().kind

        assertTrue(kind is PreviewKind.OfficeDocument)
        assertEquals("short-lived", kind.viewer.token)
    }

    // ---------------------------------------------------------------------------------------------
    // ResolveThumbnailUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `thumbnail forwards the ref so a cached rendition can be served`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val ref = ThumbnailRef(sizeHint = "480", contentHash = "abcdef0123456789")

        ResolveThumbnailUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            ref = ref,
        ).requireSuccess()

        assertEquals(ref, alpha.callsOn(ProviderSurface.PREVIEW).single()["ref"])
    }

    @Test
    fun `no thumbnail for this item is a success with a null resource`() = runTest {
        // The common case for folders and archives. Collapsing it into an error is how a file list
        // ends up full of broken-image placeholders.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.previewFake.thumbnailResource = null

        val result = ResolveThumbnailUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isSuccess())
        assertNull(result.requireSuccess().resource)
    }

    @Test
    fun `thumbnail refuses before the network when the provider has no thumbnail capability`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            preview = caps.preview.copy(thumbnail = CapabilitySupport.UNSUPPORTED),
        )

        val result = ResolveThumbnailUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(ResolveThumbnailUseCase.CAPABILITY_THUMBNAIL, error.capability)
        assertTrue(
            alpha.wasNeverCalled(),
            "a scrolling grid must issue zero doomed thumbnail requests",
        )
    }

    @Test
    fun `thumbnail passes a null ref through for callers that only hold an id`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        ResolveThumbnailUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
            ref = null,
        ).requireSuccess()

        assertNull(alpha.callsOn(ProviderSurface.PREVIEW).single()["ref"])
    }

    // ---------------------------------------------------------------------------------------------
    // ResolveDownloadSourceUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `download source returns the freshly signed url and its expiry`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.operationsFake.downloadSource = com.notdisk.core.provider.DownloadSource(
            url = "https://cdn.invalid/signed",
            fileName = "movie.mkv",
            sizeBytes = 8_000_000_000L,
            expiresAt = 9_999L,
            refreshable = true,
        )

        val source = ResolveDownloadSourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        ).requireSuccess()

        assertEquals("https://cdn.invalid/signed", source.url)
        assertEquals(9_999L, source.expiresAt)
        assertTrue(!source.isExpired(nowMillis = 9_000L))
        assertTrue(source.isExpired(nowMillis = 9_999L))
    }

    @Test
    fun `download source refuses before the network when download is unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(
                download = com.notdisk.core.model.DownloadCapabilities.Unsupported,
            ),
        )

        val result = ResolveDownloadSourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(ResolveDownloadSourceUseCase.CAPABILITY_DOWNLOAD, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `download source forwards a provider failure untouched`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.operationsFake.prepareDownloadFailure = CloudError.QuotaExceeded()

        val result = ResolveDownloadSourceUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileId,
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.QuotaExceeded>()
    }
}
