package com.notdisk.feature.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.designsystem.component.CloudFileGridTile
import com.notdisk.core.designsystem.component.CloudFileListTile
import com.notdisk.core.designsystem.component.ConfirmDialog
import com.notdisk.core.designsystem.component.EmptyState
import com.notdisk.core.designsystem.component.ErrorState
import com.notdisk.core.designsystem.component.FilePathBreadcrumb
import com.notdisk.core.designsystem.component.FileGridSkeleton
import com.notdisk.core.designsystem.component.FileListSkeleton
import com.notdisk.core.designsystem.component.LoadingIndicatorRow
import com.notdisk.core.designsystem.component.TextInputDialog
import com.notdisk.core.designsystem.format.CloudDateTimeFormatter
import com.notdisk.core.designsystem.format.FileSizeFormatter
import com.notdisk.core.designsystem.theme.NotDiskTheme
import com.notdisk.core.designsystem.theme.Dimens
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ProviderId

/**
 * The stateful entry point of the file browser.
 *
 * ### Why the screen is split into a route and a stateless composable
 * [FilesScreen] takes a plain value and plain lambdas, so it can be rendered by a `@Preview`, by a
 * screenshot test, and by a future host (search results, a shared-folder deep link) without a Hilt
 * graph. This wrapper is the only place that mentions a `ViewModel` or lifecycle-aware collection,
 * which is what keeps the screen a pure function of its state.
 *
 * ### Why navigation arrives as callbacks and not as a `NavController`
 * `feature:files` must not know that a player or a preview screen exists — the app module owns the
 * graph (see `NotDiskNavHost`). Exposing `(providerId, accountId, fileId)` as three strings is
 * also exactly a `CloudFileKey`, i.e. the only file reference that stays valid across a process
 * death; a serialised `FileItem` would drag a possibly-stale cached row through navigation.
 *
 * @param viewModel the browser's state machine. Injected rather than resolved with a bare
 *   `hiltViewModel()` because the app's navigation host needs the *same* instance to route the
 *   system back press through (see `NotDiskNavHost`); two instances would each keep their own
 *   breadcrumb, and back would appear to do nothing.
 * @param onOpenMedia invoked when the user asks to play a file the provider can stream.
 * @param onOpenPreview invoked when the user asks to preview a file the provider can render inline.
 * @param onOpenAccounts invoked by the empty state's "去绑定账号". It is **required** (no default):
 *   this screen's whole first-run experience depends on that button leading somewhere, and a
 *   defaulted no-op is exactly how it silently stopped working — see [FilesScreen]'s note.
 */
@Composable
fun FilesRoute(
    onOpenMedia: (providerId: String, accountId: String, fileId: String) -> Unit,
    onOpenPreview: (providerId: String, accountId: String, fileId: String) -> Unit,
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FilesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // ### Why the account list is re-read when the screen resumes
    // Accounts are bound on the settings tab (the bottom bar has no accounts tab), and this
    // destination is never popped, so its ViewModel survives that round trip. Without this, a user
    // who bound their first cloud in settings and came back would still see the "no account" screen
    // — and would have to restart the app to see their files.
    //
    // The call is cheap and deliberately position-preserving: it redraws only when the account set
    // actually changes the level (see `FilesViewModel.refreshAccounts`), so answering a permission
    // dialog mid-folder does not throw the user back to the cloud's root.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshAccounts()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ### Why the system back gesture is handled here
    // Folder navigation is not a navigation-graph concern: this ViewModel keeps its own breadcrumb,
    // so `NavHost`'s back stack only ever holds the home screen plus player/preview. Without this
    // handler a back press at folder depth 3 fell straight through to the Activity and closed the
    // app — the reported "返回键直接退出APP了 而不是返回上层文件夹".
    //
    // The handler is *disabled* when `canGoUp` is false, which is what keeps the two directions
    // honest: with a folder above, back goes up; with nothing above (a single-account root, or the
    // empty state), the handler is not installed, so the platform's default — leaving the app —
    // runs. A handler that always consumed would trap the user in the app instead.
    //
    // It lives in this route rather than in the app's nav host because this is where the ViewModel is
    // scoped: resolving the same instance from the host would need `getBackStackEntry`, which throws
    // during the first composition and would give the host a second, divergent back stack.
    BackHandler(enabled = state.canGoUp) {
        viewModel.navigateUp()
    }

    FilesScreen(
        state = state,
        onNavigateUp = viewModel::navigateUp,
        onOpenFolder = viewModel::openFolder,
        onNavigateToBreadcrumb = viewModel::navigateToBreadcrumb,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onCreateFolder = viewModel::createFolder,
        onRename = viewModel::rename,
        onDelete = viewModel::delete,
        onSetSort = viewModel::setSort,
        onToggleViewMode = viewModel::toggleViewMode,
        onDismissError = viewModel::dismissError,
        onOpenMedia = onOpenMedia,
        onOpenPreview = onOpenPreview,
        // Passed straight through. It is deliberately *not* `viewModel::goHome`: that only resets
        // this screen's own level, which is why the button appeared to do nothing.
        onOpenAccounts = onOpenAccounts,
        modifier = modifier,
    )
}

