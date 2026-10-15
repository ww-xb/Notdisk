package com.notdisk.feature.files

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.NoOpCloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.database.dao.FileMetadataDao
import com.notdisk.core.database.entity.FileMetadataEntity
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.data.cache.FileMetadataCache
import com.notdisk.core.data.repository.AccountRepository
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileOperationCapabilities
import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.AuthProvider
import com.notdisk.core.provider.CloudProvider
import com.notdisk.core.provider.DefaultProviderRegistry
import com.notdisk.core.provider.FileOperationProvider
import com.notdisk.core.provider.FileSystemProvider
import com.notdisk.core.provider.MediaProvider
import com.notdisk.core.provider.PreviewProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behavioural tests for [FilesViewModel] against a hand-written fake file system.
 *
 * ### What is faked, and what deliberately is not
 * The ViewModel is constructed with the **real** `CloudFileRepository`, `AccountRepository`,
 * `FileMetadataCache` and `NotDiskSettingsStore`:
 *  - the repository and cache are given a [ScriptedFileSystemProvider] and an in-memory
 *    [InMemoryFileMetadataDao], so the pagination-cursor guard, the sort plumbing and the "reload
 *    after a mutation" path all execute for real;
 *  - the settings store is backed by a real DataStore over a temp file, because `activeAccount` is
 *    the mechanism that decides which account the screen opens.
 *
 * Only the two things that genuinely need Android are replaced: the Room DAO (an interface, faked in
 * memory) and the provider (an interface, scripted per test). This is what makes assertions like
 * "a stale response was discarded" meaningful rather than a restatement of the fake's own behaviour.
 *
 * ### Why nothing here can hang
 * Every test runs under `runTest` with a virtual clock and a [StandardTestDispatcher] installed as
 * `Dispatchers.Main`. No test performs network I/O or an unbounded wait; where a test needs a
 * response to arrive *late*, it does so by returning from a suspending gate the test itself opens
 * (`CompletableDeferred`), never by sleeping — an unresolved gate fails the test on the
 * `advanceUntilIdle` timeout rather than blocking the suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var dataStore: DataStore<Preferences>
    private var storeFile: java.io.File? = null

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // A unique temp path per test. A DataStore file may only be opened by one instance per
        // process, so the name carries the test's identity; it is removed again in `tearDown`.
        val file = java.io.File(
            System.getProperty("java.io.tmpdir"),
            "files-viewmodel-test-${java.util.UUID.randomUUID()}.preferences_pb",
        )
        file.deleteOnExit()
        storeFile = file
        dataStore = PreferenceDataStoreFactory.create(scope = TestScope(dispatcher)) { file }
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
        storeFile?.delete()
    }

    // ------------------------------------------------------------------ (a) stale responses

    @Test
    fun `a slow response for a folder the user left does not overwrite the folder on screen`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(listOf("dir-a", "dir-b")))
        provider.paginate("dir-b", pages = listOf(listOf("b-file")))
        // Folder A's listing is held open; folder B answers immediately afterwards.
        val gateForA = kotlinx.coroutines.CompletableDeferred<Unit>()
        provider.hold("dir-a", gateForA)

        val viewModel = createViewModel(provider)

        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        // The user is at the root and taps folder A …
        viewModel.openFolder(folderItem("dir-a"))
        advanceUntilIdle()
        // … then changes their mind and taps folder B before A's request returns.
        viewModel.openFolder(folderItem("dir-b"))
        advanceUntilIdle()

        assertEquals(
            "dir-b",
            viewModel.state.value.currentFolder?.id?.value,
            "the breadcrumb must show the folder the user is actually in",
        )

        // A's slow answer finally arrives. It must be discarded: applying it would paint A's rows
        // under B's name, which is precisely the bug the generation guard exists to prevent.
        gateForA.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "dir-b",
            viewModel.state.value.currentFolder?.id?.value,
            "a late response must not change which folder is shown",
        )
        assertEquals(
            listOf("b-file"),
            viewModel.state.value.items.map { it.name },
            "the rows must still be folder B's",
        )
    }

    @Test
    fun `a stale failure does not replace the current folder with an error screen`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(listOf("dir-a", "dir-b")))
        provider.paginate("dir-b", pages = listOf(listOf("b-file")))
        val gateForA = kotlinx.coroutines.CompletableDeferred<Unit>()
        provider.hold("dir-a", gateForA)
        provider.failFor("dir-a", CloudError.PermissionDenied(message = "没有权限访问"))

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        viewModel.openFolder(folderItem("dir-a"))
        advanceUntilIdle()
        viewModel.openFolder(folderItem("dir-b"))
        advanceUntilIdle()

        gateForA.complete(Unit)
        advanceUntilIdle()

        assertNull(
            viewModel.state.value.error,
            "the abandoned folder's failure must not surface on the folder now on screen",
        )
        assertEquals(listOf("b-file"), viewModel.state.value.items.map { it.name })
    }

    // ------------------------------------------------------------------ (b) loadMore

    @Test
    fun `loadMore appends the next page and stops at a null cursor`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(listOf("a1", "a2"), listOf("b1", "b2"), listOf("c1")))

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        assertEquals(listOf("a1", "a2"), viewModel.state.value.items.map { it.name })
        assertNotNull(viewModel.state.value.nextCursor, "the provider offered a second page")

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a1", "a2", "b1", "b2"), viewModel.state.value.items.map { it.name })

        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(
            listOf("a1", "a2", "b1", "b2", "c1"),
            viewModel.state.value.items.map { it.name },
        )

        // The third page was the last one: the provider returned `nextCursor = null`, which is a
        // definitive "no more", so further calls must not re-request page 1 and duplicate the list.
        assertNull(viewModel.state.value.nextCursor)
        assertFalse(viewModel.state.value.canLoadMore)

        val requestsBefore = provider.listCalls.size
        viewModel.loadMore()
        advanceUntilIdle()
        assertEquals(
            requestsBefore,
            provider.listCalls.size,
            "loadMore after a null cursor must not issue a request",
        )
        assertEquals(
            listOf("a1", "a2", "b1", "b2", "c1"),
            viewModel.state.value.items.map { it.name },
            "no page may be appended twice",
        )
    }

    @Test
    fun `loadMore does not re-request the first page when there is no cursor`() = runTest {
        val provider = ScriptedFileSystemProvider()
        // A single-page folder: the very first listing reports no cursor.
        provider.paginate("root", pages = listOf(listOf("only")))

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val callsAfterOpen = provider.listCalls.filter { it.cursor == null }.size
        viewModel.loadMore()
        advanceUntilIdle()

        assertEquals(
            callsAfterOpen,
            provider.listCalls.filter { it.cursor == null }.size,
            "page 1 must not be requested again once the provider reported the end",
        )
    }

    // ------------------------------------------------------------------ (c) mutations

    @Test
    fun `a failed mutation surfaces the error and does not reload the folder`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(listOf("dir-sub", "a-file")))
        provider.operationFailure = CloudError.PermissionDenied(message = "没有权限重命名")

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val listingsBefore = provider.listCalls.size
        val target = viewModel.state.value.items.first { it.isFolder }

        viewModel.rename(target, "新名字")
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("没有权限重命名", state.error, "the provider's own message must reach the user")
        assertNotNull(state.errorDetail, "the technical detail is offered behind the disclosure")
        assertFalse(state.isMutating, "the in-flight flag must always be cleared")
        assertEquals(
            listingsBefore,
            provider.listCalls.size,
            "a failed mutation changed nothing, so re-reading the folder would be a wasted request",
        )
    }

    @Test
    fun `a successful mutation reloads the folder from the network`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(listOf("dir-sub", "a-file")))

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val listingsBefore = provider.listCalls.size
        val target = viewModel.state.value.items.first { it.isFolder }

        viewModel.rename(target, "新名字")
        advanceUntilIdle()

        assertNull(viewModel.state.value.error)
        assertTrue(
            provider.listCalls.size > listingsBefore,
            "a successful mutation must re-read the folder it changed",
        )
        // `forceNetwork` only changes whether the repository consults its cache first; the provider
        // is reached either way, which is what this asserts rather than the internal flag.
        assertTrue(
            provider.listCalls.drop(listingsBefore).all { it.cursor == null },
            "the reload is a fresh first page of the current folder, not a continuation",
        )
    }

    @Test
    fun `a failed createFolder surfaces the error and leaves the listing untouched`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.operationFailure = CloudError.InvalidArgument(message = "文件夹名不能为空")

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val itemsBefore = viewModel.state.value.items
        viewModel.createFolder("")
        advanceUntilIdle()

        assertEquals("文件夹名不能为空", viewModel.state.value.error)
        assertEquals(itemsBefore, viewModel.state.value.items)
    }

    // ------------------------------------------------------------------ capabilities, never identity

    @Test
    fun `actions are gated by declared capabilities`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(listOf("dir-sub")))
        provider.declaredOperations = FileOperationCapabilities(
            createFolder = CapabilitySupport.UNSUPPORTED,
            rename = CapabilitySupport.SUPPORTED,
            delete = CapabilitySupport.UNVERIFIED,
        )

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val capabilities = viewModel.state.value.capabilities
        assertFalse(capabilities.canCreateFolder, "UNSUPPORTED must hide the entry point")
        assertTrue(capabilities.canRename)
        assertFalse(
            capabilities.canDelete,
            "UNVERIFIED is not consent: offering delete on a documented-but-unverified " +
                "capability is how a user loses a folder",
        )
    }

    @Test
    fun `an unregistered provider yields no actions instead of crashing`() = runTest {
        val provider = ScriptedFileSystemProvider()
        val viewModel = createViewModel(provider)

        // An account row can outlive the provider registration that created it (a build variant
        // that drops a cloud). The honest UI for that is "no actions", never a crash.
        viewModel.open(ProviderId("not-registered"), ACCOUNT_ID)
        advanceUntilIdle()

        assertEquals(FileActionAvailability.Unavailable, viewModel.state.value.capabilities)
    }

    // ------------------------------------------------------------------ loading / empty / error are distinct

    @Test
    fun `an empty folder reports empty, not loading and not an error`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.paginate("root", pages = listOf(emptyList()))

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state.isEmpty, "an empty folder is a successful listing with no rows")
        assertFalse(state.isLoading)
        assertNull(state.error)
    }

    @Test
    fun `a failure on the root is an error, not an empty folder`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.failFor("root", CloudError.PermissionDenied(message = "没有权限访问「根目录」"))

        val viewModel = createViewModel(provider)
        viewModel.open(PROVIDER_ID, ACCOUNT_ID)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals("没有权限访问「根目录」", state.error)
        assertFalse(state.isEmpty, "a failure must never be rendered as an empty folder")
        assertFalse(state.isLoading)
    }

    // ------------------------------------------------------------------ (d) home level

    /**
     * One account: the home screen *is* that cloud's contents.
     *
     * This is the brief's "只有一个账号时 直接显示该网盘的内容". The alternative — a one-row list holding a
     * single folder — costs the user a tap and makes a configured app look unconfigured.
     */
    @Test
    fun `a single account opens its root directly with no grouping level`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("only"))
        provider.paginate("root", pages = listOf(listOf("a-file")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScreenLevel.ACCOUNT_ROOT_SINGLE, state.level)
        assertEquals(
            listOf("a-file"),
            state.items.map { it.name },
            "the single cloud's own contents must be on screen, not a folder holding them",
        )
        assertFalse(state.isAccountPicker, "there is no picker to show with one account")
    }

    /**
     * Two accounts: each cloud becomes a top-level folder, named after the netdisk.
     *
     * The brief's "有一个以上账号时 用网盘名作为顶层文件夹". The row's *name* is the provider's display
     * name, and its *id* is the account id — two accounts of the same cloud must both appear, which a
     * provider-id keyed row could not express.
     */
    @Test
    fun `several accounts become top-level folders named after the netdisk`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("a1"), account("a2"))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScreenLevel.HOME_MULTI, state.level)
        assertTrue(state.isAccountPicker)
        assertEquals(
            listOf("Scripted · 用户a1", "Scripted · 用户a2"),
            state.items.map { it.name },
            "each bound account must be its own top-level folder",
        )
        assertTrue(
            state.items.all { it.isFolder },
            "a cloud must render as a folder, or it cannot be opened",
        )
        assertTrue(
            state.items.all { it.capabilities.canDelete == false },
            "a synthetic cloud row is not a remote object; offering delete on it would be a lie",
        )
    }

    /**
     * A cloud row is opened by *account id*, and doing so lists that cloud.
     *
     * The row's id is the account id rather than a provider id precisely so that two accounts of one
     * provider can be told apart; opening the wrong one would show the other user's files.
     */
    @Test
    fun `opening a cloud row lists that account`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("a1"), account("a2"))
        provider.paginate("root", pages = listOf(listOf("cloud-file")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        val second = viewModel.state.value.items[1]
        viewModel.openFolder(second)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScreenLevel.ACCOUNT_ROOT_SINGLE, state.level)
        assertEquals("a2", state.accountId?.value)
        assertEquals(listOf("cloud-file"), state.items.map { it.name })
    }

    /**
     * Back from a cloud's root returns to the cloud list; back from a folder inside it goes up one
     * level.
     *
     * This is the reported bug — "返回键直接退出APP了 而不是返回上层文件夹" — expressed as the two rules
     * that fix it, at both depths.
     */
    @Test
    fun `navigateUp walks out of a cloud and then up the breadcrumb`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("a1"), account("a2"))
        provider.paginate("root", pages = listOf(listOf("dir-sub")))
        provider.paginate("dir-sub", pages = listOf(listOf("inner-file")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        viewModel.openFolder(viewModel.state.value.items[0])
        advanceUntilIdle()
        viewModel.openFolder(viewModel.state.value.items.single())
        advanceUntilIdle()
        assertEquals(
            listOf("根目录", "dir-sub"),
            viewModel.state.value.breadcrumb.map { it.name },
            "the breadcrumb is the cloud's own path, starting at its synthetic root",
        )

        // Behave as the system back press does: the handler is only installed while `canGoUp`.
        assertTrue(viewModel.state.value.canGoUp, "a folder above means back must be intercepted")
        viewModel.navigateUp()
        advanceUntilIdle()
        assertEquals(
            listOf("根目录"),
            viewModel.state.value.breadcrumb.map { it.name },
            "up from a subfolder lands on the cloud's root, not the picker",
        )

        assertTrue(
            viewModel.state.value.canGoUp,
            "a cloud's root still goes up, because a picker exists above it",
        )
        viewModel.navigateUp()
        advanceUntilIdle()
        assertEquals(
            ScreenLevel.HOME_MULTI,
            viewModel.state.value.level,
            "up from a cloud's root must reveal the cloud list rather than leave the app",
        )
    }

    /**
     * With one account, nothing is above its root, so back must *not* be intercepted.
     *
     * The mirror of the test above: a handler that always consumed would trap the user in a
     * single-account app with no way to exit.
     */
    @Test
    fun `a single account root cannot go up, so back leaves the app`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("only"))
        provider.paginate("root", pages = listOf(listOf("a-file")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        assertFalse(
            viewModel.state.value.canGoUp,
            "with one cloud there is nothing above its root; back must fall through to the system",
        )
    }

    /** No account at all is its own screen, not an empty folder inside a nonexistent cloud. */
    @Test
    fun `no bound account renders the empty home level`() = runTest {
        val provider = ScriptedFileSystemProvider()
        // No accounts bound, and the listing is scripted so a bug that *did* list would be visible.
        provider.paginate("root", pages = listOf(listOf("should-not-appear")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScreenLevel.EMPTY, state.level)
        assertTrue(state.isNoAccounts)
        assertTrue(state.items.isEmpty(), "nothing may be listed when nothing is bound")
        assertNull(state.error, "having no account is an expected state, not a failure")
    }

    // ------------------------------------------------------------------ (e) refresh on resume

    /**
     * Re-reading accounts while browsing a folder must not throw the user back to the root.
     *
     * The route re-reads on `ON_RESUME` (accounts are bound on the settings tab). A naive
     * implementation would re-render the level, which for a single-account app means re-resolving the
     * root — and the user, mid-folder, would silently jump to the top. The rule is therefore
     * "recompute the level, redraw only if it changed".
     */
    @Test
    fun `refreshing accounts keeps the user in the folder they are browsing`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("only"))
        provider.paginate("root", pages = listOf(listOf("dir-sub")))
        provider.paginate("dir-sub", pages = listOf(listOf("inner-file")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()

        viewModel.openFolder(viewModel.state.value.items.single())
        advanceUntilIdle()
        assertEquals(listOf("根目录", "dir-sub"), viewModel.state.value.breadcrumb.map { it.name })

        viewModel.refreshAccounts()
        advanceUntilIdle()

        assertEquals(
            listOf("根目录", "dir-sub"),
            viewModel.state.value.breadcrumb.map { it.name },
            "resuming must not navigate the user back to the root",
        )
        assertEquals(listOf("inner-file"), viewModel.state.value.items.map { it.name })
    }

    /**
     * Binding the first account while the browser is on its empty screen must make it appear.
     *
     * This is the user journey the resume hook exists for: bind in settings, return to files. The
     * local mirror cannot answer it — the newly bound credential exists only on the provider side
     * until the next enumeration — which is why `refreshAccounts` calls `listAccounts` rather than
     * reading `observeAccounts`.
     */
    @Test
    fun `binding an account refreshes the empty home level`() = runTest {
        val provider = ScriptedFileSystemProvider()
        // Start with nothing bound, so the screen is on the empty level.
        val viewModel = createViewModel(provider)
        advanceUntilIdle()
        assertEquals(ScreenLevel.EMPTY, viewModel.state.value.level)

        // The user binds an account in settings; the provider now reports it.
        provider.bindAccounts(account("first"))
        provider.paginate("root", pages = listOf(listOf("now-visible")))

        viewModel.refreshAccounts()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(ScreenLevel.ACCOUNT_ROOT_SINGLE, state.level)
        assertEquals(
            listOf("now-visible"),
            state.items.map { it.name },
            "a newly bound account's contents must appear without restarting the app",
        )
    }

    /**
     * A second bound account turns the open cloud's root into a "there is a picker above me" level.
     *
     * The screen deliberately does not redraw (the user is mid-browse), but up-navigation must now
     * lead somewhere — otherwise the new account would be unreachable until a restart.
     */
    @Test
    fun `a second account makes the open cloud's root navigable upward`() = runTest {
        val provider = ScriptedFileSystemProvider()
        provider.bindAccounts(account("only"))
        provider.paginate("root", pages = listOf(listOf("a-file")))

        val viewModel = createViewModel(provider)
        advanceUntilIdle()
        assertFalse(viewModel.state.value.canGoUp, "one account has nothing above its root")

        provider.bindAccounts(account("only"), account("second"))
        viewModel.refreshAccounts()
        advanceUntilIdle()

        assertTrue(
            viewModel.state.value.canGoUp,
            "with a second account bound, up must now reveal the picker",
        )
        viewModel.navigateUp()
        advanceUntilIdle()
        assertEquals(ScreenLevel.HOME_MULTI, viewModel.state.value.level)
    }

    // ------------------------------------------------------------------ helpers

    private fun TestScope.createViewModel(provider: ScriptedFileSystemProvider): FilesViewModel {
        val registry = DefaultProviderRegistry(listOf(provider))
        val cache = FileMetadataCache(
            dao = InMemoryFileMetadataDao(),
            logger = NoOpCloudLogger,
        )
        val files = CloudFileRepository(
            registry = registry,
            cache = cache,
            logger = NoOpCloudLogger,
        )
        val accounts = AccountRepository(
            registry = registry,
            accountDao = NoOpAccountDao,
            logger = NoOpCloudLogger,
        )
        val settings = NotDiskSettingsStore(dataStore)

        return FilesViewModel(
            files = files,
            accountRepository = accounts,
            settings = settings,
            registry = registry,
        )
    }
}

// ---------------------------------------------------------------------------- fixtures

private val PROVIDER_ID = ProviderId("scripted")
private val ACCOUNT_ID = AccountId("account-1")

/**
 * A bound account on [PROVIDER_ID].
 *
 * `addedAt` is derived from the id so the home level's ordering is deterministic; the display name
 * is derived too, so a test that names two accounts can tell their rows apart without extra setup.
 */
private fun account(id: String) = com.notdisk.core.model.CloudAccount(
    accountId = AccountId(id),
    providerId = PROVIDER_ID,
    remoteUserId = "remote-$id",
    displayName = "用户$id",
    authState = com.notdisk.core.model.AuthState.AUTHORIZED,
    addedAt = 1_700_000_000_000L + id.hashCode().toLong(),
)

/**
 * The auth surface of [ScriptedFileSystemProvider], answering `listAccounts` with a fixed set.
 *
 * `getAccount` is answered from the same set so the ViewModel's "look up the display name" path
 * succeeds; everything else is deliberately unsupported, because nothing else is exercised.
 */
private class ScriptedAuthProvider(
    private val accounts: List<com.notdisk.core.model.CloudAccount>,
) : AuthProvider {

    override suspend fun beginAuthorization(
        flow: com.notdisk.core.model.AuthFlowKind,
        params: com.notdisk.core.provider.AuthFlowParams,
    ): CloudResult<com.notdisk.core.provider.AuthChallenge> =
        CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

    override suspend fun pollChallenge(
        challengeId: String,
    ): CloudResult<com.notdisk.core.provider.AuthChallengeState> =
        CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

    override suspend fun cancelChallenge(challengeId: String): CloudResult<Unit> =
        CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

    override suspend fun verifyAccount(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.CloudAccount> = unsupported()

    override suspend fun getAccount(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.CloudAccount> =
        accounts.firstOrNull { it.accountId == accountId }
            ?.let { CloudResult.Success(it) }
            ?: CloudResult.Failure(CloudError.NotAuthenticated(message = "账号不存在"))

    override suspend fun listAccounts(): CloudResult<List<com.notdisk.core.model.CloudAccount>> =
        CloudResult.Success(accounts)

    override suspend fun refreshProfile(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.CloudAccount> = unsupported()

    override suspend fun getStorageQuota(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.StorageQuota> = unsupported()

    override suspend fun logout(accountId: AccountId): CloudResult<Unit> = unsupported()

    override suspend fun session(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.AuthSession> = unsupported()

    private fun <T> unsupported(): CloudResult<T> =
        CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))
}

private fun folderItem(id: String, name: String = id) = FileItem(    id = FileId(id),
    providerId = PROVIDER_ID,
    accountId = ACCOUNT_ID,
    name = name,
    type = com.notdisk.core.model.FileType.FOLDER,
    capabilities = com.notdisk.core.model.FileCapabilities(canRename = true, canDelete = true),
)

private fun fileItem(id: String, name: String = id) = FileItem(
    id = FileId(id),
    providerId = PROVIDER_ID,
    accountId = ACCOUNT_ID,
    name = name,
    type = com.notdisk.core.model.FileType.FILE,
    capabilities = com.notdisk.core.model.FileCapabilities(canRename = true, canDelete = true),
)

/** One recorded listing call, so a test can assert *that* a request happened and with what. */
private data class ListCall(
    val parentId: String,
    val cursor: String?,
)

/**
 * A hand-written [FileSystemProvider] whose answers each test scripts.
 *
 * Hand-written rather than mocked on purpose: the assertions that matter here are about *how many
 * times* something was called and *which cursor* was sent, and a relaxed mock answers every call
 * successfully — so a ViewModel that re-requested page 1 forever would still pass.
 *
 * ### Determinism
 * Pagination is scripted as an explicit list of pages. `hold(folderId, gate)` suspends a folder's
 * listing until the test opens the gate, which is how "a slow response for a folder the user left"
 * is reproduced without a single millisecond of real waiting.
 */
private class ScriptedFileSystemProvider : CloudProvider {

    override val id: ProviderId = PROVIDER_ID
    override val displayName: String = "Scripted"

    override var auth: AuthProvider = UnusedAuthProvider
    override var media: MediaProvider = UnusedMediaProvider
    override var preview: PreviewProvider = UnusedPreviewProvider

    /**
     * Binds accounts so the home-level rules have something to decide with.
     *
     * Replaces the `auth` surface with a scripted one rather than mocking it: the ViewModel's whole
     * account story runs through `listAccounts`, and a relaxed mock would answer every call
     * successfully — so a ViewModel that ignored the list would still pass.
     */
    fun bindAccounts(vararg accounts: com.notdisk.core.model.CloudAccount) {
        auth = ScriptedAuthProvider(accounts.toList())
    }

    /** Every `listChildren` this provider received, in order. */
    val listCalls: MutableList<ListCall> = mutableListOf()

    /** Capabilities the operations surface declares; a test flips these to test the UI gates. */
    var declaredOperations: FileOperationCapabilities = FileOperationCapabilities(
        createFolder = CapabilitySupport.SUPPORTED,
        rename = CapabilitySupport.SUPPORTED,
        delete = CapabilitySupport.SUPPORTED,
    )

    /** When set, every mutation fails with this error. */
    var operationFailure: CloudError? = null

    private val pages = mutableMapOf<String, List<List<String>>>()
    private val gates = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Unit>>()
    private val failures = mutableMapOf<String, CloudError>()

    override val files: FileSystemProvider = object : FileSystemProvider {
        override suspend fun getRoot(accountId: AccountId): CloudResult<FileItem> =
            CloudResult.Success(folderItem("root", name = "根目录"))

        override suspend fun listChildren(
            accountId: AccountId,
            request: ListChildrenRequest,
        ): CloudResult<FilePage> {
            listCalls += ListCall(
                parentId = request.parentId.value,
                cursor = request.cursor,
            )

            gates[request.parentId.value]?.await()

            failures[request.parentId.value]?.let { return CloudResult.Failure(it) }

            // Unscripted folders get one deterministic row named after the folder, so a test that
            // forgot to script one fails with a readable list rather than an empty screen.
            val scripted = pages[request.parentId.value]
                ?: listOf(listOf("${request.parentId.value}-only"))
            val offset = request.cursor?.toIntOrNull() ?: 0
            val page = scripted.getOrNull(offset) ?: emptyList()
            val nextOffset = offset + 1

            return CloudResult.Success(
                FilePage(
                    items = page.map { name ->
                        if (name.startsWith("dir-")) folderItem(name) else fileItem(name)
                    },
                    nextCursor = if (nextOffset < scripted.size) nextOffset.toString() else null,
                ),
            )
        }

        override suspend fun getFile(accountId: AccountId, fileId: FileId): CloudResult<FileItem> =
            CloudResult.Success(folderItem(fileId.value))

        override suspend fun getPath(
            accountId: AccountId,
            fileId: FileId,
        ): CloudResult<com.notdisk.core.model.FilePath> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun search(
            accountId: AccountId,
            query: FileSearchQuery,
        ): CloudResult<FilePage> = CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun resolvePath(accountId: AccountId, path: String): CloudResult<FileId> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))
    }

    override val operations: FileOperationProvider = object : FileOperationProvider {
        override suspend fun createFolder(
            accountId: AccountId,
            parentId: FileId,
            name: String,
        ): CloudResult<FileItem> = mutationResult { folderItem("created", name = name) }

        override suspend fun rename(
            accountId: AccountId,
            fileId: FileId,
            newName: String,
        ): CloudResult<FileItem> = mutationResult { folderItem(fileId.value, name = newName) }

        override suspend fun move(
            accountId: AccountId,
            fileIds: List<FileId>,
            targetParentId: FileId,
        ): CloudResult<com.notdisk.core.provider.BatchOperationResult> =
            mutationResult { com.notdisk.core.provider.BatchOperationResult() }

        override suspend fun copy(
            accountId: AccountId,
            fileIds: List<FileId>,
            targetParentId: FileId,
        ): CloudResult<com.notdisk.core.provider.BatchOperationResult> =
            mutationResult { com.notdisk.core.provider.BatchOperationResult() }

        override suspend fun delete(
            accountId: AccountId,
            fileIds: List<FileId>,
            permanent: Boolean,
        ): CloudResult<com.notdisk.core.provider.BatchOperationResult> =
            mutationResult { com.notdisk.core.provider.BatchOperationResult() }

        override suspend fun upload(
            accountId: AccountId,
            request: com.notdisk.core.model.UploadRequest,
        ): CloudResult<com.notdisk.core.model.TransferTask> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun prepareDownload(
            accountId: AccountId,
            fileId: FileId,
        ): CloudResult<com.notdisk.core.provider.DownloadSource> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun download(
            accountId: AccountId,
            request: com.notdisk.core.model.DownloadRequest,
            source: com.notdisk.core.provider.DownloadSource,
        ): CloudResult<com.notdisk.core.model.TransferTask> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override fun observeTransfer(transferId: String): kotlinx.coroutines.flow.Flow<com.notdisk.core.model.TransferProgress> =
            kotlinx.coroutines.flow.emptyFlow()

        override suspend fun cancelTransfer(transferId: String): CloudResult<Unit> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun retryTransfer(
            transferId: String,
        ): CloudResult<com.notdisk.core.model.TransferTask> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun share(
            accountId: AccountId,
            fileIds: List<FileId>,
            enable: Boolean,
            password: String?,
        ): CloudResult<com.notdisk.core.provider.ShareResult> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun setStarred(
            accountId: AccountId,
            fileIds: List<FileId>,
            starred: Boolean,
        ): CloudResult<Unit> = CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun listTrash(
            accountId: AccountId,
            cursor: String?,
            pageSize: Int,
        ): CloudResult<com.notdisk.core.provider.TrashPage> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun restoreFromTrash(
            accountId: AccountId,
            trashIds: List<String>,
        ): CloudResult<com.notdisk.core.provider.BatchOperationResult> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))

        override suspend fun purgeFromTrash(
            accountId: AccountId,
            trashIds: List<String>,
            safeKey: String?,
        ): CloudResult<com.notdisk.core.provider.BatchOperationResult> =
            CloudResult.Failure(CloudError.Unsupported(message = "not scripted"))
    }

    override fun capabilities(): CloudCapabilities = CloudCapabilities(
        providerId = id,
        operations = declaredOperations,
    )

    // ------------------------------------------------------------------ scripting

    /** Scripts the pages [folderId] returns: `pages[0]` is page 1, and so on. */
    fun paginate(folderId: String, pages: List<List<String>>) {
        this.pages[folderId] = pages
    }

    /** Makes [folderId]'s listing suspend until [gate] is completed. */
    fun hold(folderId: String, gate: kotlinx.coroutines.CompletableDeferred<Unit>) {
        gates[folderId] = gate
    }

    /** Makes [folderId]'s listing fail with [error]. */
    fun failFor(folderId: String, error: CloudError) {
        failures[folderId] = error
    }

    private inline fun <T> mutationResult(block: () -> T): CloudResult<T> =
        operationFailure?.let { CloudResult.Failure(it) } ?: CloudResult.Success(block())
}

