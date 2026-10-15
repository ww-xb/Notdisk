package com.notdisk.feature.files

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.ThemeMode
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ProviderId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * Compose UI tests for the file browser.
 *
 * ### The acceptance criterion these cover
 * The brief requires that "空目录、加载失败、权限不足和网络断开的状态" each be **distinguishable**, and
 * that an empty folder must not look like a failure. Those are exactly the cases that rot: a
 * refactor collapses the branches, every unit test still passes, and the user is left staring at a
 * blank screen wondering whether the app is broken or the folder is genuinely empty.
 *
 * ### Why Robolectric
 * No KVM and no CPU virtualisation in this environment (verified: `/dev/kvm` absent, no `vmx`/`svm`),
 * so an emulator cannot boot. Robolectric runs the real Compose runtime, layout and semantics tree on
 * the JVM, which is sufficient for structural assertions about which branch rendered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FilesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val providerId = ProviderId("p115")
    private val accountId = AccountId("acct-1")

    private fun folder(id: String, name: String) = FileItem(
        id = FileId("folder:115:cid=$id"),
        providerId = providerId,
        accountId = accountId,
        name = name,
        type = FileType.FOLDER,
        capabilities = FileCapabilities(canRename = true, canDelete = true),
    )

    private fun file(id: String, name: String, streamable: Boolean = false) = FileItem(
        id = FileId("file:115:fid=$id:cid=1"),
        providerId = providerId,
        accountId = accountId,
        name = name,
        type = FileType.FILE,
        mimeType = "application/octet-stream",
        extension = "bin",
        size = 1024,
        capabilities = FileCapabilities(canStream = streamable, canRename = true, canDelete = true),
    )

    /** Capabilities with everything available, so the FAB and the item menu are offerable. */
    private val fullCapabilities = FileActionAvailability(
        createFolder = CapabilitySupport.SUPPORTED,
        rename = CapabilitySupport.SUPPORTED,
        delete = CapabilitySupport.SUPPORTED,
        move = CapabilitySupport.SUPPORTED,
    )

    private fun state(
        items: List<FileItem> = emptyList(),
        breadcrumb: List<FileItem> = listOf(folder("0", "根目录")),
        isLoading: Boolean = false,
        isLoadingMore: Boolean = false,
        error: String? = null,
        capabilities: FileActionAvailability = fullCapabilities,
    ) = FilesUiState(
        providerId = providerId,
        accountId = accountId,
        breadcrumb = breadcrumb,
        items = items,
        isLoading = isLoading,
        isLoadingMore = isLoadingMore,
        error = error,
        viewMode = NotDiskSettingsStore.ViewMode.LIST,
        capabilities = capabilities,
        providerDisplayName = "115 网盘",
    )

    private fun render(
        state: FilesUiState,
        onOpenFolder: (FileItem) -> Unit = {},
        onRefresh: () -> Unit = {},
        onCreateFolder: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            NotDiskTheme(themeMode = ThemeMode.LIGHT, dynamicColor = false) {
                FilesScreen(
                    state = state,
                    onNavigateUp = {},
                    onOpenFolder = onOpenFolder,
                    onNavigateToBreadcrumb = {},
                    onRefresh = onRefresh,
                    onLoadMore = {},
                    onCreateFolder = onCreateFolder,
                    onRename = { _, _ -> },
                    onDelete = {},
                    onSetSort = { _, _ -> },
                    onToggleViewMode = {},
                    onDismissError = {},
                    onOpenMedia = { _, _, _ -> },
                    onOpenPreview = { _, _, _ -> },
                )
            }
        }
    }

    /** A folder and a file both render, with their names visible. */
    @Test
    fun `items render with their names`() {
        render(
            state(
                items = listOf(
                    folder("2590058519487052812", "我的文件"),
                    file("3289386448728834697", "cookies.txt"),
                ),
            ),
        )

        composeRule.onNodeWithText("我的文件", substring = true).assertExists()
        composeRule.onNodeWithText("cookies.txt", substring = true).assertExists()
    }

    /**
     * An empty folder must be explained as *empty*, not as an error.
     *
     * This is the brief's "空目录、加载失败…的状态" requirement in its most easily-broken form: an empty
     * list and a failed load both produce zero rows, so if the empty branch were missing the user would
     * see a blank screen with no way to tell which had happened.
     */
    @Test
    fun `an empty folder is explained and is not an error`() {
        render(state(items = emptyList()))

        // The real (and more useful) wording: it names the folder and suggests the next step.
        composeRule.onNodeWithText("这个文件夹是空的", substring = true).assertExists()
        composeRule.onNodeWithText("还没有任何内容", substring = true).assertExists()
        // ...and no error treatment competes with it: "empty" and "failed" must not both be claimed.
        composeRule.onNodeWithText("重新加载", substring = true).assertDoesNotExist()
    }

    /** A failure must be recoverable: the message plus a retry. */
    @Test
    fun `a load failure shows a message and offers retry`() {
        var refreshed = false
        render(
            state = state(error = "网络不可用，请检查网络连接"),
            onRefresh = { refreshed = true },
        )

        composeRule.onNodeWithText("网络不可用", substring = true).assertExists()
        composeRule.onNodeWithText("重新加载", substring = true).assertExists().performClick()
        assertTrue(refreshed, "a failed load must be retryable")
    }

    /** A first load shows a spinner rather than an empty list. */
    @Test
    fun `a first load shows a progress state`() {
        render(state(isLoading = true))

        composeRule.onNodeWithText("正在读取", substring = true).assertExists()
        // The empty state must not also be shown: two contradictory messages at once would be worse
        // than either alone.
        composeRule.onNodeWithText("这个文件夹是空的", substring = true).assertDoesNotExist()
    }

    /** Paging shows a footer indicator without replacing the rows already on screen. */
    @Test
    fun `loading more keeps the existing rows visible`() {
        render(
            state(
                items = listOf(file("1", "first.bin")),
                isLoadingMore = true,
            ),
        )

        composeRule.onNodeWithText("first.bin", substring = true).assertExists()
    }

    /**
     * The create-folder affordance must appear only when the provider declares the capability.
     *
     * The brief forbids presenting an action that cannot work, so a provider without
     * `createFolder = SUPPORTED` must not show the FAB at all.
     */
    @Test
    fun `the create-folder action is hidden when the provider cannot create folders`() {
        render(
            state(
                items = listOf(file("1", "a.bin")),
                capabilities = FileActionAvailability(
                    createFolder = CapabilitySupport.UNSUPPORTED,
                    rename = CapabilitySupport.SUPPORTED,
                    delete = CapabilitySupport.SUPPORTED,
                    move = CapabilitySupport.SUPPORTED,
                ),
            ),
        )

        composeRule.onNodeWithContentDescription("新建文件夹").assertDoesNotExist()
    }

    /** And it *is* present when the capability is declared. */
    @Test
    fun `the create-folder action is offered when supported`() {
        render(state(items = listOf(file("1", "a.bin"))))

        composeRule.onNodeWithContentDescription("新建文件夹").assertExists()
    }

    /** The breadcrumb shows where the user is, and the root when at the root. */
    @Test
    fun `the breadcrumb reflects the current path`() {
        render(
            state(
                items = listOf(file("1", "a.bin")),
                breadcrumb = listOf(
                    folder("0", "根目录"),
                    folder("2590058519487052812", "我的文件"),
                ),
            ),
        )

        // The breadcrumb is one merged, horizontally scrollable row whose content-desc carries the
        // whole path; asserting on that is both unambiguous and closer to what a screen reader says.
        composeRule
            .onNodeWithContentDescription("当前路径：根目录 / 我的文件")
            .assertExists()
    }

    /** Tapping a folder opens it, carrying the real item (whose id the caller needs). */
    @Test
    fun `tapping a folder opens it`() {
        var opened: FileItem? = null
        render(
            state = state(items = listOf(folder("2590058519487052812", "我的文件"))),
            onOpenFolder = { opened = it },
        )

        composeRule.onNodeWithText("我的文件", substring = true).performClick()
        assertTrue(
            opened?.name == "我的文件",
            "opening a folder must pass the item so its provider-scoped id travels with it",
        )
    }
}