/**
 * The file browser: one folder's contents, in list or grid form, with only the actions the current
 * provider actually supports.
 *
 * ### Why loading, empty and error are three separate branches
 * They answer three different questions, and an app that renders one spinner or one "暂无数据" for
 * all of them makes the user guess which situation they are in:
 *  - **loading** — "is anything happening?" A labelled spinner. It replaces the list only on the
 *    *first* load of a folder; a refresh keeps the rows visible, because blanking a list the user is
 *    reading is a worse lie than showing it a moment out of date.
 *  - **empty** — "is this normal?" A *successful* listing with no rows, with copy that names the
 *    folder and offers the action that makes sense (新建文件夹, when the provider can create one).
 *  - **error** — "what went wrong, and what now?" The provider's own user-facing message, a retry,
 *    and the technical detail behind a disclosure.
 * The three are mutually exclusive by construction, because [FilesUiState] cannot hold two of them
 * at once.
 *
 * ### Why every affordance is capability-gated twice
 * A control that cannot work teaches the user to distrust the whole screen. Each action must pass
 * both gates: the *provider* must declare the capability `SUPPORTED` (via
 * [FilesUiState.capabilities]), and the *item* must permit it (`FileCapabilities.canDelete == true`
 * — `null` means "the provider did not say", which is not permission).
 *
 * ### Why "去绑定账号" is wired to an injected callback, never to a ViewModel method
 * This screen is the app's only route to the login screen for a first-run user (the bottom bar has
 * no accounts tab). It used to call `onGoHome`, which defaulted to `{}` and — once the account
 * manager moved to `设置 → 账号` — only reset the *browser's* own level, so the button did visibly
 * nothing. The action now comes in as a required parameter, so a host that forgets to wire it fails
 * to compile instead of shipping a dead end.
 *
 * @param state the complete screen state. This composable owns no state beyond which dialog, menu
 *   or pending confirmation is open.
 * @param onOpenAccounts opens the account manager. Required for the same reason it is documented
 *   above: a default here would let the dead end come back silently.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    state: FilesUiState,
    onNavigateUp: () -> Unit,
    onOpenFolder: (FileItem) -> Unit,
    onNavigateToBreadcrumb: (Int) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onCreateFolder: (String) -> Unit,
    onRename: (FileItem, String) -> Unit,
    onDelete: (List<FileItem>) -> Unit,
    onSetSort: (FileSortField, Boolean) -> Unit,
    onToggleViewMode: () -> Unit,
    onDismissError: () -> Unit,
    onOpenMedia: (providerId: String, accountId: String, fileId: String) -> Unit,
    onOpenPreview: (providerId: String, accountId: String, fileId: String) -> Unit,
    onOpenAccounts: () -> Unit,
    modifier: Modifier = Modifier,
    onSearchRequested: (() -> Unit)? = null,
) {
    var showCreateFolderDialog by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileItem?>(null) }
    var deleteTargets by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var detailsTarget by remember { mutableStateOf<FileItem?>(null) }
    var sortMenuExpanded by rememberSaveable { mutableStateOf(false) }

    val callbacks = remember(state.capabilities, state.providerId, state.accountId) {
        FileItemCallbacks(
            onOpen = { item ->
                openItem(item, state, onOpenFolder, onOpenMedia, onOpenPreview) { detailsTarget = it }
            },
            onOpenMedia = { item -> onOpenMedia(item.providerId.value, item.accountId.value, item.id.value) },
            onOpenPreview = { item -> onOpenPreview(item.providerId.value, item.accountId.value, item.id.value) },
            onRename = { item -> renameTarget = item },
            onDelete = { item -> deleteTargets = listOf(item) },
            onDetails = { item -> detailsTarget = item },
            capabilities = state.capabilities,
        )
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.titleFor(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    // Rendered only when going up is possible. An always-present back arrow that
                    // sometimes does nothing trains the user to stop trusting it, and at the home
                    // level up would re-render the same screen.
                    if (state.canGoUp) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (state.breadcrumb.size > 1) {
                                    "返回上一级"
                                } else {
                                    "返回账号列表"
                                },
                            )
                        }
                    }
                },
                actions = {
                    // Sorting, view mode and folder creation are listing affordances. At the home
                    // level the rows are clouds, so none of them has a meaning there and the whole
                    // action row is hidden rather than shown inert.
                    if (state.isListingFolder) {
                        IconButton(onClick = onRefresh, enabled = !state.isLoading && !state.isRefreshing) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = onToggleViewMode) {
                            val toGrid = state.viewMode == NotDiskSettingsStore.ViewMode.LIST
                            Icon(
                                imageVector = if (toGrid) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                                contentDescription = if (toGrid) "切换到网格视图" else "切换到列表视图",
                            )
                        }
                        Box {
                            IconButton(onClick = { sortMenuExpanded = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序方式")
                            }
                            SortMenu(
                                expanded = sortMenuExpanded,
                                current = state.sort,
                                onDismiss = { sortMenuExpanded = false },
                                onSelect = { field, ascending ->
                                    sortMenuExpanded = false
                                    onSetSort(field, ascending)
                                },
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh, enabled = !state.isLoading) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新账号列表")
                        }
                    }
                    // Hidden entirely rather than disabled: a search that cannot run is not a
                    // "currently unavailable" control, it is a provider that has no such endpoint.
                    if (state.capabilities.canSearch && onSearchRequested != null) {
                        IconButton(onClick = onSearchRequested) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            // A folder is created *inside a cloud*, so the affordance only exists once one is open.
            // At the home level there would be no account to create it in.
            if (state.isListingFolder && state.capabilities.canCreateFolder) {
                FloatingActionButton(onClick = { showCreateFolderDialog = true }) {
                    Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.isListingFolder && state.breadcrumb.isNotEmpty()) {
                // Cross-faded on the *path* rather than on the state: the breadcrumb is rebuilt for
                // every emitted state, but it only looks different when the user actually moved.
                Crossfade(
                    targetState = state.breadcrumb.map { it.id.value },
                    animationSpec = tween(durationMillis = BREADCRUMB_FADE_MILLIS),
                    label = "breadcrumb",
                ) {
                    FilePathBreadcrumb(
                        // `id to label`: the id is what navigation uses, the label is what the user
                        // reads. The root's own name is the cloud's name, not an empty string.
                        path = state.breadcrumb.map { it.id.value to it.name },
                        onCrumbClick = { crumbId ->
                            val index = state.breadcrumb.indexOfFirst { it.id.value == crumbId }
                            if (index >= 0) onNavigateToBreadcrumb(index)
                        },
                    )
                }
                HorizontalDivider(
                    thickness = Dimens.Divider,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }

            // A refresh replaces neither the rows nor the breadcrumb, so without this the gesture
            // would look like it did nothing at all until the list quietly changed. The bar owns a
            // fixed 6dp of layout whether or not it is running, and only its *alpha* animates — so a
            // refresh fades in instead of appearing as a hard 6dp jump, and the list below never
            // moves. It is not shown at the home level, where there is no listing to refresh.
            if (state.isListingFolder) {
                val refreshing = state.isRefreshing
                val progressAlpha by animateFloatAsState(
                    targetValue = if (refreshing) 1f else 0f,
                    animationSpec = tween(durationMillis = PROGRESS_FADE_MILLIS),
                    label = "refresh-progress-alpha",
                )
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(Dimens.ProgressTrack)
                        .alpha(progressAlpha),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }

            // A failure while rows are already visible must not erase them: the user keeps their
            // context and gets a dismissible explanation instead of losing their place. It slides in
            // rather than jumping the list, so a mid-scroll failure does not shove the rows the user
            // was reading under their finger.
            //
            // It sits *above* the content because that content is `fillMaxSize`: composing the strip
            // after it would place it below the visible area, where it explained a failure nobody
            // could see. The expand/shrink animation keeps that honest — the row list is pushed down
            // by exactly the strip's height, and returns when it is dismissed.
            AnimatedVisibility(
                visible = state.error != null && state.items.isNotEmpty(),
                enter = expandVertically(animationSpec = tween(TRANSITION_MILLIS)) +
                    fadeIn(animationSpec = tween(TRANSITION_MILLIS)),
                exit = shrinkVertically(animationSpec = tween(TRANSITION_MILLIS)) +
                    fadeOut(animationSpec = tween(TRANSITION_MILLIS)),
            ) {
                InlineFailure(
                    message = state.error.orEmpty(),
                    detail = state.errorDetail,
                    onDismiss = onDismissError,
                    onRetry = {
                        onDismissError()
                        onRefresh()
                    },
                )
            }

            // ### Why the content is cross-faded between its shapes
            // Every one of these branches used to snap into place: tapping a folder blanked the
            // list for the whole provider round trip and then painted the new rows in a single
            // frame. That is the "生硬" the user reported. Crossfading keyed on the *shape* of the
            // content (not on the state object, which changes on every emitted byte) fades one
            // presentation out while the next fades in, and — because a skeleton is the loading
            // shape rather than an empty box — something is on screen for the entire wait.
            Crossfade(
                targetState = state.contentMode(),
                animationSpec = tween(durationMillis = CONTENT_FADE_MILLIS),
                label = "files-content",
            ) { mode ->
                when (mode) {
                    ContentMode.LOADING -> FolderSkeleton(
                        state = state,
                        label = state.currentFolder?.name?.let { "正在读取「$it」…" } ?: "正在读取…",
                        modifier = Modifier.fillMaxSize(),
                    )

                    ContentMode.ERROR ->
                        ErrorState(
                            userMessage = state.error.orEmpty(),
                            detail = state.errorDetail,
                            onRetry = {
                                onDismissError()
                                onRefresh()
                            },
                            retryLabel = "重新加载",
                            modifier = Modifier.fillMaxSize(),
                        )

                    ContentMode.NO_ACCOUNTS ->
                        // **The** first-run screen: no account is bound. It must say how to bind one
                        // *and* offer the route, because the bottom bar deliberately has no accounts
                        // tab and a user who cannot find the login screen has no way forward.
                        EmptyState(
                            title = "还没有绑定网盘账号",
                            icon = Icons.Filled.FolderOff,
                            description = "到「设置 → 账号」中登录 115 网盘。只有一个账号时，这里会直接显示该网盘的内容；" +
                                "绑定多个账号后，会先按网盘名分组显示。",
                            actionLabel = "去绑定账号",
                            onAction = onOpenAccounts,
                            modifier = Modifier.fillMaxSize(),
                        )

                    ContentMode.ACCOUNT_PICKER ->
                        // Two or more clouds: the rows *are* the clouds, so an empty list here can
                        // only mean the account list is mid-update.
                        if (state.items.isEmpty()) {
                            // Mid-update is a *loading* state, not an empty one: showing "正在准备
                            // 账号列表" with a 刷新 button presented a wait as a dead end.
                            FolderSkeleton(
                                state = state,
                                label = "正在准备账号列表…",
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            FileContent(
                                state = state,
                                onLoadMore = onLoadMore,
                                callbacks = callbacks,
                            )
                        }

                    ContentMode.EMPTY_FOLDER ->
                        EmptyState(
                            title = "这个文件夹是空的",
                            icon = Icons.Filled.FolderOff,
                            description = buildString {
                                append("「")
                                append(state.currentFolder?.name ?: "当前文件夹")
                                append("」中还没有任何内容。")
                                if (state.capabilities.canCreateFolder) {
                                    append("你可以新建一个文件夹开始整理。")
                                } else {
                                    append("请在对应的网盘 App 中上传文件后刷新。")
                                }
                            },
                            actionLabel = if (state.capabilities.canCreateFolder) "新建文件夹" else null,
                            onAction = if (state.capabilities.canCreateFolder) {
                                { showCreateFolderDialog = true }
                            } else {
                                null
                            },
                            modifier = Modifier.fillMaxSize(),
                        )

                    ContentMode.CONTENT -> FileContent(
                        state = state,
                        onLoadMore = onLoadMore,
                        callbacks = callbacks,
                    )
                }
            }
        }
    }

    TextInputDialog(
        title = "新建文件夹",
        visible = showCreateFolderDialog,
        onDismiss = { showCreateFolderDialog = false },
        onConfirm = { name ->
            showCreateFolderDialog = false
            onCreateFolder(name)
        },
        label = "文件夹名称",
        helperText = "名称随时可以修改；若网盘不允许某些字符，会由服务端拒绝并给出提示。",
        confirmLabel = "创建",
        enabled = !state.isMutating,
    )

    val renaming = renameTarget
    TextInputDialog(
        title = "重命名",
        visible = renaming != null,
        onDismiss = { renameTarget = null },
        onConfirm = { newName ->
            renaming?.let { onRename(it, newName) }
            renameTarget = null
        },
        // Pre-filled *and* pre-selected by `TextInputDialog`, so typing replaces the old name
        // instead of appending to it.
        initialValue = renaming?.name.orEmpty(),
        label = "新名称",
        confirmLabel = "重命名",
        enabled = !state.isMutating,
    )

    ConfirmDialog(
        title = deleteTargets.singleOrNull()?.let { "删除「${it.name}」？" }
            ?: "删除选中的 ${deleteTargets.size} 个项目？",
        // The message states the real consequence. Promising a recycle bin for a provider that has
        // none is a lie the user only discovers once the file is unrecoverable.
        message = if (state.capabilities.deleteIsRecoverable) {
            "该项目将被移入网盘的回收站，之后可以在回收站中恢复。"
        } else {
            "该网盘未声明回收站能力，删除后可能无法恢复，请确认。"
        },
        visible = deleteTargets.isNotEmpty(),
        onDismiss = { deleteTargets = emptyList() },
        onConfirm = {
            onDelete(deleteTargets)
            deleteTargets = emptyList()
        },
        confirmLabel = "删除",
        destructive = true,
    )

    detailsTarget?.let { details ->
        FileDetailsDialog(item = details, onDismiss = { detailsTarget = null })
    }
}

/**
 * The rows themselves, in whichever presentation the user chose.
 *
 * Split out of [FilesScreen] because the branch above answers *whether* there is content while this
 * answers *what it looks like*; the two change for different reasons.
 *
 * ### Why the account picker ignores the grid preference
 * The grid is a way to scan *many* files visually. The home level holds a handful of clouds, each of
 * which needs its *name* read to be useful — a grid of unlabelled tiles would be strictly worse. The
 * chosen view mode therefore resumes as soon as a cloud is opened.
 */