/**
 * A minimal in-memory [FileMetadataDao].
 *
 * The cache is a *real* [FileMetadataCache] over this DAO, so the repository's cache-invalidation
 * path executes for real — only the Room storage is replaced. An in-memory map is sufficient because
 * these tests assert on call counts and on what reached the UI, not on SQL semantics.
 */
private class InMemoryFileMetadataDao : FileMetadataDao {

    private val rows = mutableMapOf<Triple<String, String, String>, FileMetadataEntity>()

    override suspend fun upsertAll(items: List<FileMetadataEntity>) {
        items.forEach { upsert(it) }
    }

    override suspend fun upsert(item: FileMetadataEntity) {
        rows[Triple(item.accountId, item.providerId, item.fileId)] = item
    }

    override suspend fun childrenOf(
        accountId: String,
        providerId: String,
        parentId: String?,
    ): List<FileMetadataEntity> = rows.values.filter {
        it.accountId == accountId && it.providerId == providerId && it.parentId == parentId
    }

    override fun observeChildrenOf(
        accountId: String,
        providerId: String,
        parentId: String?,
    ): kotlinx.coroutines.flow.Flow<List<FileMetadataEntity>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun find(
        accountId: String,
        providerId: String,
        fileId: String,
    ): FileMetadataEntity? = rows[Triple(accountId, providerId, fileId)]

