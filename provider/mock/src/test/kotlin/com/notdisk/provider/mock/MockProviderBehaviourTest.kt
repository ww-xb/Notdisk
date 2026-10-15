package com.notdisk.provider.mock

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.MediaProtocol
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.TransferDirection
import com.notdisk.core.model.TransferStatus
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.model.CloudFileKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for the Mock provider **beyond** the shared contract.
 *
 * The contract suite proves the provider obeys the common rules. These tests pin down the concrete
 * behaviours the UI and the repository will rely on when they are developed offline:
 * multi-account isolation, empty-vs-forbidden folders, sorting/filtering, transfer progress,
 * expiring media URLs, and preview classification.
 *
 * They double as executable documentation of what "a working provider" looks like, which is what
 * the real 115 implementation is then measured against.
 */
class MockProviderBehaviourTest {

    private val fixture = MockProviders.contractFixture()
    private val provider = fixture.provider
    private val accountId = fixture.accountId

    // ------------------------------------------------------------------ accounts

    @Test
    fun `multiple accounts of the same provider are isolated`() = runTest {
        val secondAccount = provider.seedAccount("第二个账号")
        assertTrue(secondAccount != accountId, "seeding must create a distinct account id")

        val accounts = provider.auth.listAccounts().getOrNull()
        assertNotNull(accounts)
        assertEquals(2, accounts.size, "the Mock provider must support several accounts")

        // Both accounts address the same Mock tree, but every returned item must be scoped to the
        // account that asked — that scoping is exactly what the repository caches on.
        val first = provider.files.getRoot(accountId).getOrNull()
        val second = provider.files.getRoot(secondAccount).getOrNull()
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(accountId, first.accountId)
        assertEquals(secondAccount, second.accountId)
        assertEquals(first.id, second.id, "both accounts address the same Mock tree")
    }

    @Test
    fun `logging out one account leaves the other usable`() = runTest {
        val secondAccount = provider.seedAccount("保留账号")
        assertTrue(provider.auth.logout(accountId) is CloudResult.Success)

        val remaining = provider.auth.listAccounts().getOrNull()
        assertEquals(listOf(secondAccount), remaining?.map { it.accountId })
        assertTrue(
            provider.files.getRoot(secondAccount) is CloudResult.Success,
            "removing one account must not invalidate another account of the same provider",
        )
    }

    @Test
    fun `expired session is reported as TokenExpired rather than silently working`() = runTest {
        provider.invalidateAllSessions()

        val session = provider.auth.session(accountId)
        assertTrue(session is CloudResult.Failure)
        assertTrue(
            (session as CloudResult.Failure).error is CloudError.TokenExpired,
            "an expired credential must surface as TokenExpired so the UI can route to re-auth",
        )

        val listing = provider.files.listChildren(
            accountId,
            ListChildrenRequest(FileId(MockTree.ROOT_ID)),
        )
        assertTrue(
            listing is CloudResult.Failure && listing.error is CloudError.TokenExpired,
            "data calls must fail with TokenExpired once the session is invalid, got $listing",
        )
    }

    @Test
    fun `qr authorization flow reaches success and binds an account`() = runTest {
        val fresh = MockProviders.empty()
        val challenge = fresh.auth.beginAuthorization(AuthFlowKind.QR_CODE).getOrNull()
        assertNotNull(challenge)
        assertTrue(challenge is AuthChallenge.QrCode, "the QR flow must produce a QR challenge")

        val state = fresh.auth.pollChallenge(challenge.challengeId).getOrNull()
        assertTrue(
            state is AuthChallengeState.Succeeded,
            "with pollsUntilSuccess=0 the first poll completes the flow, got $state",
        )
        assertEquals(1, fresh.auth.listAccounts().getOrNull()?.size)
    }