@Composable
private fun FileContent(
    state: FilesUiState,
    onLoadMore: () -> Unit,
    callbacks: FileItemCallbacks,
) {
    if (state.isAccountPicker) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Dimens.XSmall),
        ) {
            items(items = state.items, key = { it.id.value }) { account ->
                CloudAccountRow(account = account, onClick = { callbacks.onOpen(account) })
            }
        }
        return
    }

    // ### Why the list/grid swap is cross-faded too
    // Switching presentation used to hard-cut: the same files, re-laid out in a single frame, with
    // no signal that the two views hold the same content. A short cross-fade makes it read as one
    // list changing shape rather than as a navigation to a different screen — and the AppBar's
    // toggle button, which is what the user just pressed, stays put above it.
    Crossfade(
        targetState = state.viewMode,
        animationSpec = tween(durationMillis = VIEW_MODE_FADE_MILLIS),
        label = "view-mode",
    ) { mode ->
        when (mode) {
            NotDiskSettingsStore.ViewMode.LIST -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                // Room for the FAB, so the last row is not permanently covered by it.
                contentPadding = PaddingValues(bottom = Dimens.Huge),
            ) {
                items(items = state.items, key = { it.id.value }) { item ->
                    FileRow(item = item, callbacks = callbacks)
                }
                pagingFooter(state = state, onLoadMore = onLoadMore)
            }

            NotDiskSettingsStore.ViewMode.GRID -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = GRID_CELL_MIN_WIDTH),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Dimens.Medium),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
                verticalArrangement = Arrangement.spacedBy(Dimens.Medium),
            ) {
                items(items = state.items, key = { it.id.value }) { item ->
                    FileCell(item = item, callbacks = callbacks)
                }
                item(key = PAGING_FOOTER_KEY, span = { GridItemSpan(maxLineSpan) }) {
                    PagingFooter(state = state, onLoadMore = onLoadMore)
                }
            }
        }
    }
}