    override suspend fun searchLocal(
        accountId: String,
        providerId: String,
        keyword: String,
        limit: Int,
    ): List<FileMetadataEntity> = emptyList()

    override suspend fun delete(accountId: String, providerId: String, fileId: String) {
        rows.remove(Triple(accountId, providerId, fileId))
    }

    override suspend fun deleteChildrenOf(accountId: String, providerId: String, parentId: String?) {
        rows.entries.removeAll {
            it.value.accountId == accountId &&
                it.value.providerId == providerId &&
                it.value.parentId == parentId
        }
    }

    override suspend fun evictOlderThan(threshold: Long): Int {
        val before = rows.size
        rows.entries.removeAll { it.value.cachedAt < threshold }
        return before - rows.size
    }

    override suspend fun countFor(accountId: String, providerId: String): Int =
        rows.values.count { it.accountId == accountId && it.providerId == providerId }

    override suspend fun clearAll() {
        rows.clear()
    }
}

/** An [com.notdisk.core.database.dao.AccountDao] that stores nothing; accounts are faked. */
private object NoOpAccountDao : com.notdisk.core.database.dao.AccountDao {
    override suspend fun upsert(account: com.notdisk.core.database.entity.AccountEntity) = Unit

    override fun observeAll(): kotlinx.coroutines.flow.Flow<List<com.notdisk.core.database.entity.AccountEntity>> =
        kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun getAll(): List<com.notdisk.core.database.entity.AccountEntity> = emptyList()

