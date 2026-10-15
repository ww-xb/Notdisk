package com.notdisk.domain.files

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.provider.DefaultProviderRegistry
import com.notdisk.domain.Fixtures
import com.notdisk.domain.failureOfType
import com.notdisk.domain.registryOf
import com.notdisk.domain.requireSuccess
import com.notdisk.domain.testing.CapabilityFixtures
import com.notdisk.domain.testing.CursorPolicy
import com.notdisk.domain.testing.FakeCloudProvider
import com.notdisk.domain.testing.ProviderSurface
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ListChildrenUseCase], with the **cursor-progress invariant** as the centrepiece.
 *
 * ### Why the echoed-cursor case gets its own section
 * It is the one provider bug that does not look like a bug. Nothing throws, nothing logs an error,
 * and the feed screen's `cursor = page.nextCursor; load()` loop simply re-requests page 1 forever:
 * the user sees the same rows appended repeatedly, and the in-memory list grows until the process
 * dies with no stack trace pointing anywhere near the provider. The use case is the only place that
 * holds both the cursor that was sent and the cursor that came back, so it is the only place the
 * loop can be broken — and these tests are what keep that guarantee from being refactored away.
 */
class ListChildrenUseCaseTest {

    private val parentId = FileId("folder-1")

    // ---------------------------------------------------------------------------------------------
    // Pass-through behaviour
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `passes a well-formed page through unchanged`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val page = FilePage(
            items = emptyList(),
            nextCursor = "cursor-2",
            totalCount = 100L,
        )
        alpha.filesFake.listChildrenResult = CloudResult.Success(page)
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId)

        val returned = result.requireSuccess()
        assertEquals("cursor-2", returned.nextCursor)
        assertEquals(100L, returned.totalCount)
        assertTrue(returned.hasMore)
    }

    @Test
    fun `forwards the request fields verbatim`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = ListChildrenUseCase(registryOf(alpha))

        useCase(
            providerId = Fixtures.ALPHA,
            accountId = Fixtures.ACCOUNT,
            parentId = parentId,
            cursor = "cursor-7",
            pageSize = 77,
            sort = FileSort(ascending = true),
        )

        val call = alpha.callsOn(ProviderSurface.FILES).single()
        assertEquals(parentId, call["parentId"])
        assertEquals("cursor-7", call["cursor"])
        assertEquals(77, call["pageSize"])
        assertEquals(
            Fixtures.ACCOUNT,
            call["accountId"],
            "the account must be threaded through explicitly, never taken from ambient state",
        )
    }

    @Test
    fun `a final page reporting no further cursor is a success`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.cursorPolicy = CursorPolicy.End
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val page = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "cursor-9")
            .requireSuccess()

        assertNull(page.nextCursor, "null means 'no more pages', a definitive statement")
        assertTrue(!page.hasMore)
    }

    @Test
    fun `a genuinely advancing cursor is allowed`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.cursorPolicy = CursorPolicy.Advance
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val page = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "cursor-1")
            .requireSuccess()

        assertEquals("cursor-1-next", page.nextCursor)
    }

    // ---------------------------------------------------------------------------------------------
    // The cursor-progress invariant
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `FAILS when the provider echoes the cursor that was passed in`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.cursorPolicy = CursorPolicy.EchoInput
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "stuck")

        assertTrue(result.isFailure(), "an echoed cursor would make 'load more' loop forever")
        val error = result.failureOfType<CloudError.Unknown>()
        assertTrue(
            error.message.contains("stuck"),
            "the message must name the offending cursor so the log line is actionable",
        )
        assertTrue(
            error.message.contains("alpha"),
            "the message must name the provider",
        )
    }

    @Test
    fun `the echoed-cursor failure is Unknown and not a misleadingly specific type`() = runTest {
        // Documented rationale: ParseError would send the next engineer hunting for a JSON bug,
        // InvalidArgument would blame a caller who passed the provider's own cursor, and
        // Unsupported would wrongly disable paging for the provider forever.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.cursorPolicy = CursorPolicy.EchoInput
        val registry = DefaultProviderRegistry(listOf(alpha))
        val useCase = ListChildrenUseCase(registry)

        val error = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "c")
            .failureOfType<CloudError.Unknown>()

        // Unknown carries no `` field by design; assert it is the plain variant.
        assertNull(error.vendorCode)
        assertNull(error.httpStatus)
    }

    @Test
    fun `a first-page request is never rejected even if the provider echoes null`() = runTest {
        // cursor == null means "first page"; returning nextCursor == null means "no more pages".
        // That is the correct report for a single-page folder, not a violation.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.cursorPolicy = CursorPolicy.EchoInput
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val page = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = null)
            .requireSuccess()

        assertNull(page.nextCursor)
    }

    @Test
    fun `the invariant is per-request, not global state`() = runTest {
        // The use case is stateless: an echoed cursor on one call must not poison a later,
        // well-formed call (a remembered-history implementation would wrongly fail this).
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.cursorPolicy = CursorPolicy.EchoInput
        val useCase = ListChildrenUseCase(registryOf(alpha))

        assertTrue(useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "x").isFailure())

        alpha.filesFake.cursorPolicy = CursorPolicy.Advance
        assertTrue(useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "x").isSuccess())
    }

    @Test
    fun `an empty cursor string is treated as a real cursor and checked`() = runTest {
        // Vendors that use "" as a cursor send it back as "". The check must not special-case it.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.listChildrenResult = CloudResult.Success(
            FilePage(items = emptyList(), nextCursor = ""),
        )
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "")

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unknown>()
    }

    @Test
    fun `a provider failure is forwarded untouched and not reclassified`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.filesFake.listChildrenFailure = CloudError.PermissionDenied()
        val useCase = ListChildrenUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, cursor = "c")

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.PermissionDenied>()
    }

    @Test
    fun `an unknown provider fails with Unsupported rather than throwing`() = runTest {
        val useCase = ListChildrenUseCase(registryOf())

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId)

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.Unsupported>()
    }

    @Test
    fun `the canonical request overload is what the provider sees`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = ListChildrenUseCase(registryOf(alpha))
        val request = ListChildrenRequest(parentId = parentId, cursor = "c1", pageSize = 10)

        useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, request)

        val call = alpha.callsOn(ProviderSurface.FILES).single()
        assertEquals(10, call["pageSize"])
        assertEquals(parentId, call["parentId"])
    }
}