/**
 * One row of the home level: a bound cloud, presented as a top-level folder.
 *
 * The row is a *synthetic* folder (see `FilesViewModel.homeItemsFor`), so every per-item action a
 * real remote object has — rename, delete, details, play — is deliberately absent. Only "open" is
 * offered, which is exactly what the row means.
 */
@Composable
private fun CloudAccountRow(account: FileItem, onClick: () -> Unit) {
    CloudFileListTile(
        name = account.name,
        isFolder = true,
        onClick = onClick,
        sizeText = null,
        // The subtitle names the cloud's own id rather than a count, because with two accounts of
        // the same provider the *name* is the only thing that tells them apart, and the row above
        // already shows it. Showing the provider id here is the honest secondary line.
        subtitleText = account.providerId.value,
        mimeType = null,
        enabled = true,
    )
}

/** The screen title for the level currently on screen. */
private fun FilesUiState.titleFor(): String = when {
    isAccountPicker -> "全部网盘"
    isNoAccounts -> "文件"
    else -> currentFolder?.name ?: "文件"
}

/**
 * The mutually exclusive shapes the content area can take.
 *
 * ### Why this is a value rather than the `when` that used to be inline
 * The transition animation must key on *which screen is showing*, not on the state object: a state
 * is re-emitted for a cursor, a cache origin or an error detail, and cross-fading on every one of
 * those would make the list flicker while nothing visible changed. Deriving a small enum makes the
 * animation trigger exactly the real transitions — list ⇄ skeleton, list ⇄ error, list ⇄ empty.
 *
 * The order of the branches is the priority order, and it is the same one the composable rendered
 * before this enum existed: while a first load is in flight the spinner (now the skeleton) wins over
 * a stale error, an error wins over "no accounts", and so on.
 */