    override suspend fun find(
        accountId: String,
        providerId: String,
    ): com.notdisk.core.database.entity.AccountEntity? = null

    override suspend fun findByProvider(
        providerId: String,
    ): List<com.notdisk.core.database.entity.AccountEntity> = emptyList()

    override suspend fun findByRemoteUserId(
        providerId: String,
        remoteUserId: String,
    ): com.notdisk.core.database.entity.AccountEntity? = null

    override suspend fun updateAuthState(accountId: String, providerId: String, authState: String) = Unit

    override suspend fun delete(accountId: String, providerId: String) = Unit

    override suspend fun deleteFiles(accountId: String, providerId: String) = Unit

    override suspend fun deleteRecents(accountId: String, providerId: String) = Unit

    override suspend fun deleteFavourites(accountId: String, providerId: String) = Unit

    override suspend fun deleteTransfers(accountId: String, providerId: String) = Unit
}

/** Sub-providers the file browser never reaches; they fail loudly if a test path does. */
private object UnusedAuthProvider : AuthProvider {
    override suspend fun beginAuthorization(
        flow: com.notdisk.core.model.AuthFlowKind,
        params: com.notdisk.core.provider.AuthFlowParams,
    ): CloudResult<com.notdisk.core.provider.AuthChallenge> = unsupported()

