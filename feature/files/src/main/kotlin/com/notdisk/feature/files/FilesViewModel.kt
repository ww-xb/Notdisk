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
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.CloudAccountKey
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.FileSortField
import com.notdisk.core.model.FileType
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

        // The account list is what decides the home level, and it can change while this screen is
        // alive (the settings screen's account manager binds or removes an account). Watching the
        // local mirror is what lets a *removal* be reacted to; the mirror is cheap to observe
        // because the repository writes provider answers into it.
        viewModelScope.launch {
            accountRepository.observeAccounts().collect { accounts ->
                onAccountsChanged(accounts)
            }
        }
    }

    // ------------------------------------------------------------------ home (multi-account)

    /**
     * Re-reads the bound accounts from every provider and re-decides the home level.
     *
     * ### Why this runs on every resume, and why it must not move the user
     * Accounts are bound on the settings tab, so a user who binds their first cloud and returns here
     * expects to see its files. The browser's ViewModel survives that round trip (the `files`
     * destination is never popped), so nothing else would re-read the list.
     *
     * It re-enumerates the providers rather than trusting the local mirror, because a
     * just-completed login exists only on the provider side until the next enumeration. For 115 that
     * call is purely local (`listAccounts` never contacts the vendor), so resuming costs nothing.
     *
     * ### The position-preservation rule
     * Re-rendering unconditionally would throw the user back to the cloud's *root* every time the
     * screen resumed — including after answering a permission dialog. So the level is recomputed
     * first, and the screen is only redrawn when the account set actually changed the decision:
     *  - the cloud being browsed is gone, or
     *  - the number of accounts crossed the 1 ↔ many boundary.
     *
     * A newly bound *second* account while the user is browsing the first is deliberately not a
     * redraw: the user is mid-folder, and up-navigation now leads to the picker (see `canGoUp`), so
     * the new cloud is reachable without losing their place.
     */
    fun refreshAccounts() {
        val ticket = generation.begin()
        viewModelScope.launch {
            when (val result = accountRepository.listAccounts()) {
                is CloudResult.Failure -> applyFailure(ticket, result.error)
                is CloudResult.Success -> {
                    val accounts = result.data.accounts
                    applyKnownAccounts(accounts)
                    if (!generation.isCurrent(ticket)) return@launch

                    val current = _state.value
                    val open = current.openAccountKey()
                    val stillBound = open != null && accounts.any {
                        it.providerId == open.providerId && it.accountId == open.accountId
                    }
                    val expected = expectedLevel(accounts, open.takeIf { stillBound })

                    if (expected != current.level || (open != null && !stillBound)) {
                        renderLevel(ticket, open.takeIf { stillBound }, forceNetwork = true)
                    }
                }
            }
        }
    }

    /**
     * The level the account set implies for the given open cloud.
     *
     * Extracted because two callers must agree on it — [refreshAccounts] decides whether to redraw,
     * and [renderLevel] decides what to draw — and a mismatch between the two would produce either a
     * stale screen or a redraw loop.
     */
    private fun expectedLevel(
        accounts: List<CloudAccount>,
        openAccount: CloudAccountKey?,
    ): ScreenLevel = when {
        accounts.isEmpty() -> ScreenLevel.EMPTY
        accounts.size > 1 && openAccount == null -> ScreenLevel.HOME_MULTI
        else -> ScreenLevel.ACCOUNT_ROOT_SINGLE
    }

    /** Descends home → the cloud the user picked; a no-op once that cloud is open. */
    fun openAccount(account: CloudAccount) {
        open(account.providerId, account.accountId)
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
                // Cleared here; `loadChildren` then fills it with the cloud's own root, so the
                // breadcrumb is always the *account's* path rather than the picker's.
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
     * Returns to the home level: the cloud picker when several accounts are bound, and the single
     * cloud's root when only one is.
     *
     * The open account is deliberately *not* carried over. "Home" means the level above a cloud, so
     * re-opening the cloud the user just left would make the up-navigation look like it did nothing.
     */
    fun goHome() {
        val ticket = generation.begin()
        val known = _state.value.knownAccounts

        _state.update {
            it.copy(
                // The breadcrumb stays empty at home: it is the *account's* path, and at the home
                // level there is no folder above the cloud to name.
                breadcrumb = emptyList(),
                items = emptyList(),
                isLoading = true,
                isRefreshing = false,
                isLoadingMore = false,
                nextCursor = null,
                error = null,
                errorDetail = null,
                level = if (known.size > 1) ScreenLevel.HOME_MULTI else ScreenLevel.EMPTY,
                providerId = null,
                accountId = null,
            )
        }

        viewModelScope.launch { renderLevel(ticket, openAccount = null, forceNetwork = false) }
    }

    /** Descends into [folder]. */
    fun openFolder(folder: FileItem) {
        // The home level's rows are not files: they are the bound clouds, and opening one enters
        // that cloud rather than listing a folder inside it.
        if (_state.value.level.isHome) {
            _state.value.knownAccounts
                .firstOrNull { it.accountId.value == folder.id.value }
                ?.let { openAccount(it) }
            return
        }
        if (!folder.isFolder) return
        val chain = _state.value.breadcrumb + folder
        val ticket = generation.begin()
        viewModelScope.launch { loadChildren(ticket = ticket, chain = chain, forceNetwork = false) }
    }

    /** Moves one level up. A no-op at the root, where there is nowhere to go. */
    fun navigateUp() {
        val state = _state.value
        if (state.level.isHome) return
        val chain = state.breadcrumb
        if (chain.size <= 1) {
            // At the cloud's own root, "up" means the home level.
            goHome()
            return
        }
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
        val state = _state.value
        if (state.level.isHome) return
        val chain = state.breadcrumb
        if (index !in chain.indices || index == chain.lastIndex) return
        val ticket = generation.begin()
        viewModelScope.launch {
            loadChildren(ticket = ticket, chain = chain.take(index + 1), forceNetwork = false)
        }
    }

    /**
     * Re-reads whatever is currently on screen from the provider, ignoring the cache.
     *
     * Also the retry entry point of the error state, which is why it works at the home level
     * (where the account list itself may have failed to load) and with an empty breadcrumb (a
     * failed root fetch, in which case the root is re-resolved first).
     */
    fun refresh() {
        val snapshot = _state.value
        val ticket = generation.begin()

        if (snapshot.level.isHome) {
            viewModelScope.launch {
                when (val result = accountRepository.listAccounts()) {
                    is CloudResult.Failure -> applyFailure(ticket, result.error)
                    is CloudResult.Success -> {
                        applyKnownAccounts(result.data.accounts)
                        if (generation.isCurrent(ticket)) {
                            renderLevel(ticket, snapshot.openAccountKey(), forceNetwork = true)
                        }
                    }
                }
            }
            return
        }

        val providerId = snapshot.providerId ?: return
        val accountId = snapshot.accountId ?: return

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

    /** Records the accounts the home level can offer, without deciding what is on screen. */
    private fun applyKnownAccounts(accounts: List<CloudAccount>) {
        _state.update { it.copy(knownAccounts = accounts.sortedBy { it.addedAt }) }
    }

    /**
     * Reacts to the bound-account list changing underneath the screen.
     *
     * The only change that must be acted on is *removal of the account being browsed*: without this,
     * the screen would keep showing a cloud's folders while every request for them fails with
     * "account does not exist".
     *
     * ### Why an empty observation is ignored while accounts are known
     * This table is a *cache* the repository fills from provider answers
     * (`AccountRepository.listAccounts` → `persistQuietly`), so the flow's first emission after
     * subscribing is the table's state *before* anything was written — normally empty. Reacting to
     * it would blank a screen whose accounts were just read successfully from the provider, which is
     * exactly the "no account is bound" screen appearing over a working account.
     *
     * The same reasoning means a *newly* bound account is not picked up here: this observes a cache,
     * and the authority for "which accounts are bound" is the provider. Binding happens on the
     * settings tab, so the browser re-reads on its next render anyway.
     */
    private suspend fun onAccountsChanged(mirror: List<CloudAccount>) {
        val previous = _state.value.knownAccounts
        if (mirror.isEmpty() && previous.isNotEmpty()) return
        if (mirror == previous) return
        applyKnownAccounts(mirror)

        val current = _state.value
        val open = current.openAccountKey() ?: return
        val stillBound = mirror.any {
            it.providerId == open.providerId && it.accountId == open.accountId
        }
        if (stillBound) return

        // The cloud the user was inside is gone. Falling back to home is the honest answer; the
        // alternative (staying put) shows folders that can no longer be listed.
        val ticket = generation.begin()
        renderLevel(ticket, openAccount = null, forceNetwork = false)
    }

    /**
     * Renders the current level, re-deciding it from the account list when [forceNetwork] asks for
     * fresh data.
     *
     * @param openAccount the cloud the user was last inside, or `null` at the home level with
     *   nothing open. With exactly one bound account this is what makes the home screen show that
     *   cloud's contents directly rather than a redundant single-row list.
     */
    private suspend fun renderLevel(
        ticket: Long,
        openAccount: CloudAccountKey?,
        forceNetwork: Boolean,
    ) {
        val accounts = _state.value.knownAccounts
        val level = expectedLevel(accounts, openAccount)

        if (level == ScreenLevel.EMPTY) {
            if (generation.isCurrent(ticket)) {
                _state.update {
                    it.copy(
                        level = ScreenLevel.EMPTY,
                        breadcrumb = emptyList(),
                        items = emptyList(),
                        isLoading = false,
                        isRefreshing = false,
                        nextCursor = null,
                        error = null,
                        errorDetail = null,
                    )
                }
            }
            return
        }

        if (level == ScreenLevel.HOME_MULTI) {
            if (generation.isCurrent(ticket)) {
                _state.update {
                    it.copy(
                        level = ScreenLevel.HOME_MULTI,
                        // No single cloud is open at this level, so the account-scoped fields are
                        // cleared rather than left pointing at whichever cloud was last entered —
                        // a stale `providerId` here is exactly what would make a folder action
                        // target the wrong drive.
                        providerId = null,
                        accountId = null,
                        breadcrumb = emptyList(),
                        // One folder per bound cloud, labelled with the cloud's own name.
                        items = homeItemsFor(accounts),
                        isLoading = false,
                        isRefreshing = false,
                        isLoadingMore = false,
                        nextCursor = null,
                        error = null,
                        errorDetail = null,
                        // No single provider's actions apply to a list of providers; offering a
                        // "new folder" here would have to invent which cloud it meant.
                        capabilities = FileActionAvailability.Unavailable,
                        providerDisplayName = null,
                        accountDisplayName = null,
                    )
                }
            }
            return
        }

        // Single account, or an explicit cloud selection: the cloud's own root is the content.
        val target = openAccount
            ?: accounts.first().let { CloudAccountKey(it.providerId, it.accountId) }
        val providerId = target.providerId
        val accountId = target.accountId

        _state.update {
            it.copy(
                providerId = providerId,
                accountId = accountId,
                level = ScreenLevel.ACCOUNT_ROOT_SINGLE,
                capabilities = registry.actionAvailability(providerId),
                providerDisplayName = registry.find(providerId)?.displayName,
            )
        }

        val root = files.getRoot(accountId, providerId)
        when (root) {
            is CloudResult.Failure -> applyFailure(ticket, root.error)
            is CloudResult.Success ->
                if (generation.isCurrent(ticket)) {
                    loadChildren(ticket, listOf(root.data), forceNetwork = forceNetwork)
                }
        }
    }

    /**
     * The home level's rows: one synthetic folder per bound cloud.
     *
     * ### Two decisions worth naming
     *  - The row's **id is the account id**, not a provider id. A single provider can be bound
     *    twice with two different accounts, and both must appear as two separate top-level folders.
     *  - The row's **name is the provider's display name** ("115 网盘"), which is what the user
     *    recognises. Two rows of the same cloud are disambiguated by appending the account's own
     *    display name, so "115 网盘" never appears twice with no way to tell them apart.
     *
     * The synthetic folder carries no vendor capability flags: a top-level row is not a real remote
     * object, so offering rename/delete on it would be a lie the provider would then reject.
     */
    private fun homeItemsFor(accounts: List<CloudAccount>): List<FileItem> {
        val duplicateProviders = accounts.groupingBy { it.providerId }.eachCount()
        return accounts.map { account ->
            val cloudName = registry.find(account.providerId)?.displayName
                ?: account.providerId.value
            val needsSuffix = (duplicateProviders[account.providerId] ?: 0) > 1
            FileItem(
                id = FileId(account.accountId.value),
                providerId = account.providerId,
                accountId = account.accountId,
                name = if (needsSuffix) "$cloudName · ${account.displayName}" else cloudName,
                type = FileType.FOLDER,
                // Folders have no size and no timestamp; `null` is the model's "not reported",
                // which is exactly true here and is what stops the row inventing "0 B".
                size = null,
                modifiedTime = null,
                createdTime = null,
                capabilities = FileCapabilities(
                    canRename = false,
                    canDelete = false,
                    canMove = false,
                    canCopy = false,
                    // Tapping the row opens the cloud, so it is always selectable.
                    isBlocked = false,
                ),
            )
        }
    }

    /** The cloud the user is currently inside, if any. */
    private fun FilesUiState.openAccountKey(): CloudAccountKey? {
        val provider = providerId ?: return null
        val account = accountId ?: return null
        return CloudAccountKey(provider, account)
    }

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
        val isArrival = snapshot.breadcrumb != chain || snapshot.level.isHome

        _state.update {
            it.copy(
                level = ScreenLevel.ACCOUNT_ROOT_SINGLE,
                breadcrumb = chain,
                // Clearing on arrival is what makes the loading branch reachable: the screen shows a
                // skeleton rather than another folder's contents under this folder's name.
                items = if (isArrival) emptyList() else it.items,
                error = null,
                errorDetail = null,
                nextCursor = null,
                isLoadingMore = false,
                // ### Why this is just `isArrival`
                // It used to be `isArrival && it.items.isEmpty()`, which read the *previous* state's
                // rows — and since arrival has already cleared them above, that second term could
                // only ever *suppress* the loading flag. Navigating from a populated folder
                // therefore published `items = []` with `isLoading = false`, which is exactly the
                // shape of a successful empty listing: the screen rendered 「这个文件夹是空的」 for
                // the whole provider round trip and then hard-swapped to the real rows (reported as
                // "进入文件夹时依旧很生硬"). Going *up* had the same defect.
                //
                // An arrival always means "the contents of this folder are not known yet", so the
                // loading state is not conditional on anything.
                isLoading = isArrival,
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
     * Decides the screen's first content, so the files tab is useful on a cold start instead of
     * showing an empty "choose an account" screen.
     *
     * ### The rule this implements
     *  - **no account** → the home level's empty state, which explains how to bind one;
     *  - **exactly one account** → that cloud's contents *directly*, with no redundant one-row list
     *    standing between the user and their files;
     *  - **two or more accounts** → one top-level folder per cloud, named after the netdisk.
     *
     * The stored `activeAccount` preference is treated as a *hint* for which cloud to open when
     * several are bound, not as truth: an account that no longer exists (removed on another screen,
     * or its provider dropped from this build) must not produce a permanent error, so the hint is
     * only honoured when it still names a live account.
     */
    private suspend fun adoptRememberedAccount() {
        if (_state.value.providerId != null) return

        val ticket = generation.begin()
        val accountsResult = accountRepository.listAccounts()
        val accounts = when (accountsResult) {
            is CloudResult.Success -> accountsResult.data.accounts
            is CloudResult.Failure -> {
                // A failed enumeration still leaves the local mirror to render, so the user sees
                // their accounts (with an explanation) rather than a bare error page.
                applyKnownAccounts(accountRepository.observeAccounts().first())
                applyFailure(ticket, accountsResult.error)
                return
            }
        }

        if (!generation.isCurrent(ticket)) return
        applyKnownAccounts(accounts)

        // The hint only picks *which* cloud to open; it never changes whether the home level is
        // shown, because that is decided by how many accounts exist.
        val hinted = settings.activeAccount.first()?.let { (provider, account) ->
            accounts.firstOrNull {
                it.providerId.value == provider && it.accountId.value == account
            }
        }

        renderLevel(
            ticket = ticket,
            // With one account either branch opens the same cloud, so the hint is only consulted
            // when there is a real choice to make.
            openAccount = if (accounts.size > 1) null else hinted?.let {
                CloudAccountKey(it.providerId, it.accountId)
            },
            forceNetwork = false,
        )
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
 * Which level of the browser is on screen.
 *
 * ### Why this is explicit rather than derived from `breadcrumb.isEmpty()`
 * "The breadcrumb is empty" conflates three genuinely different screens: no account is bound at
 * all, several clouds are offered as top-level folders, and a single cloud is open at its own root.
 * Only one of those may offer "new folder"; only one may be entered by a system back press; and
 * each needs different copy. Deriving the level from the breadcrumb would make each of those a
 * coincidence rather than a rule.
 */
enum class ScreenLevel {
    /** No account is bound: the screen explains how to bind one. */
    EMPTY,

    /** Two or more accounts: the rows are the clouds themselves. */
    HOME_MULTI,

    /**
     * A cloud's own root, reached either because it is the only bound account (the single-account
     * rule) or because the user picked it from [HOME_MULTI].
     */
    ACCOUNT_ROOT_SINGLE,
    ;

    /** True when the rows on screen are clouds rather than remote folders. */
    val isHome: Boolean get() = this != ACCOUNT_ROOT_SINGLE
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
    /** Which level is on screen; see [ScreenLevel]. */
    val level: ScreenLevel = ScreenLevel.EMPTY,
    /**
     * Every bound account, across every provider.
     *
     * Held in state rather than read on demand because the home level renders one row per entry and
     * the back-press rule depends on how many there are. Kept up to date by the repository's flow.
     */
    val knownAccounts: List<CloudAccount> = emptyList(),
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

    /**
     * True when the screen is showing remote folder contents.
     *
     * The home levels list clouds, not folders, so every folder-specific affordance (up-navigation,
     * breadcrumb, new folder, paging) keys off this rather than off "the breadcrumb is empty".
     */
    val isListingFolder: Boolean get() = level == ScreenLevel.ACCOUNT_ROOT_SINGLE

    /**
     * Whether "go up" leads anywhere.
     *
     * Three distinct cases, and only two of them are navigable:
     *  - an empty home has nothing to go up to;
     *  - the cloud *picker* is the top level, so there is nothing above it either;
     *  - a cloud's root goes up to the picker **only when a picker exists** — with a single bound
     *    account, up would re-render the identical root, so the control is hidden rather than
     *    offered as a no-op.
     */
    val canGoUp: Boolean
        get() = when (level) {
            ScreenLevel.EMPTY, ScreenLevel.HOME_MULTI -> false
            ScreenLevel.ACCOUNT_ROOT_SINGLE ->
                breadcrumb.size > 1 || knownAccounts.size > 1
        }

    /**
     * Whether the provider has more rows and no request is already running.
     *
     * A `null` cursor is the provider's definitive "this is everything", so it is what stops an
     * infinite scroll from looping over page 1 forever.
     */
    val canLoadMore: Boolean
        get() = isListingFolder && nextCursor != null && !isLoadingMore && !isRefreshing && !isLoading

    /** An account is open but its folder has produced no rows and no failure. */
    val isEmpty: Boolean
        get() = isListingFolder && items.isEmpty() && !isLoading && error == null

    /** Nothing has been opened at all — distinct from an empty folder, and rendered differently. */
    val isUnbound: Boolean get() = providerId == null || accountId == null

    /** The home level with more than one cloud, which is the only level whose rows are clouds. */
    val isAccountPicker: Boolean get() = level == ScreenLevel.HOME_MULTI

    /** No account is bound at all. */
    val isNoAccounts: Boolean get() = level == ScreenLevel.EMPTY
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