private enum class ContentMode {
    /** First load of a folder, or a home level whose accounts are still being read. */
    LOADING,

    /** Nothing is on screen and the last attempt failed. */
    ERROR,

    /** No cloud is bound at all. */
    NO_ACCOUNTS,

    /** Two or more clouds, but their rows are not ready yet. */
    ACCOUNT_PICKER,

    /** A successful listing with no rows. */
    EMPTY_FOLDER,

    /** Rows are on screen. */
    CONTENT,
}

private fun FilesUiState.contentMode(): ContentMode = when {
    isLoading && items.isEmpty() -> ContentMode.LOADING
    error != null && items.isEmpty() -> ContentMode.ERROR
    isNoAccounts -> ContentMode.NO_ACCOUNTS
    isAccountPicker -> ContentMode.ACCOUNT_PICKER
    items.isEmpty() -> ContentMode.EMPTY_FOLDER
    else -> ContentMode.CONTENT
}

/**
 * The placeholder list shown while the first load of a folder is in flight.
 *
 * ### Why this is not just `FileListSkeleton`
 * The skeleton has to mirror the *presentation the user chose*: showing list-shaped rows to someone
 * who prefers the grid would make the content jump twice — once when the layout switches, and again
 * when the rows arrive. It also has to mirror the level: the account picker's rows are clouds (a
 * plain list), so honouring the stored grid preference there would promise a layout the picker
 * deliberately never uses.
 */
@Composable
private fun FolderSkeleton(
    state: FilesUiState,
    label: String,
    modifier: Modifier = Modifier,
) {
    val useGrid = state.isListingFolder && state.viewMode == NotDiskSettingsStore.ViewMode.GRID
    if (useGrid) {
        FileGridSkeleton(modifier = modifier, label = label)
    } else {
        FileListSkeleton(modifier = modifier, label = label)
    }
}

/**
 * The last element of a list: a spinner while page N+1 is in flight, and the trigger that asks for
 * it.
 *
 * ### Why the trigger is this element rather than a scroll listener
 * `LazyColumn`/`LazyVerticalGrid` compose only what is near the viewport, so *being composed* is
 * already the signal "the user reached the end" — and it fires once per cursor rather than once per
 * scroll frame. That avoids a hand-rolled `derivedStateOf` over scroll positions (which is easy to
 * get wrong at the boundary) and it cannot fire when the provider has said there is no next page,
 * because the footer is not composed at all then.
 */
private fun LazyListScope.pagingFooter(
    state: FilesUiState,
    onLoadMore: () -> Unit,
) {
    if (state.isLoadingMore || state.canLoadMore) {
        item(key = PAGING_FOOTER_KEY) {
            PagingFooter(state = state, onLoadMore = onLoadMore)
        }
    }
}

/** The shared paging footer, used by both layouts. */
@Composable
private fun PagingFooter(state: FilesUiState, onLoadMore: () -> Unit) {
    if (state.isLoadingMore) {
        LoadingIndicatorRow()
        return
    }
    if (state.canLoadMore) {
        // Keyed on the cursor, so each page is requested exactly once even if the user scrolls away
        // and back.
        LaunchedEffect(state.nextCursor) { onLoadMore() }
        LoadingIndicatorRow(label = "正在加载更多…")
    }
}

/** One list row, with the per-item affordances the provider's capabilities allow. */
@Composable
private fun FileRow(item: FileItem, callbacks: FileItemCallbacks) {
    var menuExpanded by remember { mutableStateOf(false) }

    CloudFileListTile(
        name = item.name,
        isFolder = item.isFolder,
        onClick = { callbacks.onOpen(item) },
        // Long-press *and* an explicit overflow button: the gesture alone is undiscoverable, and it
        // is unreachable for a screen-reader user without the button.
        onLongClick = { menuExpanded = true },
        sizeText = item.size?.let(FileSizeFormatter::formatBytes),
        subtitleText = FileTimestamp.subtitleFor(item),
        mimeType = item.mimeType,
        enabled = item.isSelectable(),
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "更多操作：${item.name}")
                }
                FileActionMenu(
                    item = item,
                    expanded = menuExpanded,
                    callbacks = callbacks,
                    onDismiss = { menuExpanded = false },
                )
            }
        },
    )
}

