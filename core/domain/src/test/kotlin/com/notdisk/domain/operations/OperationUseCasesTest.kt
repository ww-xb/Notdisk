package com.notdisk.domain.operations

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.isFailure
import com.notdisk.core.common.result.isSuccess
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.domain.Fixtures
import com.notdisk.domain.failureOfType
import com.notdisk.domain.registryOf
import com.notdisk.domain.requireSuccess
import com.notdisk.domain.testing.CapabilityFixtures
import com.notdisk.domain.testing.FakeCloudProvider
import com.notdisk.domain.testing.ProviderSurface
import com.notdisk.domain.testing.trashItem
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the mutating use cases.
 *
 * ### The property under test throughout
 * Every one of these tests checks the same two-part guarantee, in this order:
 *
 *  1. **validate locally** — a blank name or an empty selection is refused with
 *     [CloudError.InvalidArgument] and **zero** provider calls; and
 *  2. **gate on the declaration** — an operation the provider declares unsupported is refused with
 *     [CloudError.Unsupported] naming the capability, also with zero provider calls.
 *
 * The `wasNeverCalled()` assertions are the point. "We do not send doomed requests" is the design
 * rule this module exists to enforce, and it is only checkable against a fake that records calls —
 * which is why these tests use a hand-written provider rather than a relaxed mock that would answer
 * anything and record nothing.
 */
class OperationUseCasesTest {

    private val parentId = FileId("parent")
    private val fileA = FileId("file-a")
    private val fileB = FileId("file-b")