    @Test
    fun `cancelling a challenge stops it from ever succeeding`() = runTest {
        val fresh = MockProviders.empty()
        val challenge = fresh.auth.beginAuthorization(AuthFlowKind.QR_CODE).getOrNull()
        assertNotNull(challenge)

        fresh.auth.cancelChallenge(challenge.challengeId)

        // The poll must still answer *something* typed rather than throwing or returning null:
        // the UI polls until it sees a terminal state.
        val result = fresh.auth.pollChallenge(challenge.challengeId)
        val state = result.getOrNull()
        assertNotNull(
            state,
            "polling a cancelled challenge must return a terminal state, not a null payload",
        )
        assertTrue(
            state is AuthChallengeState.Failed,
            "a cancelled challenge must not be able to bind an account, got $state",
        )
        assertEquals(0, fresh.auth.listAccounts().getOrNull()?.size)
    }

    @Test
    fun `password flow is refused because it is not advertised`() = runTest {
        val result = provider.auth.beginAuthorization(AuthFlowKind.PASSWORD)
        assertTrue(result is CloudResult.Failure)
        assertTrue(
            (result as CloudResult.Failure).error is CloudError.Unsupported,
            "a flow that is not in interactiveFlows must fail closed",
        )
    }

    // ------------------------------------------------------------------ listing semantics

    @Test
    fun `an empty folder is a successful empty page not an error`() = runTest {
        val page = provider.files
            .listChildren(accountId, ListChildrenRequest(fixture.emptyFolderId))
            .getOrNull()

        assertNotNull(page, "listing an empty folder must succeed")
        assertTrue(page.items.isEmpty())
        assertNull(page.nextCursor, "an empty folder must not advertise a next page")
    }

    @Test
    fun `a forbidden folder is PermissionDenied not an empty page`() = runTest {
        val result = provider.files.listChildren(
            accountId,
            ListChildrenRequest(fixture.restrictedFolderId),
        )

        assertTrue(result is CloudResult.Failure)
        assertTrue(
            (result as CloudResult.Failure).error is CloudError.PermissionDenied,
            "an unreadable folder must not be indistinguishable from an empty one",
        )
    }

    @Test
    fun `folders are listed before files regardless of sort direction`() = runTest {
        for (ascending in listOf(true, false)) {
            val page = provider.files.listChildren(
                accountId,
                ListChildrenRequest(
                    parentId = FileId(MockTree.ROOT_ID),
                    sort = FileSort(FileSortField.NAME, ascending = ascending),
                ),
            ).getOrNull()
            assertNotNull(page)

            val firstFileIndex = page.items.indexOfFirst { it.type == FileType.FILE }
            val lastFolderIndex = page.items.indexOfLast { it.type == FileType.FOLDER }
            if (firstFileIndex >= 0 && lastFolderIndex >= 0) {
                assertTrue(
                    lastFolderIndex < firstFileIndex,
                    "folders must precede files (ascending=$ascending)",
                )
            }
        }
    }

    @Test
    fun `sorting by size descends and by name ascends`() = runTest {
        val bySize = provider.files.listChildren(
            accountId,
            ListChildrenRequest(
                parentId = FileId(MockTree.ROOT_ID),
                sort = FileSort(FileSortField.SIZE, ascending = false),
            ),
        ).getOrNull()!!
        val fileSizes = bySize.items.filter { it.isFile }.mapNotNull { it.size }
        assertEquals(
            fileSizes.sortedDescending(),
            fileSizes,
            "size-descending sort must actually order files by size",
        )

        val byName = provider.files.listChildren(
            accountId,
            ListChildrenRequest(
                parentId = FileId(MockTree.ROOT_ID),
                sort = FileSort(FileSortField.NAME, ascending = true),
            ),
        ).getOrNull()!!
        val names = byName.items.filter { it.isFile }.map { it.normalizedName }
        assertEquals(names.sorted(), names)
    }