/** One grid cell. Grid mode trades the metadata line for a bigger thumbnail. */
@Composable
private fun FileCell(item: FileItem, callbacks: FileItemCallbacks) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        CloudFileGridTile(
            name = item.name,
            isFolder = item.isFolder,
            onClick = { callbacks.onOpen(item) },
            onLongClick = { menuExpanded = true },
            sizeText = item.size?.let(FileSizeFormatter::formatBytes),
            mimeType = item.mimeType,
            enabled = item.isSelectable(),
            overlayContent = {
                // The tile's own overlay slot is not a `BoxScope`, so the alignment is done here.
                Box(modifier = Modifier.fillMaxSize()) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多操作：${item.name}")
                    }
                }
            },
        )
        FileActionMenu(
            item = item,
            expanded = menuExpanded,
            callbacks = callbacks,
            onDismiss = { menuExpanded = false },
        )
    }
}

/**
 * The per-item action menu.
 *
 * ### Why the entries are computed from capabilities rather than from the file type
 * Two independent gates must both pass, and each catches a different real situation:
 *  - the *provider* must offer the operation at all (a cloud whose API has no rename endpoint must
 *    not show 重命名, and one that cannot stream must not show 播放);
 *  - the *item* must permit it — a read-only shared file reports `canRename = false`, and `null`
 *    means "the provider did not say", which is treated as "do not offer" rather than as consent.
 * Showing an entry that fails either gate is how a user learns that this app's menus cannot be
 * trusted.
 */
@Composable
private fun FileActionMenu(
    item: FileItem,
    expanded: Boolean,
    callbacks: FileItemCallbacks,
    onDismiss: () -> Unit,
) {
    val capabilities = callbacks.capabilities

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        MenuAction("打开", Icons.Filled.Visibility) {
            onDismiss()
            callbacks.onOpen(item)
        }

        if (!item.isFolder && capabilities.canStream && item.capabilities.canStream == true) {
            MenuAction("播放", Icons.Filled.PlayArrow) {
                onDismiss()
                callbacks.onOpenMedia(item)
            }
        }

        if (!item.isFolder && capabilities.canPreview && item.capabilities.canPreview == true) {
            MenuAction("预览", Icons.Filled.Visibility) {
                onDismiss()
                callbacks.onOpenPreview(item)
            }
        }

        if (capabilities.canRename && item.capabilities.canRename == true) {
            MenuAction("重命名", Icons.Filled.Edit) {
                onDismiss()
                callbacks.onRename(item)
            }
        }

        if (capabilities.canDelete && item.capabilities.canDelete == true) {
            MenuAction("删除", Icons.Filled.Delete) {
                onDismiss()
                callbacks.onDelete(item)
            }
        }

        MenuAction("详情", Icons.Filled.Info) {
            onDismiss()
            callbacks.onDetails(item)
        }
    }
}

@Composable
private fun MenuAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}

/** The sort menu, built from [FileSortField] so its choices cannot drift from the model. */
@Composable
private fun SortMenu(
    expanded: Boolean,
    current: FileSort,
    onDismiss: () -> Unit,
    onSelect: (FileSortField, Boolean) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        FileSortField.entries.forEach { field ->
            val selected = current.field == field
            DropdownMenuItem(
                text = { Text(field.chineseLabel() + if (selected) "  ✓" else "") },
                onClick = {
                    // Re-selecting the active field flips direction, which is what a user expects
                    // from a sort menu that has no separate direction control.
                    onSelect(field, if (selected) !current.ascending else true)
                },
            )
        }
    }
}

/** A compact failure strip shown *above* a populated list. */
@Composable
private fun InlineFailure(
    message: String,
    detail: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    // The strip is a `Column` of intrinsic height, not a `Box` with a fixed size: the optional
    // `detail` line is what makes it taller on a technical failure, and the enter/exit animation at
    // the call site measures whatever height this produces.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = Dimens.Large, vertical = Dimens.Small),
        verticalArrangement = Arrangement.spacedBy(Dimens.XSmall),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
        if (!detail.isNullOrBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.Small)) {
            TextButton(onClick = onRetry) { Text("重试") }
            TextButton(onClick = onDismiss) { Text("忽略") }
        }
    }
}

/** A read-only details panel. Nothing is editable here, so it is a dialog and not a screen. */
@Composable
private fun FileDetailsDialog(item: FileItem, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.XSmall)) {
                DetailLine("类型", if (item.isFolder) "文件夹" else item.extension?.uppercase() ?: "文件")
                DetailLine("大小", item.size?.let(FileSizeFormatter::formatBytes) ?: "未提供")
                DetailLine("创建时间", CloudDateTimeFormatter.formatAbsolute(item.createdTime))
                DetailLine("修改时间", CloudDateTimeFormatter.formatAbsolute(item.modifiedTime))
                DetailLine("网盘", item.providerId.value)
                DetailLine("文件 ID", item.id.value)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Medium),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DETAIL_LABEL_WIDTH),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The per-item callbacks, bundled so both layouts share one signature. */