    override suspend fun pollChallenge(
        challengeId: String,
    ): CloudResult<com.notdisk.core.provider.AuthChallengeState> = unsupported()

    override suspend fun cancelChallenge(challengeId: String): CloudResult<Unit> = unsupported()

    override suspend fun verifyAccount(accountId: AccountId): CloudResult<com.notdisk.core.model.CloudAccount> =
        unsupported()

    override suspend fun getAccount(accountId: AccountId): CloudResult<com.notdisk.core.model.CloudAccount> =
        CloudResult.Failure(CloudError.NotAuthenticated(message = "未绑定任何账号"))

    override suspend fun listAccounts(): CloudResult<List<com.notdisk.core.model.CloudAccount>> =
        CloudResult.Success(emptyList())

    override suspend fun refreshProfile(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.CloudAccount> = unsupported()

    override suspend fun getStorageQuota(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.StorageQuota> = unsupported()

    override suspend fun logout(accountId: AccountId): CloudResult<Unit> = unsupported()

    override suspend fun session(
        accountId: AccountId,
    ): CloudResult<com.notdisk.core.model.AuthSession> = unsupported()

    private fun unsupported(): CloudResult<Nothing> =
        CloudResult.Failure(CloudError.Unsupported(message = "not used by the files screen"))
}

private object UnusedMediaProvider : MediaProvider {
    override val providerId: ProviderId = PROVIDER_ID

    override suspend fun resolveMedia(
        accountId: AccountId,
        fileId: FileId,
        qualityHint: String?,
    ): CloudResult<com.notdisk.core.model.MediaResource> = unsupported()

    override suspend fun resolveAudioTrack(
        accountId: AccountId,
        fileId: FileId,
        track: com.notdisk.core.model.MediaAudioTrack,
    ): CloudResult<com.notdisk.core.model.MediaResource> = unsupported()

    override suspend fun reportPlaybackProgress(
        accountId: AccountId,
        fileId: FileId,
        positionMs: Long,
        durationMs: Long?,
    ): CloudResult<Unit> = unsupported()

    override suspend fun getPlaybackProgress(accountId: AccountId, fileId: FileId): CloudResult<Long?> =
        unsupported()

    private fun unsupported(): CloudResult<Nothing> =
        CloudResult.Failure(CloudError.Unsupported(message = "not used by the files screen"))
}

private object UnusedPreviewProvider : PreviewProvider {
    override suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean,
        hint: com.notdisk.core.provider.PreviewHint,
    ): CloudResult<com.notdisk.core.model.PreviewResult> = unsupported()

    override suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: com.notdisk.core.model.ThumbnailRef?,
    ): CloudResult<com.notdisk.core.provider.PreviewResourceOrNull> = unsupported()

    private fun unsupported(): CloudResult<Nothing> =
        CloudResult.Failure(CloudError.Unsupported(message = "not used by the files screen"))
}