    // ---------------------------------------------------------------------------------------------
    // CreateFolderUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `createFolder forwards the name and parent`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val created = CreateFolderUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            parentId,
            "新建文件夹",
        ).requireSuccess()

        assertEquals("新建文件夹", created.name)
        val call = alpha.callsOn(ProviderSurface.OPERATIONS).single()
        assertEquals("createFolder", call.method)
        assertEquals("新建文件夹", call["name"])
        assertEquals(parentId, call["parentId"])
    }

    @Test
    fun `createFolder rejects a blank name locally with zero provider calls`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = CreateFolderUseCase(registryOf(alpha))

        for (blank in listOf("", "   ", "\t\n")) {
            val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, blank)

            assertTrue(result.isFailure(), "'$blank' must be rejected")
            result.failureOfType<CloudError.InvalidArgument>()
        }
        assertTrue(alpha.wasNeverCalled(), "no folder-name validation may reach the network")
    }

    @Test
    fun `createFolder rejects a path separator in the name`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = CreateFolderUseCase(registryOf(alpha))

        // '/' means "nested path" to some vendors and "literal character" to others: accepting it
        // would make the same input mean two different things per account.
        for (bad in listOf("a/b", "a\\b")) {
            assertTrue(
                useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, bad).isFailure(),
                "'$bad' must be rejected",
            )
        }
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `createFolder rejects dot and dot-dot names`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = CreateFolderUseCase(registryOf(alpha))

        assertTrue(useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, ".").isFailure())
        assertTrue(useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, "..").isFailure())
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `createFolder applies the byte-length ceiling, not the character count`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = CreateFolderUseCase(registryOf(alpha))

        // 200 Chinese characters = 600 UTF-8 bytes: over the ceiling, though only 200 "characters".
        val tooLong = "中".repeat(200)
        assertTrue(tooLong.length < CreateFolderUseCase.MAX_NAME_BYTES)

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, tooLong)

        assertTrue(result.isFailure(), "the limit is on encoded bytes, as HTTP and object stores count")
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `createFolder accepts a name at the ceiling`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val asciiLimit = "a".repeat(CreateFolderUseCase.MAX_NAME_BYTES)

        val result = CreateFolderUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            parentId,
            asciiLimit,
        )

        assertTrue(result.isSuccess())
        assertEquals(1, alpha.totalCalls())
    }

    @Test
    fun `createFolder refuses before the network when unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(createFolder = CapabilitySupport.UNSUPPORTED),
        )

        val result = CreateFolderUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            parentId,
            "名称",
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(CreateFolderUseCase.CAPABILITY_CREATE_FOLDER, error.capability)
        assertTrue(alpha.wasNeverCalled(), "an undeclared capability must cost zero calls")
    }

    @Test
    fun `createFolder does not trim the forwarded name`() = runTest {
        // Trimming would silently rename the user's folder; the vendor is the authority on
        // whether it normalises whitespace.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        CreateFolderUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            parentId,
            "  spaced  ",
        ).requireSuccess()

        assertEquals("  spaced  ", alpha.callsOn(ProviderSurface.OPERATIONS).single()["name"])
    }

    // ---------------------------------------------------------------------------------------------
    // RenameUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `rename forwards the new name and returns the re-fetched item`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val item = RenameUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileA,
            "新名字.mp4",
        ).requireSuccess()

        assertEquals("新名字.mp4", item.name)
        assertEquals("rename", alpha.callsOn(ProviderSurface.OPERATIONS).single().method)
    }

    @Test
    fun `rename rejects a blank name with zero provider calls`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = RenameUseCase(registryOf(alpha))

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, fileA, "   ")

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(error.message.contains("名称"))
        assertTrue(
            alpha.wasNeverCalled(),
            "the blank-name guard must run before the provider is resolved or called",
        )
    }

    @Test
    fun `rename and createFolder share one validator so the two cannot drift`() = runTest {
        // A name that is legal to create but illegal to rename to is a trap users find by
        // creating a folder and then being unable to fix its typo.
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val badNames = listOf("", "  ", "a/b", "a\\b", ".", "..", "中".repeat(200))

        for (name in badNames) {
            val create = CreateFolderUseCase(registryOf(alpha))(
                Fixtures.ALPHA, Fixtures.ACCOUNT, parentId, name,
            )
            val rename = RenameUseCase(registryOf(alpha))(
                Fixtures.ALPHA, Fixtures.ACCOUNT, fileA, name,
            )
            assertEquals(
                create.isFailure(),
                rename.isFailure(),
                "'$name' must be judged identically by create and rename",
            )
        }
    }

    @Test
    fun `rename refuses before the network when unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(rename = CapabilitySupport.UNSUPPORTED),
        )

        val result = RenameUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            fileA,
            "valid-name",
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(RenameUseCase.CAPABILITY_RENAME, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    // ---------------------------------------------------------------------------------------------
    // MoveFilesUseCase / CopyFilesUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `move forwards the selection and target`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val batch = MoveFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA, fileB),
            parentId,
        ).requireSuccess()

        assertEquals(2, batch.succeeded.size)
        assertTrue(batch.allSucceeded)
        val call = alpha.callsOn(ProviderSurface.OPERATIONS).single()
        assertEquals(listOf(fileA, fileB), call["fileIds"])
        assertEquals(parentId, call["targetParentId"])
    }

    @Test
    fun `move rejects an empty selection with zero provider calls`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = MoveFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            emptyList(),
            parentId,
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled(), "'move nothing' is never what the user meant")
    }

    @Test
    fun `move de-duplicates while preserving the caller's order`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val fileC = FileId("file-c")

        MoveFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA, fileB, fileA, fileC, fileB),
            parentId,
        ).requireSuccess()

        assertEquals(
            listOf(fileA, fileB, fileC),
            alpha.callsOn(ProviderSurface.OPERATIONS).single()["fileIds"],
            "reordering would mis-attribute per-item failures to the wrong rows",
        )
    }

    @Test
    fun `copy de-duplicates because a repeated id would duplicate a file`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        CopyFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA, fileA),
            parentId,
        ).requireSuccess()

        assertEquals(
            listOf(fileA),
            alpha.callsOn(ProviderSurface.OPERATIONS).single()["fileIds"],
            "sending the same id twice creates two identical files on real vendors",
        )
    }

    @Test
    fun `copy refuses before the network when unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(copy = CapabilitySupport.UNSUPPORTED),
        )

        val result = CopyFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
            parentId,
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(CopyFilesUseCase.CAPABILITY_COPY, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `move propagates a partial batch result instead of collapsing it`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.operationsFake.moveResult = com.notdisk.core.common.result.CloudResult.Success(
            com.notdisk.core.provider.BatchOperationResult(
                succeeded = listOf(
                    com.notdisk.core.model.CloudFileKey(Fixtures.ALPHA, Fixtures.ACCOUNT, fileA),
                ),
                failed = listOf(
                    com.notdisk.core.provider.BatchItemFailure(
                        key = com.notdisk.core.model.CloudFileKey(
                            Fixtures.ALPHA,
                            Fixtures.ACCOUNT,
                            fileB,
                        ),
                        error = CloudError.PermissionDenied(),
                    ),
                ),
            ),
        )

        val batch = MoveFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA, fileB),
            parentId,
        ).requireSuccess()

        assertTrue(batch.partiallySucceeded, "a partially applied batch must stay visible")
        assertEquals(1, batch.succeeded.size)
        assertEquals(1, batch.failed.size)
    }

    // ---------------------------------------------------------------------------------------------
    // DeleteFilesUseCase — the two-tier gate
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `delete defaults to a recoverable recycle-bin delete`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        DeleteFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
        ).requireSuccess()

        assertEquals(
            false,
            alpha.callsOn(ProviderSurface.OPERATIONS).single()["permanent"],
            "an omitted flag must be the safe direction",
        )
    }

    @Test
    fun `permanent delete is gated on trash purge, not on plain delete`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        // delete is fine, purge is not — the exact combination that must not fall through to a
        // destructive operation.
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(
                delete = CapabilitySupport.SUPPORTED,
                trash = caps.operations.trash.copy(purge = CapabilitySupport.UNSUPPORTED),
            ),
        )

        val result = DeleteFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
            permanent = true,
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(DeleteFilesUseCase.CAPABILITY_PURGE, error.capability)
        assertTrue(alpha.wasNeverCalled(), "an irreversible request must never be sent on a guess")
    }

    @Test
    fun `recycle-bin delete still works when purge is unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(
                trash = caps.operations.trash.copy(purge = CapabilitySupport.UNSUPPORTED),
            ),
        )

        val result = DeleteFilesUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
            permanent = false,
        )

        assertTrue(result.isSuccess())
        assertEquals(1, alpha.totalCalls())
    }

    // ---------------------------------------------------------------------------------------------
    // SetStarredUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `setStarred forwards both directions through one call`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = SetStarredUseCase(registryOf(alpha))

        useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, listOf(fileA), starred = true).requireSuccess()
        useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, listOf(fileA), starred = false).requireSuccess()

        val calls = alpha.callsOn(ProviderSurface.OPERATIONS)
        assertEquals(true, calls[0]["starred"])
        assertEquals(false, calls[1]["starred"])
    }

    @Test
    fun `setStarred rejects an empty selection locally`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = SetStarredUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            emptyList(),
            starred = true,
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    // ---------------------------------------------------------------------------------------------
    // Trash
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `listTrash forwards paging and reports the secret protection flag`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        alpha.operationsFake.trashPage = com.notdisk.core.provider.TrashPage(
            items = listOf(trashItem("r1"), trashItem("r2")),
            nextCursor = "next",
            totalCount = 2L,
            protectedBySecret = true,
        )

        val page = ListTrashUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            cursor = "c1",
            pageSize = 50,
        ).requireSuccess()

        assertEquals(2, page.items.size)
        assertTrue(page.protectedBySecret, "the UI needs this to warn before offering restore")
        val call = alpha.callsOn(ProviderSurface.OPERATIONS).single()
        assertEquals("c1", call["cursor"])
        assertEquals(50, call["pageSize"])
    }

    @Test
    fun `listTrash refuses before the network when the provider has no recycle bin`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(
                trash = caps.operations.trash.copy(list = CapabilitySupport.UNSUPPORTED),
            ),
        )

        val result = ListTrashUseCase(registryOf(alpha))(Fixtures.ALPHA, Fixtures.ACCOUNT)

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(ListTrashUseCase.CAPABILITY_TRASH_LIST, error.capability)
        assertTrue(
            alpha.wasNeverCalled(),
            "an empty list would wrongly tell the user their files are gone forever",
        )
    }

    @Test
    fun `restoreFromTrash forwards trash ids and de-duplicates them`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        RestoreFromTrashUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf("r1", "r2", "r1"),
        ).requireSuccess()

        assertEquals(
            listOf("r1", "r2"),
            alpha.callsOn(ProviderSurface.OPERATIONS).single()["trashIds"],
        )
    }

    @Test
    fun `restoreFromTrash rejects an empty selection and blank ids`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = RestoreFromTrashUseCase(registryOf(alpha))

        assertTrue(useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, emptyList()).isFailure())
        assertTrue(useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, listOf("  ")).isFailure())
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `restoreFromTrash refuses before the network when unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(
                trash = caps.operations.trash.copy(restore = CapabilitySupport.UNSUPPORTED),
            ),
        )

        val result = RestoreFromTrashUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf("r1"),
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(RestoreFromTrashUseCase.CAPABILITY_TRASH_RESTORE, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `purgeFromTrash passes the safe key through without storing or inventing one`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        PurgeFromTrashUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf("r1"),
            safeKey = "user-typed-secret",
        ).requireSuccess()

        assertEquals(
            "user-typed-secret",
            alpha.callsOn(ProviderSurface.OPERATIONS).single()["safeKey"],
        )
    }

    @Test
    fun `purgeFromTrash forwards a null safe key rather than fabricating one`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        PurgeFromTrashUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf("r1"),
            safeKey = null,
        ).requireSuccess()

        assertEquals(null, alpha.callsOn(ProviderSurface.OPERATIONS).single()["safeKey"])
    }

    @Test
    fun `purgeFromTrash refuses before the network when unsupported`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(
                trash = caps.operations.trash.copy(purge = CapabilitySupport.UNSUPPORTED),
            ),
        )

        val result = PurgeFromTrashUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf("r1"),
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(PurgeFromTrashUseCase.CAPABILITY_TRASH_PURGE, error.capability)
        assertTrue(alpha.wasNeverCalled(), "a mis-tapped destructive button must not reach a vendor")
    }

    // ---------------------------------------------------------------------------------------------
    // CreateShareUseCase
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `share forwards both directions and the password`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val useCase = CreateShareUseCase(registryOf(alpha))

        useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, listOf(fileA), enable = true, password = "abcd")
            .requireSuccess()
        useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, listOf(fileA), enable = false).requireSuccess()

        val calls = alpha.callsOn(ProviderSurface.OPERATIONS)
        assertEquals(true, calls[0]["enable"])
        assertEquals("abcd", calls[0]["password"])
        assertEquals(false, calls[1]["enable"])
    }

    @Test
    fun `share allows an UNVERIFIED declaration through, because the vendor documents it`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(share = CapabilitySupport.UNVERIFIED),
        )

        val result = CreateShareUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
        )

        assertTrue(result.isSuccess(), "refusing UNVERIFIED would make the endpoint unreachable")
        assertEquals(1, alpha.totalCalls())
    }

    @Test
    fun `share refuses before the network when the provider cannot share at all`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)
        val caps = CapabilityFixtures.allSupported(Fixtures.ALPHA)
        alpha.capabilityDeclaration = caps.copy(
            operations = caps.operations.copy(share = CapabilitySupport.UNSUPPORTED),
        )

        val result = CreateShareUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
        )

        assertTrue(result.isFailure())
        val error = result.failureOfType<CloudError.Unsupported>()
        assertEquals(CreateShareUseCase.CAPABILITY_SHARE, error.capability)
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `share rejects an empty selection locally`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = CreateShareUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            emptyList(),
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    @Test
    fun `share rejects a NUL byte in the password`() = runTest {
        val alpha = FakeCloudProvider(Fixtures.ALPHA)

        val result = CreateShareUseCase(registryOf(alpha))(
            Fixtures.ALPHA,
            Fixtures.ACCOUNT,
            listOf(fileA),
            password = "a\u0000b",
        )

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
        assertTrue(alpha.wasNeverCalled())
    }

    // ---------------------------------------------------------------------------------------------
    // Validation happens before provider resolution
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `an unknown provider still yields the validation error first`() = runTest {
        // Ordering matters for the user: telling them the name is invalid is more useful than
        // telling them the provider is unavailable, when both are true.
        val useCase = MoveFilesUseCase(registryOf())

        val result = useCase(Fixtures.ALPHA, Fixtures.ACCOUNT, emptyList(), parentId)

        assertTrue(result.isFailure())
        result.failureOfType<CloudError.InvalidArgument>()
    }
}