private data class FileItemCallbacks(
    val onOpen: (FileItem) -> Unit,
    val onOpenMedia: (FileItem) -> Unit,
    val onOpenPreview: (FileItem) -> Unit,
    val onRename: (FileItem) -> Unit,
    val onDelete: (FileItem) -> Unit,
    val onDetails: (FileItem) -> Unit,
    val capabilities: FileActionAvailability,
)

/**
 * Whether the user may interact with this item at all.
 *
 * A provider can report an item as blocked (moderation, a share that was revoked, a file in a
 * read-only mount). `FileCapabilities.isBlocked` is nullable, and `null` means "not reported",
 * which is *not* the same as blocked — treating an unreported flag as blocked would grey out every
 * row on a provider that simply does not populate it.
 */
private fun FileItem.isSelectable(): Boolean = capabilities.isBlocked != true

/**
 * What a tap on a row means.
 *
 * A folder navigates; a playable file plays; a previewable file previews; anything else opens its
 * details, because a tap that does *nothing* reads as a broken screen. The order matters: playing
 * beats previewing for media, since a video's "preview" is playback anyway.
 */
private fun openItem(
    item: FileItem,
    state: FilesUiState,
    onOpenFolder: (FileItem) -> Unit,
    onOpenMedia: (providerId: String, accountId: String, fileId: String) -> Unit,
    onOpenPreview: (providerId: String, accountId: String, fileId: String) -> Unit,
    onDetails: (FileItem) -> Unit,
) {
    when {
        item.isFolder -> onOpenFolder(item)

        item.isSelectable() && state.capabilities.canStream && item.capabilities.canStream == true ->
            onOpenMedia(item.providerId.value, item.accountId.value, item.id.value)

        item.isSelectable() && state.capabilities.canPreview && item.capabilities.canPreview == true ->
            onOpenPreview(item.providerId.value, item.accountId.value, item.id.value)

        else -> onDetails(item)
    }
}

/** Chinese labels for the sort fields. Here, not on the enum: `core:model` carries no UI text. */
private fun FileSortField.chineseLabel(): String = when (this) {
    FileSortField.NAME -> "按名称"
    FileSortField.MODIFIED_TIME -> "按修改时间"
    FileSortField.CREATED_TIME -> "按创建时间"
    FileSortField.SIZE -> "按大小"
    FileSortField.TYPE -> "按类型"
}

/**
 * The one-line timestamp a row shows.
 *
 * The relative ladder from [CloudDateTimeFormatter] answers "is this the version I just uploaded?"
 * far better than an absolute date — but "3 分钟前" is useless when comparing a folder against a
 * backup, so a folder shows its exact modification date instead.
 */
private object FileTimestamp {
    fun subtitleFor(item: FileItem): String? {
        val modified = item.modifiedTime ?: return null
        return if (item.isFolder) {
            CloudDateTimeFormatter.formatDate(modified)
        } else {
            CloudDateTimeFormatter.format(modified)
        }
    }
}

/** One key for the single synthetic footer, so Compose can reuse it across recompositions. */
private const val PAGING_FOOTER_KEY = "paging-footer"

/**
 * How long the file list and the file grid take to cross-fade into each other.
 *
 * Fast enough to feel like the list *settled* rather than like a screen transition the user has to
 * wait out; slow enough that the swap is not a hard cut. It is deliberately shorter than Material's
 * default medium duration for the same reason.
 */
private const val CONTENT_FADE_MILLIS = 220

/** Duration of the inline failure strip's expand/shrink, matched to [CONTENT_FADE_MILLIS]. */
private const val TRANSITION_MILLIS = 220

/** Fade of the refresh progress bar. Long enough to read as a fade, short enough to not lag input. */
private const val PROGRESS_FADE_MILLIS = 180

/** Fade of the breadcrumb when the user actually moves. A hard cut here reads as a jolt. */
private const val BREADCRUMB_FADE_MILLIS = 180

/**
 * Fade between the list and the grid presentation.
 *
 * Slightly longer than the other fades because two entirely different layouts are swapping places:
 * at 180ms the eye reads the grid's cells as the list's rows "jumping", which is the opposite of the
 * intent.
 */
private const val VIEW_MODE_FADE_MILLIS = 260

/** Grid cells are at least this wide; `Adaptive` then fits 2–4 columns on a phone. */
private val GRID_CELL_MIN_WIDTH = 108.dp

private val DETAIL_LABEL_WIDTH = 72.dp

/** Unused-import guard keeping [FileCapabilities] documented as part of the item contract. */
@Suppress("unused")
private val fileCapabilitiesDoc: FileCapabilities = FileCapabilities.Unknown

/** Unused-import guard keeping [FileType] documented as the folder/file discriminator. */
@Suppress("unused")
private val fileTypeDoc: FileType = FileType.FILE

// ---------------------------------------------------------------------------- previews

/**
 * A fake state for the previews below and for screenshot tooling.
 *
 * A function rather than a constant so a caller can vary exactly one field. The rows are chosen to
 * make every branch reachable: a folder, a streamable file, a preview-only file, and a file whose
 * size the provider did not report.
 */
