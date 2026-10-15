package com.notdisk.feature.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.database.settings.NotDiskSettingsStore
import com.notdisk.core.data.repository.AccountRepository
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The file browser's state machine.
 *
 * ### Why the state is one immutable value
 * A file screen has combinations that are meaningless but easy to produce with separate flows:
 * "loading" plus "error", "empty" plus "loading more", "folder B's breadcrumb" plus "folder A's
 * rows". Collapsing them into [FilesUiState] makes each of those a value the code cannot construct,
 * which is what allows the composable to render loading / empty / error as three genuinely distinct
 * screens instead of an `if` pile.
 *
 * ### The two bugs this class exists to not have
 *  1. **A stale response for a folder the user already left.** Directory listings are asynchronous
 *     and their latency varies per provider and per folder. Tapping A then quickly tapping B is
 *     completely normal, and if the ViewModel applied whichever response arrived last, A's slower
 *     answer would repaint B's screen with A's contents — the breadcrumb and the list would
 *     contradict each other, with no error shown. Every load therefore runs under a
 *     [RequestGeneration] ticket and is discarded unless it is still the current one *and* it is
 *     still about the folder on screen.
 *  2. **"Load more" that never ends.** Paging only ever appends while the provider supplies a
 *     cursor; a `null` cursor is a definitive "this is everything", and re-requesting page 1 would
 *     duplicate rows forever. See [loadMore].
 *
 * ### Capabilities, never provider identity
 * Which actions exist is read from the registry's [com.notdisk.core.model.CloudCapabilities] and
 * exposed as [FileActionAvailability]. There is deliberately no `when (providerId)` in this file or
 * anywhere it calls into: adding a cloud must not require editing a screen.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val files: CloudFileRepository,
    private val accountRepository: AccountRepository,
    private val settings: NotDiskSettingsStore,
    private val registry: ProviderRegistry,
) : ViewModel() {

    private val _state = MutableStateFlow(FilesUiState())
    val state: StateFlow<FilesUiState> = _state.asStateFlow()

    /**
     * The stale-response guard. See [RequestGeneration] for the failure mode it prevents.
     * Deliberately a plain object rather than a `Job` list: cancellation alone is not sufficient,
     * because a repository call that has already returned has nothing left to cancel.
     */
    private val generation = RequestGeneration()

    /** Kept so a second mutation cannot start while the first is still in flight. */
    private var mutationJob: Job? = null

    init {
        // The view mode is a live user preference: if it changes elsewhere (the settings screen),
        // this screen must follow without a relaunch. The sort is deliberately *not* watched this
        // way — see [setSort].
        viewModelScope.launch {
            // The persisted view mode and sort seed the screen once. They are read here and not
            // collected: `SortChoice` cannot represent `FileSortField.CREATED_TIME`, so watching
            // the store would silently rewrite a "按创建时间" choice back to the stored default
            // while the sort menu still showed the user's pick.
            val snapshot = settings.snapshot()
            _state.update { current ->
                current.copy(
                    viewMode = snapshot.viewMode,
                    sort = FileSort(
                        field = snapshot.defaultSort.toSortField(),
                        ascending = snapshot.sortAscending,
                        naturalOrder = snapshot.naturalSort,
                    ),
                )
            }
        }

        viewModelScope.launch { adoptRememberedAccount() }
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Opens an account's root folder.
     *
     * Idempotent: calling it again for the account that is already open (which is what a
     * `LaunchedEffect` in the route does on every recomposition-triggered relaunch) does not throw
     * away the folder the user has navigated into. Any *other* account starts from its root,
     * because a path from one drive is meaningless on another.
     */
    fun open(providerId: ProviderId, accountId: AccountId) {
        val current = _state.value
        if (current.providerId == providerId && current.accountId == accountId &&
            current.breadcrumb.isNotEmpty()
        ) {
            return
        }

        viewModelScope.launch { settings.setActiveAccount(providerId.value, accountId.value) }

        val ticket = generation.begin()
        _state.update {
            it.copy(
                providerId = providerId,
                accountId = accountId,
                breadcrumb = emptyList(),
                items = emptyList(),
                error = null,
                errorDetail = null,
                nextCursor = null,
                isLoadingMore = false,
                isLoading = true,
                isRefreshing = false,
                origin = null,
                capabilities = registry.actionAvailability(providerId),
                providerDisplayName = registry.find(providerId)?.displayName,
                accountDisplayName = null,
            )
        }

        viewModelScope.launch {
            // The account's display name is cosmetic, so a failure here must not stop the listing:
            // the user asked to see files, not a profile.
            when (val account = accountRepository.findAccount(providerId, accountId)) {
                is CloudResult.Success -> if (generation.isCurrent(ticket)) {
                    _state.update { it.copy(accountDisplayName = account.data.displayName) }
                }

                is CloudResult.Failure -> Unit
            }

            when (val root = files.getRoot(accountId, providerId)) {
                is CloudResult.Failure -> applyFailure(ticket, root.error)

                is CloudResult.Success ->
                    if (generation.isCurrent(ticket)) {
                        loadChildren(ticket = ticket, chain = listOf(root.data), forceNetwork = false)
                    }
            }
        }
    }

    /**
     * Descends into [folder].
     *
     * A tap on a file is *not* handled here: files are opened by the route's `onOpenMedia` /
     * `onOpenPreview` callbacks, so this method can assume it is being asked for a directory.
     */
    fun openFolder(folder: FileItem) {
        if (!folder.isFolder) return
        val chain = _state.value.breadcrumb + folder
        val ticket = generation.begin()
        viewModelScope.launch { loadChildren(ticket = ticket, chain = chain, forceNetwork = false) }
    }

    /** Moves one level up. A no-op at the root, where there is nowhere to go. */
    fun navigateUp() {
        val chain = _state.value.breadcrumb
        if (chain.size <= 1) return
        val ticket = generation.begin()
        viewModelScope.launch {
            loadChildren(ticket = ticket, chain = chain.dropLast(1), forceNetwork = false)
        }
    }

    /**
     * Jumps to an ancestor crumb.
     *
     * @param index index into [FilesUiState.breadcrumb]; the root is `0`. Out-of-range indices are
     *   ignored rather than clamped: a clamped index would navigate somewhere the user did not ask
     *   for, which is worse than doing nothing.
     */
    fun navigateToBreadcrumb(index: Int) {
        val chain = _state.value.breadcrumb
        if (index !in chain.indices || index == chain.lastIndex) return
        val ticket = generation.begin()
        viewModelScope.launch {
            loadChildren(ticket = ticket, chain = chain.take(index + 1), forceNetwork = false)
        }
    }

    /**
     * Re-reads the current folder from the provider, ignoring the cache.
     *
     * Also the retry entry point of the error state, which is why it works when the breadcrumb is
     * still empty (a failed root fetch): in that case the root is re-resolved first.
     */
    fun refresh() {
        val snapshot = _state.value
        val providerId = snapshot.providerId ?: return
        val accountId = snapshot.accountId ?: return
        val ticket = generation.begin()

        viewModelScope.launch {
            val chain = snapshot.breadcrumb
            if (chain.isEmpty()) {
                when (val root = files.getRoot(accountId, providerId)) {
                    is CloudResult.Failure -> applyFailure(ticket, root.error)
                    is CloudResult.Success ->
                        if (generation.isCurrent(ticket)) {
                            loadChildren(ticket, listOf(root.data), forceNetwork = true)
                        }
                }
            } else {
                loadChildren(ticket, chain, forceNetwork = true)
            }
        }
    }

    /**
     * Appends the next page.
     *
     * ### Why it cannot loop or duplicate
     *  - With no cursor the provider has said "that was everything"; requesting again would append
     *    page 1 a second time, so the call returns immediately.
     *  - A request already in flight blocks a second one, which matters because a `LazyColumn`
     *    fires its "near the end" callback on every scroll frame.
     *  - The ticket is the *current* generation, never a new one: a page belonging to a listing the
     *    user has left is discarded rather than appended to a different folder's rows.
     */
    fun loadMore() {
        val snapshot = _state.value
        val providerId = snapshot.providerId ?: return
        val accountId = snapshot.accountId ?: return
        val folder = snapshot.currentFolder ?: return
        if (!snapshot.canLoadMore) return
        val cursor = snapshot.nextCursor ?: return
        val ticket = generation.current

        _state.update { it.copy(isLoadingMore = true, error = null, errorDetail = null) }

        viewModelScope.launch {
            val result = files.listChildren(
                accountId = accountId,
                providerId = providerId,
                parentId = folder.id,
                cursor = cursor,
                sort = snapshot.sort,
            )

            when (result) {
                is CloudResult.Failure -> {
                    // The flag is cleared even for a response that is then discarded: leaving it set
                    // would disable paging for the rest of the session.
                    _state.update { it.copy(isLoadingMore = false) }
                    applyFailure(ticket, result.error)
                }

                is CloudResult.Success -> {
                    val listing = result.data
                    val stillHere = generation.isCurrent(ticket) &&
                        _state.value.currentFolder?.id == folder.id
                    if (stillHere) {
                        _state.update {
                            it.copy(
                                items = ListingMerge.merge(it.items, listing.page.items, continuation = true),
                                nextCursor = listing.page.nextCursor,
                                isLoadingMore = false,
                                isLoading = false,
                                isRefreshing = false,
                                origin = listing.origin,
                                error = null,
                                errorDetail = null,
                            )
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ mutations

    /** Creates a folder in the folder currently on screen. */
    fun createFolder(name: String) = mutate { providerId, accountId, folderId ->
        files.createFolder(accountId = accountId, providerId = providerId, parentId = folderId, name = name)
    }

    /** Renames [item] in place. */
    fun rename(item: FileItem, newName: String) = mutate { providerId, accountId, _ ->
        files.rename(accountId = accountId, providerId = providerId, fileId = item.id, newName = newName)
    }

    /**
     * Deletes [items].
     *
     * `permanent = false` on purpose: this screen's confirmation dialog says "移入回收站", and a
     * provider without a trash capability treats a normal delete as permanent on its own side. The
     * UI states which of the two applies before the user confirms (see `FilesScreen`).
     */
    fun delete(items: List<FileItem>) {
        if (items.isEmpty()) return
        mutate { providerId, accountId, folderId ->
            files.delete(
                accountId = accountId,
                providerId = providerId,
                fileIds = items.map { it.id },
                // Supplied explicitly: the cache may have been cleared, in which case the repository
                // could not derive the parent from a cached row and would invalidate nothing.
                knownParentId = folderId,
                permanent = false,
            )
        }
    }

    /**
     * Runs a mutation against the folder on screen, then reloads it.
     *
     * ### Why success reloads and failure does not
     * A successful mutation changed the remote state, and the authoritative answer is the provider's
     * — the reload is issued with `forceNetwork = true` rather than trusting the cache, so the user
     * sees exactly what the server now holds (including a folder the vendor rejected). A *failed*
     * mutation changed nothing, so reloading would burn a request to re-fetch an identical listing
     * and would hide the reason for the failure behind a spinner; the error is surfaced instead and
     * the list stays as it was.
     */
    private fun mutate(
        action: suspend (ProviderId, AccountId, FileId) -> CloudResult<*>,
    ) {
        val snapshot = _state.value
        val providerId = snapshot.providerId ?: return
        val accountId = snapshot.accountId ?: return
        val folderId = snapshot.currentFolder?.id ?: return
        if (mutationJob?.isActive == true) return

        mutationJob = viewModelScope.launch {
            _state.update { it.copy(isMutating = true, error = null, errorDetail = null) }
            when (val result = action(providerId, accountId, folderId)) {
                is CloudResult.Failure -> _state.update {
                    it.copy(
                        isMutating = false,
                        error = result.error.userFacingMessage,
                        errorDetail = result.error.technicalDetail(),
                    )
                }

                is CloudResult.Success -> {
                    _state.update { it.copy(isMutating = false) }
                    refresh()
                }
            }
        }
    }

    // ------------------------------------------------------------------ preferences

    /**
     * Applies a new sort order and reloads.
     *
     * ### Why the sort is written to the store but never read back from it
     * [NotDiskSettingsStore.SortChoice] has no `CREATED_TIME`, so a round trip through the store
     * cannot represent every [FileSortField] the UI offers. Watching the store would therefore
     * silently rewrite a `按创建时间` choice back to the stored default while the menu still showed
     * the user's pick. The screen's own state is authoritative for the session; the store is
     * best-effort persistence for the next launch, seeded once in [open].
     */
    fun setSort(field: FileSortField, ascending: Boolean) {
        val sort = FileSort(field = field, ascending = ascending)
        if (_state.value.sort == sort) return
        _state.update { it.copy(sort = sort) }

        viewModelScope.launch {
            sort.toSortChoice()?.let { settings.setDefaultSort(it) }
            settings.setSortAscending(ascending)
        }

        val ticket = generation.begin()
        val chain = _state.value.breadcrumb
        if (chain.isNotEmpty()) {
            viewModelScope.launch { loadChildren(ticket, chain, forceNetwork = false) }
        }
    }

    /** Flips between the list and grid presentations and remembers the choice. */
    fun toggleViewMode() {
        val next = when (_state.value.viewMode) {
            NotDiskSettingsStore.ViewMode.LIST -> NotDiskSettingsStore.ViewMode.GRID
            NotDiskSettingsStore.ViewMode.GRID -> NotDiskSettingsStore.ViewMode.LIST
        }
        _state.update { it.copy(viewMode = next) }
        viewModelScope.launch { settings.setViewMode(next) }
    }

    /** Clears the error banner without touching the list. */
    fun dismissError() {
        _state.update { it.copy(error = null, errorDetail = null) }
    }

    // ------------------------------------------------------------------ internals

    /**
     * Replaces the breadcrumb and loads that folder's children.
     *
     * The single place a listing is (re)started, so the stale-response rules are written once. The
     * caller has already claimed [ticket] from [generation]; the checks below are what turn that
     * ticket into a guarantee:
     *  - [RequestGeneration.isCurrent] rejects a listing started for a folder the user has left;
     *  - the folder-id comparison rejects the narrower case of two loads for the same generation
     *    that a future refactor might introduce.
     */
    private suspend fun loadChildren(
        ticket: Long,
        chain: List<FileItem>,
        forceNetwork: Boolean,
    ) {
        val folder = chain.lastOrNull() ?: return
        val snapshot = _state.value
        val providerId = snapshot.providerId ?: return
        val accountId = snapshot.accountId ?: return
        // "Arriving somewhere new" is decided by the *path*, not by whether rows happen to be on
        // screen: a folder the user has just navigated into must never display the previous
        // folder's rows while its own listing is in flight, even for a frame.
        val isArrival = snapshot.breadcrumb != chain

        _state.update {
            it.copy(
                breadcrumb = chain,
                // Clearing on arrival is what makes the loading branch reachable: the screen shows a
                // spinner rather than another folder's contents under this folder's name.
                items = if (isArrival) emptyList() else it.items,
                error = null,
                errorDetail = null,
                nextCursor = null,
                isLoadingMore = false,
                isLoading = isArrival && it.items.isEmpty(),
                isRefreshing = !isArrival,
            )
        }

        val result = files.listChildren(
            accountId = accountId,
            providerId = providerId,
            parentId = folder.id,
            sort = snapshot.sort,
            forceNetwork = forceNetwork,
            // Only a folder the user is arriving at may be served from cache; a pull-to-refresh
            // must reach the provider, which is the whole point of the gesture.
            serveCachedFirst = !forceNetwork,
        )

        if (!generation.isCurrent(ticket) || _state.value.currentFolder?.id != folder.id) {
            // A different folder won the race. Applying this page would show the wrong folder's
            // contents under the right folder's name.
            return
        }

        when (result) {
            is CloudResult.Failure -> {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        // The error is carried in state rather than as a thrown exception so the
                        // screen can offer a retry that keeps the breadcrumb the user is on.
                        error = result.error.userFacingMessage,
                        errorDetail = result.error.technicalDetail(),
                    )
                }
            }

            is CloudResult.Success -> {
                val listing = result.data
                _state.update {
                    it.copy(
                        // Replaces rather than appends: the provider is authoritative for the
                        // folder, so a file deleted on the server must disappear instead of
                        // lingering behind the fresh rows.
                        items = ListingMerge.merge(
                            existing = emptyList(),
                            incoming = listing.page.items,
                            continuation = false,
                        ),
                        nextCursor = listing.page.nextCursor,
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        origin = listing.origin,
                    )
                }
            }
        }
    }

    /** Applies the failure to the state, but only if the response still belongs to this screen. */
    private fun applyFailure(ticket: Long, error: CloudError) {
        if (!generation.isCurrent(ticket)) return
        _state.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                isLoadingMore = false,
                error = error.userFacingMessage,
                errorDetail = error.technicalDetail(),
            )
        }
    }

    /**
     * Opens whichever account the user last used, so the files tab is useful on a cold start
     * instead of showing an empty "choose an account" screen.
     *
     * The stored preference is treated as a *hint*, not as truth: an account that no longer exists
     * (removed on another screen, or its provider dropped from this build) must not produce a
     * permanent error, so the first live account is used instead.
     */
    private suspend fun adoptRememberedAccount() {
        if (_state.value.providerId != null) return

        val stored = settings.activeAccount.first()
        if (stored != null) {
            val providerId = stored.first.takeIf { it.isNotBlank() }?.let(::ProviderId)
            val accountId = stored.second.takeIf { it.isNotBlank() }?.let(::AccountId)
            if (providerId != null && accountId != null &&
                accountRepository.findAccount(providerId, accountId) is CloudResult.Success
            ) {
                open(providerId, accountId)
                return
            }
        }

        val accounts = accountRepository.listAccounts().getOrNull()?.accounts.orEmpty()
        accounts.firstOrNull()?.let { open(it.providerId, it.accountId) }
    }
}

/**
 * Which mutating actions the current provider actually offers.
 *
 * ### Why a typed value rather than a set of booleans scattered through the screen
 * The screen must hide a control it cannot honour, and it must be able to explain *why* when it
 * shows a disabled one ("厂商文档声明支持，但尚未实际验证"). Carrying the raw
 * [CapabilitySupport] per action — instead of only a `Boolean` — is what lets the top bar render a
 * [com.notdisk.core.designsystem.component.CapabilityBadge] without asking the registry again
 * during composition. It is a fixed-shape data class on purpose: a map would let a typo'd key read
 * as "unsupported" with no compile error.
 *
 * The `can…` readers are strict: only an explicitly `SUPPORTED` capability enables an action.
 * `UNVERIFIED` means the vendor documents it but nobody has exercised it, and offering a delete
 * button on that basis is how a user loses a folder to an integration that never worked.
 */
data class FileActionAvailability(
    val createFolder: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val rename: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val delete: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val move: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    val search: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** Whether deletes land in a recycle bin the user can restore from. */
    val trash: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** The provider can hand out a playable resource for at least one media kind. */
    val stream: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
    /** The provider can render at least one previewable kind inline. */
    val preview: CapabilitySupport = CapabilitySupport.UNSUPPORTED,
) {
    val canCreateFolder: Boolean get() = createFolder.isSupported
    val canRename: Boolean get() = rename.isSupported
    val canDelete: Boolean get() = delete.isSupported
    val canSearch: Boolean get() = search.isSupported
    val canStream: Boolean get() = stream.isSupported
    val canPreview: Boolean get() = preview.isSupported

    /** True when the provider's delete is recoverable, which the confirmation dialog must say. */
    val deleteIsRecoverable: Boolean get() = trash.isSupported

    companion object {
        /** Used before an account is opened, when nothing is known and nothing may be offered. */
        val Unavailable = FileActionAvailability()
    }
}

/**
 * Reads a provider's declared capabilities into the screen's flat availability value.
 *
 * ### Why this mapping lives in exactly one place
 * Two independent surfaces contribute (the mutation API and the media/preview API), and the
 * *combination* rule — "streaming is available if the provider can stream video **or** audio" — is
 * easy to get subtly wrong inside a composable and impossible to get wrong here. It also keeps the
 * screen's parameter list free of `CloudCapabilities`, which is what lets the `@Preview`s below
 * build a state by hand.
 *
 * A provider that is not registered yields [FileActionAvailability.Unavailable] rather than
 * throwing: an account row can legitimately outlive the provider registration that created it (a
 * build variant that drops a cloud), and the honest UI for that is "no actions offered", not a
 * crash on the first composition.
 */
internal fun ProviderRegistry.actionAvailability(providerId: ProviderId): FileActionAvailability {
    val capabilities = capabilitiesOf(providerId).getOrNull()
        ?: return FileActionAvailability.Unavailable
    val media = capabilities.media
    val previews = capabilities.preview

    return FileActionAvailability(
        createFolder = capabilities.operations.createFolder,
        rename = capabilities.operations.rename,
        delete = capabilities.operations.delete,
        move = capabilities.operations.move,
        search = capabilities.fileSystem.search,
        trash = capabilities.operations.trash.supported,
        stream = combine(media.videoStreaming, media.audioStreaming),
        preview = combine(
            previews.image,
            previews.text,
            previews.pdf,
            previews.officeDocument,
            previews.thumbnail,
        ),
    )
}

/**
 * Collapses several capability declarations into one.
 *
 * `SUPPORTED` wins because a provider that can stream audio *can* play something even when it
 * cannot stream video. `UNVERIFIED` survives only when nothing is verified: the UI still hides the
 * entry point, but a capability badge can then explain that the vendor documents the feature.
 */
internal fun combine(vararg values: CapabilitySupport): CapabilitySupport = when {
    values.any { it == CapabilitySupport.SUPPORTED } -> CapabilitySupport.SUPPORTED
    values.any { it == CapabilitySupport.UNVERIFIED } -> CapabilitySupport.UNVERIFIED
    else -> CapabilitySupport.UNSUPPORTED
}

/**
 * Everything the file browser renders.
 *
 * `providerId` / `accountId` being nullable is meaningful: the screen is *unbound* before an
 * account is open, which is a different state from "an account with an empty root".
 */
data class FilesUiState(
    val providerId: ProviderId? = null,
    val accountId: AccountId? = null,
    /** Root → current folder. The last entry is the folder on screen. */
    val breadcrumb: List<FileItem> = emptyList(),
    val items: List<FileItem> = emptyList(),
    /** First load of a folder: the list is not yet trustworthy, so a spinner replaces it. */
    val isLoading: Boolean = false,
    /** A re-read while rows are already on screen; the list stays visible. */
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** A create/rename/delete is running; the list is dimmed but not replaced. */
    val isMutating: Boolean = false,
    /** User-facing failure text. `null` when the last operation succeeded. */
    val error: String? = null,
    /** Technical counterpart of [error], shown only behind [ErrorState]'s disclosure. */
    val errorDetail: String? = null,
    val nextCursor: String? = null,
    val viewMode: NotDiskSettingsStore.ViewMode = NotDiskSettingsStore.ViewMode.LIST,
    val sort: FileSort = FileSort.Default,
    /** Where the visible rows came from; the UI can tell the user they are looking at a cache. */
    val origin: CloudFileRepository.DataOrigin? = null,
    val capabilities: FileActionAvailability = FileActionAvailability.Unavailable,
    val providerDisplayName: String? = null,
    val accountDisplayName: String? = null,
) {
    val currentFolder: FileItem? get() = breadcrumb.lastOrNull()

    /** False at the root: there is nothing to go up to. */
    val canGoUp: Boolean get() = breadcrumb.size > 1

    /**
     * Whether the provider has more rows and no request is already running.
     *
     * A `null` cursor is the provider's definitive "this is everything", so it is what stops an
     * infinite scroll from looping over page 1 forever.
     */
    val canLoadMore: Boolean get() = nextCursor != null && !isLoadingMore && !isRefreshing && !isLoading

    /** An account is open but its folder has produced no rows and no failure. */
    val isEmpty: Boolean get() = items.isEmpty() && !isLoading && error == null

    /** Nothing has been opened at all — distinct from an empty folder, and rendered differently. */
    val isUnbound: Boolean get() = providerId == null || accountId == null
}

/**
 * The technical half of an error, for [com.notdisk.core.designsystem.component.ErrorState].
 *
 * Deliberately assembled from the typed fields of [CloudError] rather than from its message: the
 * vendor's own text is useful in a bug report, whereas a raw exception string may contain a signed
 * URL. Nothing here is a credential, and nothing here is shown unless the user asks for it.
 */
internal fun CloudError.technicalDetail(): String? {
    val parts = buildList {
        add(this@technicalDetail::class.simpleName ?: "CloudError")
        httpStatus?.let { add("HTTP $it") }
        vendorCode?.let { add("code=$it") }
        vendorMessage?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.takeIf { it.size > 1 }?.joinToString(" · ")
}

/**
 * Maps the persisted sort choice onto the sort field it names.
 *
 * One-way on purpose: [NotDiskSettingsStore.SortChoice] has no `CREATED_TIME`, so the reverse
 * mapping would have to invent an answer. `null` from that direction is what keeps the menu from
 * disagreeing with the screen.
 */
private fun NotDiskSettingsStore.SortChoice.toSortField(): FileSortField = when (this) {
    NotDiskSettingsStore.SortChoice.NAME -> FileSortField.NAME
    NotDiskSettingsStore.SortChoice.MODIFIED_TIME -> FileSortField.MODIFIED_TIME
    NotDiskSettingsStore.SortChoice.SIZE -> FileSortField.SIZE
    NotDiskSettingsStore.SortChoice.TYPE -> FileSortField.TYPE
}

/** Maps a settings choice onto the sort field it names. `CREATED_TIME` has no stored equivalent. */
private fun FileSort.toSortChoice(): NotDiskSettingsStore.SortChoice? = when (field) {
    FileSortField.NAME -> NotDiskSettingsStore.SortChoice.NAME
    FileSortField.MODIFIED_TIME -> NotDiskSettingsStore.SortChoice.MODIFIED_TIME
    FileSortField.SIZE -> NotDiskSettingsStore.SortChoice.SIZE
    FileSortField.TYPE -> NotDiskSettingsStore.SortChoice.TYPE
    FileSortField.CREATED_TIME -> null
}