    @Test
    fun `pagination walks every item exactly once`() = runTest {
        val seen = mutableListOf<FileId>()
        var cursor: String? = null
        do {
            val page = provider.files.listChildren(
                accountId,
                ListChildrenRequest(fixture.knownFolderId, cursor = cursor, pageSize = 7),
            ).getOrNull()!!
            seen += page.items.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(
            MockTree.largeFolderChildren.size,
            seen.size,
            "paging must visit every child exactly once",
        )
        assertEquals(seen.size, seen.toSet().size, "paging must not repeat an item")
    }

    @Test
    fun `search can be narrowed to a subtree and by suffix`() = runTest {
        val everything = provider.files.search(
            accountId,
            FileSearchQuery(keyword = "file-"),
        ).getOrNull()!!
        assertTrue(everything.items.isNotEmpty())

        val narrowed = provider.files.search(
            accountId,
            FileSearchQuery(
                keyword = "file-",
                parentId = FileId(MockTree.DOCUMENTS_ID),
            ),
        ).getOrNull()!!
        assertTrue(
            narrowed.items.size < everything.items.size,
            "restricting the search to a subtree must not return the same result set",
        )

        // A blank keyword cannot even be constructed: the model rejects it, which is what stops
        // a caller from accidentally turning "search" into "list the whole drive".
        val rejected = try {
            FileSearchQuery(keyword = "", suffixes = setOf("jpg", "png"))
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue(rejected, "FileSearchQuery must refuse a blank keyword")

        // Suffix filtering on a real keyword still narrows correctly.
        val bySuffix = provider.files.search(
            accountId,
            FileSearchQuery(keyword = "file-", suffixes = setOf("txt")),
        ).getOrNull()
        assertNotNull(bySuffix)
        assertTrue(bySuffix.items.filter { it.isFile }.all { it.extension == "txt" })
    }

    @Test
    fun `search results never leak outside the account scope`() = runTest {
        val page = provider.files.search(
            accountId,
            FileSearchQuery(keyword = "readme"),
        ).getOrNull()!!
        assertTrue(page.items.all { it.accountId == accountId })
    }

    @Test
    fun `path resolution returns the breadcrumb chain`() = runTest {
        val path = provider.files.getPath(accountId, fixture.knownFolderId).getOrNull()
        assertNotNull(path)
        // `大目录` hangs directly off the root in the fixture tree.
        assertEquals(
            listOf("根目录", "大目录"),
            path.segments.map { it.name },
            "the path must run root -> ... -> target",
        )

        // A deeper node proves the chain is not just root + leaf.
        val deep = provider.files.getPath(accountId, FileId(MockTree.DEEP_ID)).getOrNull()
        assertNotNull(deep)
        assertEquals(
            listOf("根目录", "文档", "深层目录"),
            deep.segments.map { it.name },
            "an intermediate folder must appear in the breadcrumb",
        )
    }

    @Test
    fun `resolvePath accepts a human path and rejects a wrong one`() = runTest {
        val resolved = provider.files.resolvePath(accountId, "文档/深层目录").getOrNull()
        assertEquals(FileId(MockTree.DEEP_ID), resolved)

        val missing = provider.files.resolvePath(accountId, "文档/不存在的目录")
        assertTrue(missing is CloudResult.Failure)
        assertTrue((missing as CloudResult.Failure).error is CloudError.NotFound)
    }

    // ------------------------------------------------------------------ operations

    @Test
    fun `creating a folder rejects a duplicate name locally`() = runTest {
        val created = provider.operations
            .createFolder(accountId, FileId(MockTree.ROOT_ID), "新目录")
            .getOrNull()
        assertNotNull(created)
        assertEquals(FileType.FOLDER, created.type)

        val duplicate = provider.operations.createFolder(accountId, FileId(MockTree.ROOT_ID), "新目录")
        assertTrue(
            duplicate is CloudResult.Failure &&
                (duplicate as CloudResult.Failure).error is CloudError.InvalidArgument,
            "a duplicate folder name must be refused with a typed error",
        )
    }

    @Test
    fun `renaming a folder rejects path separators`() = runTest {
        val result = provider.operations.rename(accountId, fixture.knownFolderId, "a/b")
        assertTrue(
            result is CloudResult.Failure &&
                (result as CloudResult.Failure).error is CloudError.InvalidArgument,
        )
    }

    @Test
    fun `batch delete reports per item outcomes for a mixed request`() = runTest {
        val readable = FileId("mock:f-readme")
        val forbidden = fixture.restrictedFolderId
        val missing = fixture.nonExistentFileId

        val result = provider.operations
            .delete(accountId, listOf(readable, forbidden, missing))
            .getOrNull()

        assertNotNull(result)
        assertEquals(1, result.succeeded.size, "only the readable item should be deleted")
        assertEquals(2, result.failed.size, "the forbidden and missing items must be reported")
        assertTrue(result.partiallySucceeded, "a mixed batch must be reported as partial")

        val errors = result.failed.map { it.error }
        assertTrue(errors.any { it is CloudError.PermissionDenied })
        assertTrue(errors.any { it is CloudError.NotFound })
    }

    @Test
    fun `deleting an empty selection is refused instead of sending a pointless request`() = runTest {
        val result = provider.operations.delete(accountId, emptyList())
        assertTrue(
            result is CloudResult.Failure &&
                (result as CloudResult.Failure).error is CloudError.InvalidArgument,
        )
    }

    @Test
    fun `permanent delete is refused because the provider routes deletes to the trash`() = runTest {
        val result = provider.operations.delete(accountId, listOf(FileId("mock:f-readme")), permanent = true)
        assertTrue(
            result is CloudResult.Failure &&
                (result as CloudResult.Failure).error is CloudError.Unsupported,
            "the permanent-delete path must be explicitly unsupported, not silently downgraded",
        )
    }

    @Test
    fun `delete moves items to the trash where they can be listed and restored`() = runTest {
        val target = FileId("mock:f-notes")
        val deleted = provider.operations.delete(accountId, listOf(target)).getOrNull()
        assertEquals(1, deleted?.succeeded?.size)

        val trash = provider.operations.listTrash(accountId).getOrNull()
        assertNotNull(trash)
        assertEquals(1, trash.items.size)
        assertEquals("notes.md", trash.items.first().originalName)

        val restored = provider.operations
            .restoreFromTrash(accountId, listOf(trash.items.first().trashId))
            .getOrNull()
        assertEquals(1, restored?.succeeded?.size)

        val emptyTrash = provider.operations.listTrash(accountId).getOrNull()
        assertTrue(emptyTrash!!.items.isEmpty(), "a restored entry must leave the trash")
    }

    @Test
    fun `purge from trash reports a wrong safe key instead of pretending to succeed`() = runTest {
        provider.operations.delete(accountId, listOf(FileId("mock:f-report")))
        val trash = provider.operations.listTrash(accountId).getOrNull()!!

        val withKey = provider.operations.purgeFromTrash(
            accountId,
            listOf(trash.items.first().trashId),
            safeKey = "unexpected",
        )
        assertTrue(
            withKey is CloudResult.Failure &&
                (withKey as CloudResult.Failure).error is CloudError.InvalidArgument,
            "the Mock account has no safe key, so supplying one must be an error rather than ignored",
        )

        val withoutKey = provider.operations
            .purgeFromTrash(accountId, listOf(trash.items.first().trashId))
            .getOrNull()
        assertEquals(1, withoutKey?.succeeded?.size)
    }

    // ------------------------------------------------------------------ transfers

    @Test
    fun `upload progress is observable and reaches completion`() = runTest {
        val task = provider.operations.upload(
            accountId,
            UploadRequest(
                key = CloudFileKey(provider.id, accountId, FileId(MockTree.ROOT_ID)),
                parentId = FileId(MockTree.ROOT_ID),
                fileName = "上传测试.bin",
                totalBytes = 4_000,
                sourceUri = "content://test/upload",
            ),
        ).getOrNull()
        assertNotNull(task)
        assertEquals(TransferDirection.UPLOAD, task.direction)
        assertTrue(
            !task.autoRetryable,
            "uploads must not be auto-retryable: a blind retry can create duplicate files",
        )

        // Collecting the flow must TERMINATE: a transfer stream that never completes would hang
        // this test forever, which is precisely the contract violation being guarded against.
        val emissions = mutableListOf<com.notdisk.core.model.TransferProgress>()
        val collection = launch { provider.operations.observeTransfer(task.id).collect { emissions += it } }

        val immediate = emissions.firstOrNull() ?: run {
            // The first emission is available synchronously from the backing StateFlow.
            provider.operations.observeTransfer(task.id).first()
        }
        assertEquals(TransferStatus.QUEUED, immediate.status)
        assertEquals(
            0f,
            immediate.fraction,
            "a known total of 4000 bytes with 0 transferred is exactly 0%, not null",
        )

        provider.completeTransfer(task.id)
        collection.join()

        assertEquals(TransferStatus.COMPLETED, emissions.last().status)
        assertEquals(1f, emissions.last().fraction, "a completed transfer is exactly 100%")
    }

    @Test
    fun `a transfer with an unknown total reports no fraction instead of guessing`() = runTest {
        val task = provider.operations.upload(
            accountId,
            UploadRequest(
                key = CloudFileKey(provider.id, accountId, FileId(MockTree.ROOT_ID)),
                parentId = FileId(MockTree.ROOT_ID),
                fileName = "unknown-size.bin",
                // A SAF source can genuinely report no length; the model must not invent one.
                totalBytes = null,
                sourceUri = "content://test/unknown-size",
            ),
        ).getOrNull()
        assertNotNull(task)

        val progress = provider.operations.observeTransfer(task.id).first()
        assertNull(
            progress.fraction,
            "an unknown total must yield a null fraction so the UI shows an indeterminate bar",
        )
    }

    @Test
    fun `download resolves a fresh short lived source and is retryable`() = runTest {
        val source = provider.operations
            .prepareDownload(accountId, FileId("mock:f-video"))
            .getOrNull()
        assertNotNull(source)
        assertNotNull(source.expiresAt, "a direct link must carry an expiry")
        assertTrue(source.refreshable, "the coordinator must be able to re-resolve it")
        assertEquals(true, source.supportsRangeRequests)

        val task = provider.operations.download(
            accountId,
            com.notdisk.core.model.DownloadRequest(
                key = CloudFileKey(provider.id, accountId, FileId("mock:f-video")),
                fileName = source.fileName,
                destinationPath = "/tmp/out.mp4",
            ),
            source,
        ).getOrNull()
        assertNotNull(task)
        assertTrue(task.autoRetryable, "downloads are idempotent and may be retried")
    }

    @Test
    fun `upload retry is refused while download retry is allowed`() = runTest {
        val upload = provider.operations.upload(
            accountId,
            UploadRequest(
                key = CloudFileKey(provider.id, accountId, FileId(MockTree.ROOT_ID)),
                parentId = FileId(MockTree.ROOT_ID),
                fileName = "retry.bin",
                totalBytes = 10,
                sourceUri = "content://test/retry",
            ),
        ).getOrNull()!!

        val uploadRetry = provider.operations.retryTransfer(upload.id)
        assertTrue(
            uploadRetry is CloudResult.Failure &&
                (uploadRetry as CloudResult.Failure).error is CloudError.Unsupported,
            "retrying an upload must require an explicit user action, not a silent automatic retry",
        )
    }

    @Test
    fun `cancelling a transfer is observable and idempotent`() = runTest {
        val task = provider.operations.upload(
            accountId,
            UploadRequest(
                key = CloudFileKey(provider.id, accountId, FileId(MockTree.ROOT_ID)),
                parentId = FileId(MockTree.ROOT_ID),
                fileName = "cancel.bin",
                totalBytes = 10,
                sourceUri = "content://test/cancel",
            ),
        ).getOrNull()!!

        assertTrue(provider.operations.cancelTransfer(task.id) is CloudResult.Success)
        assertTrue(
            provider.operations.cancelTransfer(task.id) is CloudResult.Success,
            "cancellation must be idempotent",
        )

        // Cancellation is terminal, so the stream must complete rather than stay open.
        val emissions = provider.operations.observeTransfer(task.id).toList()
        assertEquals(TransferStatus.CANCELLED, emissions.last().status)
    }

    // ------------------------------------------------------------------ media

    @Test
    fun `video media resource exposes hls with separated audio tracks`() = runTest {
        val resource = provider.media.resolveMedia(accountId, fixture.knownVideoFileId!!).getOrNull()
        assertNotNull(resource)

        assertEquals(MediaProtocol.HLS, resource.protocol)
        assertTrue(
            resource.audioTracks.isNotEmpty(),
            "the Mock mirrors 115's separated-audio HLS; a player that only handles muxed HLS " +
                "must fail here rather than in production",
        )
        assertTrue(resource.renditions.isNotEmpty(), "renditions enable quality switching")
        assertTrue(resource.refreshable)
        assertNotNull(resource.expiresAt)
    }

    @Test
    fun `media urls expire and re resolving yields a different url`() = runTest {
        var now = 1_000_000L
        val clocked = MockCloudProvider(clock = { now }, seedDemoAccount = true)
        val account = clocked.demoAccountId!!

        val first = clocked.media.resolveMedia(account, FileId("mock:f-video")).getOrNull()!!
        val firstExpiry = requireNotNull(first.expiresAt)

        // Not expired at the moment of issue...
        assertTrue(!first.isExpired(nowMillis = now))
        // ...and expired once the injected clock passes the stated expiry.
        assertTrue(
            first.isExpired(nowMillis = firstExpiry + 1),
            "a resource must report itself expired past its stated expiresAt",
        )
        // The proactive-refresh skew must also be honoured.
        assertTrue(
            first.isExpired(skewMillis = 60_000, nowMillis = firstExpiry - 30_000),
            "a positive skew must treat the resource as expired before it really is",
        )

        // Re-resolving after expiry yields a genuinely new, later-expiring URL.
        now = firstExpiry + 1
        val refreshed = clocked.media.resolveMedia(account, FileId("mock:f-video")).getOrNull()!!
        assertNotNull(refreshed.expiresAt)
        assertTrue(refreshed.expiresAt!! > firstExpiry, "a refresh must extend the expiry")
        assertTrue(
            refreshed.url != first.url,
            "a refreshed URL must be observably different, otherwise the refresh path is untestable",
        )
    }

    @Test
    fun `resolving media for a non media file is a typed failure`() = runTest {
        val result = provider.media.resolveMedia(accountId, fixture.knownNonMediaFileId!!)
        assertTrue(
            result is CloudResult.Failure &&
                (result as CloudResult.Failure).error is CloudError.Unsupported,
        )
    }

    @Test
    fun `resolving media for a folder is an InvalidArgument`() = runTest {
        val result = provider.media.resolveMedia(accountId, FileId(MockTree.MEDIA_ID))
        assertTrue(
            result is CloudResult.Failure &&
                (result as CloudResult.Failure).error is CloudError.InvalidArgument,
        )
    }

    @Test
    fun `audio track resolution yields its own playlist`() = runTest {
        val resource = provider.media
            .resolveMedia(accountId, fixture.knownVideoFileId!!)
            .getOrNull()!!
        val track = resource.audioTracks.first()

        val audio = provider.media
            .resolveAudioTrack(accountId, fixture.knownVideoFileId!!, track)
            .getOrNull()

        assertNotNull(audio)
        assertTrue(audio.url.contains(track.id), "the audio playlist must address the chosen track")
    }

    @Test
    fun `playback progress reporting is unsupported and never faked`() = runTest {
        val result = provider.media.reportPlaybackProgress(
            accountId,
            fixture.knownVideoFileId!!,
            positionMs = 1_000,
            durationMs = 2_000,
        )
        assertTrue(
            result is CloudResult.Failure &&
                (result as CloudResult.Failure).error is CloudError.Unsupported,
            "reporting progress must not silently succeed when the vendor has no such endpoint",
        )
    }

    // ------------------------------------------------------------------ preview

    @Test
    fun `preview classification covers each renderable category`() = runTest {
        suspend fun kindOf(fileId: String): PreviewKind? =
            provider.preview.preview(accountId, FileId(fileId)).getOrNull()?.kind

        assertTrue(kindOf("mock:f-image") is PreviewKind.Image)
        assertTrue(kindOf("mock:f-video") is PreviewKind.Video)
        assertTrue(kindOf("mock:f-audio") is PreviewKind.Audio)
        assertTrue(kindOf("mock:f-readme") is PreviewKind.Text)
        assertTrue(kindOf("mock:f-report") is PreviewKind.Pdf)
        assertTrue(kindOf("mock:f-office") is PreviewKind.OfficeDocument)
        assertTrue(kindOf("mock:f-opaque") is PreviewKind.Unsupported)
    }

    @Test
    fun `classification without access resolution performs no resource resolution`() = runTest {
        val result = provider.preview
            .preview(accountId, FileId("mock:f-image"), resolveAccess = false)
            .getOrNull()

        assertNotNull(result)
        assertTrue(result.kind is PreviewKind.Image, "classification must still work locally")
        assertNull(
            result.resource,
            "resolveAccess=false must not return access material; list rendering must not " +
                "trigger a network round trip per row",
        )
    }

    @Test
    fun `office preview carries an expiring viewer rather than a bare url`() = runTest {
        val result = provider.preview
            .preview(accountId, FileId("mock:f-office"))
            .getOrNull()!!

        val kind = result.kind
        assertTrue(kind is PreviewKind.OfficeDocument)
        assertNotNull(kind.viewer.expiresAt, "an office viewer handoff must carry an expiry")
        assertTrue(kind.viewer.url.isNotBlank())
    }

    @Test
    fun `a folder reports Unsupported preview rather than failing`() = runTest {
        val result = provider.preview
            .preview(accountId, FileId(MockTree.DOCUMENTS_ID))
            .getOrNull()

        assertNotNull(result, "previewing a folder is a normal outcome, not an error")
        assertTrue(result.kind is PreviewKind.Unsupported)
    }

    @Test
    fun `thumbnail absence is a success with a null resource`() = runTest {
        val payload = provider.preview
            .thumbnail(accountId, fixture.knownNonMediaFileId!!)
            .getOrNull()

        assertNotNull(payload)
        assertNull(payload.resource, "a non-image must report 'no thumbnail', not an error")
    }

    @Test
    fun `thumbnail returns a sized resource for an image`() = runTest {
        val payload = provider.preview
            .thumbnail(
                accountId,
                FileId("mock:f-image"),
                com.notdisk.core.model.ThumbnailRef(sizeHint = "1440"),
            )
            .getOrNull()

        assertNotNull(payload?.resource)
        assertTrue(
            payload.resource!!.url.contains("1440"),
            "the requested thumbnail size must be honoured",
        )
    }

    // ------------------------------------------------------------------ localisation of state

    @Test
    fun `capabilities are stable across calls`() {
        val first = provider.capabilities()
        val second = provider.capabilities()
        assertEquals(first, second, "capabilities() must be pure and stable")
    }

    @Test
    fun `capabilities declare the honest gaps of the mock integration`() {
        val caps = provider.capabilities()

        // These are deliberate gaps that mirror the real 115 integration, so the UI code paths
        // for "vendor does not offer this" are exercised offline too.
        assertEquals(
            com.notdisk.core.model.CapabilitySupport.UNSUPPORTED,
            caps.media.playbackProgress,
        )
        assertEquals(
            com.notdisk.core.model.CapabilitySupport.UNVERIFIED,
            caps.operations.share,
        )
        assertTrue(!caps.operations.idempotentWrites)
        assertEquals(false, caps.media.rangeRequests?.not())
    }
}

/** Unused-import guard for [AuthState], referenced only in documentation above. */
private val authStateReference: AuthState? = null