private fun previewState(
    items: List<FileItem> = previewItems(),
    isLoading: Boolean = false,
    error: String? = null,
) = FilesUiState(
    providerId = ProviderId("mock"),
    accountId = AccountId("preview-account"),
    breadcrumb = listOf(
        previewFolder(id = "root", name = "根目录"),
        previewFolder(id = "media", name = "媒体"),
    ),
    items = items,
    isLoading = isLoading,
    error = error,
    errorDetail = error?.let { "ServerError · HTTP 502 · code=UPSTREAM" },
    capabilities = FileActionAvailability(
        createFolder = CapabilitySupport.SUPPORTED,
        rename = CapabilitySupport.SUPPORTED,
        delete = CapabilitySupport.SUPPORTED,
        search = CapabilitySupport.UNVERIFIED,
        trash = CapabilitySupport.SUPPORTED,
        stream = CapabilitySupport.SUPPORTED,
        preview = CapabilitySupport.SUPPORTED,
    ),
    providerDisplayName = "离线演示网盘 (Mock)",
    accountDisplayName = "Mock 用户",
)

private fun previewFolder(id: String, name: String) = FileItem(
    id = FileId(id),
    providerId = ProviderId("mock"),
    accountId = AccountId("preview-account"),
    name = name,
    type = FileType.FOLDER,
    modifiedTime = 1_700_000_000_000L,
    capabilities = FileCapabilities(canRename = true, canDelete = true),
)

private fun previewItems(): List<FileItem> = listOf(
    previewFolder(id = "sub", name = "电视剧"),
    previewFile(
        id = "video",
        name = "sample-1080p.mp4",
        size = 1_036_870_843L,
        extension = "mp4",
        mimeType = "video/mp4",
        canStream = true,
        canPreview = true,
    ),
    previewFile(
        id = "image",
        name = "shot.jpg",
        size = 3_500_000L,
        extension = "jpg",
        mimeType = "image/jpeg",
        canStream = false,
        canPreview = true,
    ),
    previewFile(
        id = "unknown-size",
        name = "data.bin",
        size = null,
        extension = "bin",
        mimeType = "application/octet-stream",
        canStream = false,
        canPreview = false,
    ),
)

private fun previewFile(
    id: String,
    name: String,
    size: Long?,
    extension: String,
    mimeType: String,
    canStream: Boolean,
    canPreview: Boolean,
) = FileItem(
    id = FileId(id),
    providerId = ProviderId("mock"),
    accountId = AccountId("preview-account"),
    name = name,
    type = FileType.FILE,
    mimeType = mimeType,
    extension = extension,
    size = size,
    modifiedTime = 1_700_000_000_000L,
    capabilities = FileCapabilities(
        canStream = canStream,
        canPreview = canPreview,
        canRename = true,
        canDelete = true,
    ),
)

/** The populated list view. */
@Preview(name = "文件列表 (列表视图)", showBackground = true)
@Composable
private fun FilesScreenListPreview() {
    NotDiskTheme(dynamicColor = false) {
        FilesScreen(
            state = previewState(),
            onNavigateUp = {},
            onOpenFolder = {},
            onNavigateToBreadcrumb = {},
            onRefresh = {},
            onLoadMore = {},
            onCreateFolder = {},
            onRename = { _, _ -> },
            onDelete = {},
            onSetSort = { _, _ -> },
            onToggleViewMode = {},
            onDismissError = {},
            onOpenMedia = { _, _, _ -> },
            onOpenPreview = { _, _, _ -> },
            onOpenAccounts = {},
        )
    }
}

/** The grid view, same state, different presentation. */
@Preview(name = "文件列表 (网格视图)", showBackground = true)
@Composable
private fun FilesScreenGridPreview() {
    NotDiskTheme(dynamicColor = false) {
        FilesScreen(
            state = previewState().copy(viewMode = NotDiskSettingsStore.ViewMode.GRID),
            onNavigateUp = {},
            onOpenFolder = {},
            onNavigateToBreadcrumb = {},
            onRefresh = {},
            onLoadMore = {},
            onCreateFolder = {},
            onRename = { _, _ -> },
            onDelete = {},
            onSetSort = { _, _ -> },
            onToggleViewMode = {},
            onDismissError = {},
            onOpenMedia = { _, _, _ -> },
            onOpenPreview = { _, _, _ -> },
            onOpenAccounts = {},
        )
    }
}

/** The empty folder — reachable, and visibly different from both loading and failure. */
@Preview(name = "空文件夹", showBackground = true)
@Composable
private fun FilesScreenEmptyPreview() {
    NotDiskTheme(dynamicColor = false) {
        FilesScreen(
            state = previewState(items = emptyList()),
            onNavigateUp = {},
            onOpenFolder = {},
            onNavigateToBreadcrumb = {},
            onRefresh = {},
            onLoadMore = {},
            onCreateFolder = {},
            onRename = { _, _ -> },
            onDelete = {},
            onSetSort = { _, _ -> },
            onToggleViewMode = {},
            onDismissError = {},
            onOpenMedia = { _, _, _ -> },
            onOpenPreview = { _, _, _ -> },
            onOpenAccounts = {},
        )
    }
}

/** The failure state, which must not be mistakable for "empty". */
@Preview(name = "加载失败", showBackground = true)
@Composable
private fun FilesScreenErrorPreview() {
    NotDiskTheme(dynamicColor = false) {
        FilesScreen(
            state = previewState(items = emptyList(), error = "没有权限访问「无权限目录」"),
            onNavigateUp = {},
            onOpenFolder = {},
            onNavigateToBreadcrumb = {},
            onRefresh = {},
            onLoadMore = {},
            onCreateFolder = {},
            onRename = { _, _ -> },
            onDelete = {},
            onSetSort = { _, _ -> },
            onToggleViewMode = {},
            onDismissError = {},
            onOpenMedia = { _, _, _ -> },
            onOpenPreview = { _, _, _ -> },
            onOpenAccounts = {},
        )
    }
}
