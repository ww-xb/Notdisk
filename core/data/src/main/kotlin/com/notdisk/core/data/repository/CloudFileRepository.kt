package com.notdisk.core.data.repository

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.common.result.map
import com.notdisk.core.data.cache.FileMetadataCache
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.TransferTask
import com.notdisk.core.model.UploadRequest
import com.notdisk.core.provider.BatchOperationResult
import com.notdisk.core.provider.DownloadSource
import com.notdisk.core.provider.PreviewHint
import com.notdisk.core.provider.TrashPage
import com.notdisk.core.provider.PreviewResourceOrNull
import com.notdisk.core.provider.ProviderRegistry

/**
 * The single entry point the domain layer uses to read and mutate remote files.
 *
 * ### Responsibilities, and the boundaries it defends
 *  1. **Provider selection** through [ProviderRegistry] — never a `when (providerId)`.
 *  2. **Caching policy** — when a listing may be served from local cache, and when it must not.
 *  3. **Cache invalidation after mutations** — the difference between a cache that is fast and one
 *     that lies.
 *  4. **Cursor guarding** — refusing a page whose cursor did not advance, so "load more" cannot loop.
 *
 * It contains no vendor-specific logic. Everything vendor-shaped arrives through the provider
 * interfaces, which is what lets a second cloud be added without touching this class.
 *
 * ### Why invalidation is centralised here
 * Every mutation and the reads that depend on it live in one class, so "after a successful rename,
 * the parent folder's cache must be dropped" is expressed once, next to the mutation. Scattering it
 * across ViewModels is how a cache ends up showing a file that no longer exists.
 */
class CloudFileRepository(
    private val registry: ProviderRegistry,
    private val cache: FileMetadataCache,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Where a listing came from. The UI needs this to decide whether a refresh is still running. */
    enum class DataOrigin { CACHE, NETWORK }

    data class ListingResult(
        val page: FilePage,
        val origin: DataOrigin,
    )

    // ------------------------------------------------------------------ reads

    /**
     * Lists a folder.
     *
     * @param forceNetwork skip the cache. Pull-to-refresh and "open after a mutation" both use it.
     * @param serveCachedFirst when true and the cache is fresh, the cached page is returned without a
     *   network call; otherwise the network is consulted and the cache is refreshed.
     */
    suspend fun listChildren(
        accountId: AccountId,
        providerId: ProviderId,
        parentId: FileId,
        cursor: String? = null,
        pageSize: Int = ListChildrenRequest.DEFAULT_PAGE_SIZE,
        sort: FileSort = FileSort.Default,
        forceNetwork: Boolean = false,
        serveCachedFirst: Boolean = false,
        cacheTtlMillis: Long = DEFAULT_CACHE_TTL_MILLIS,
    ): CloudResult<ListingResult> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        // Only the *first* page of a folder can come from the cache: a later page has no cached
        // equivalent, and pretending otherwise would silently truncate the listing.
        val cacheablePage = cursor == null

        if (cacheablePage && !forceNetwork && serveCachedFirst && cache.isCacheable(provider)) {
            val fresh = cache.isFresh(accountId, providerId, parentId, cacheTtlMillis)
            if (fresh) {
                val cached = cache.readChildren(accountId, providerId, parentId).getOrNull()
                if (!cached.isNullOrEmpty()) {
                    logger.d(TAG, "serving ${cached.size} cached items for ${parentId.value.take(12)}")
                    return CloudResult.Success(
                        ListingResult(
                            page = FilePage(
                                items = cached,
                                // The cache holds one folder's worth, not a cursor chain: reporting a
                                // cursor here would make "load more" ask the network for page 2 of a
                                // listing the user already sees in full.
                                nextCursor = null,
                                totalCount = cached.size.toLong(),
                            ),
                            origin = DataOrigin.CACHE,
                        ),
                    )
                }
            }
        }

        val request = ListChildrenRequest(
            parentId = parentId,
            cursor = cursor,
            pageSize = pageSize,
            sort = sort,
        )

        return when (val result = provider.files.listChildren(accountId, request)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val page = result.data
                guardCursorProgress(cursor, page.nextCursor)?.let { return it }

                if (cacheablePage && cache.isCacheable(provider)) {
                    cache.replaceChildren(accountId, providerId, parentId, page.items)
                }

                CloudResult.Success(ListingResult(page = page, origin = DataOrigin.NETWORK))
            }
        }
    }

    suspend fun getRoot(accountId: AccountId, providerId: ProviderId): CloudResult<FileItem> =
        withProvider(providerId) { it.files.getRoot(accountId) }

    /**
     * File detail, preferring the cache when it is present.
     *
     * The cache is consulted first because a detail screen is almost always opened from a list the
     * app just rendered, so the row is already in memory. A miss falls through to the provider and
     * populates the cache for next time.
     */
    suspend fun getFile(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        preferCache: Boolean = true,
    ): CloudResult<FileItem> {
        val key = CloudFileKey(providerId, accountId, fileId)

        if (preferCache) {
            cache.peek(key)?.let { return CloudResult.Success(it) }
        }

        return when (val result = withProvider(providerId) { it.files.getFile(accountId, fileId) }) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                cache.put(result.data)
                CloudResult.Success(result.data)
            }
        }
    }

    suspend fun getPath(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ): CloudResult<FilePath> = withProvider(providerId) { it.files.getPath(accountId, fileId) }

    suspend fun resolvePath(
        accountId: AccountId,
        providerId: ProviderId,
        path: String,
    ): CloudResult<FileId> = withProvider(providerId) { it.files.resolvePath(accountId, path) }

    /**
     * Search.
     *
     * A provider that supports server-side search is asked directly. One that does not gets a
     * **typed `Unsupported`** rather than a silent fallback to a local scan: the brief requires an
     * honest answer, and a local scan over a partially-cached tree would look like a working search
     * that mysteriously misses files. The caller (UI) can offer [searchLocal] as an explicit,
     * differently-labelled option.
     */
    suspend fun search(
        accountId: AccountId,
        providerId: ProviderId,
        query: FileSearchQuery,
    ): CloudResult<FilePage> {
        val provider = when (val result = registry.require(providerId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        if (provider.capabilities().fileSystem.search != CapabilitySupport.SUPPORTED) {
            return CloudResult.Failure(
                CloudError.Unsupported(
                    message = "${provider.displayName} 不支持服务端搜索",
                    capability = "filesystem.search",
                ),
            )
        }

        return provider.files.search(accountId, query)
    }

    /** Offline search over cached metadata only. Explicitly a different, narrower operation. */
    suspend fun searchLocal(
        accountId: AccountId,
        providerId: ProviderId,
        keyword: String,
        limit: Int = 100,
    ): CloudResult<List<FileItem>> = cache.searchLocal(accountId, providerId, keyword, limit)

    // ------------------------------------------------------------------ mutations

    /**
     * Creates a folder and invalidates its parent.
     *
     * Invalidation happens **only on success**: dropping the cache after a failed create would force
     * a pointless re-fetch and, worse, make a failure look like it changed something.
     */
    suspend fun createFolder(
        accountId: AccountId,
        providerId: ProviderId,
        parentId: FileId,
        name: String,
    ): CloudResult<FileItem> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件夹名不能为空"))
        }
        requireCapability(providerId, "operations.createFolder") {
            it.capabilities().operations.createFolder
        }?.let { return it }

        return when (
            val result = withProvider(providerId) {
                it.operations.createFolder(accountId, parentId, trimmed)
            }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                cache.invalidateFolder(accountId, providerId, parentId)
                CloudResult.Success(result.data)
            }
        }
    }

    /**
     * Renames an item.
     *
     * Invalidates **both** the item's own cached row and its parent folder. The parent matters because
     * the list is ordered by name, so a rename changes the row's position as well as its label;
     * leaving the folder cached would show the file in the wrong place.
     */
    suspend fun rename(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        newName: String,
    ): CloudResult<FileItem> {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "文件名不能为空"))
        }
        requireCapability(providerId, "operations.rename") {
            it.capabilities().operations.rename
        }?.let { return it }

        val key = CloudFileKey(providerId, accountId, fileId)
        val knownParent = cache.peek(key)?.parentId

        return when (
            val result = withProvider(providerId) { it.operations.rename(accountId, fileId, trimmed) }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                cache.invalidateItem(key)
                // The new parent is taken from the provider's answer when available, so a rename that
                // also moves (some vendors treat "save as" that way) still invalidates correctly.
                cache.invalidateFolder(
                    accountId,
                    providerId,
                    result.data.parentId ?: knownParent,
                )
                CloudResult.Success(result.data)
            }
        }
    }

    suspend fun move(
        accountId: AccountId,
        providerId: ProviderId,
        fileIds: List<FileId>,
        targetParentId: FileId,
        knownSourceParentId: FileId? = null,
    ): CloudResult<BatchOperationResult> {
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何文件"))
        }
        requireCapability(providerId, "operations.move") {
            it.capabilities().operations.move
        }?.let { return it }

        return when (
            val result = withProvider(providerId) {
                it.operations.move(accountId, fileIds, targetParentId)
            }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // A move changes two listings. Both are dropped; the source parent is taken from the
                // caller when it knows it, otherwise from each item's cached row.
                cache.invalidateFolder(accountId, providerId, targetParentId)
                val sourceParents = buildSet {
                    knownSourceParentId?.let { add(it) }
                    fileIds.forEach { id ->
                        cache.peek(CloudFileKey(providerId, accountId, id))?.parentId?.let { add(it) }
                    }
                }
                sourceParents.forEach { parent ->
                    if (parent != targetParentId) {
                        cache.invalidateFolder(accountId, providerId, parent)
                    }
                }
                if (result.data.succeeded.isNotEmpty()) {
                    result.data.succeeded.forEach { cache.invalidateItem(it) }
                }
                CloudResult.Success(result.data)
            }
        }
    }

    suspend fun copy(
        accountId: AccountId,
        providerId: ProviderId,
        fileIds: List<FileId>,
        targetParentId: FileId,
    ): CloudResult<BatchOperationResult> {
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何文件"))
        }
        requireCapability(providerId, "operations.copy") {
            it.capabilities().operations.copy
        }?.let { return it }

        return when (
            val result = withProvider(providerId) {
                it.operations.copy(accountId, fileIds, targetParentId)
            }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // Only the destination changes; the sources are untouched by a copy.
                cache.invalidateFolder(accountId, providerId, targetParentId)
                CloudResult.Success(result.data)
            }
        }
    }

    /**
     * Deletes items (to the recycle bin on vendors that have one).
     *
     * @param knownParentId the folder the items are listed under. Supplied by the UI because it knows
     *   it, and because the cache may have been cleared — in which case deriving the parent from a
     *   cached row would silently invalidate nothing.
     */
    suspend fun delete(
        accountId: AccountId,
        providerId: ProviderId,
        fileIds: List<FileId>,
        knownParentId: FileId? = null,
        permanent: Boolean = false,
    ): CloudResult<BatchOperationResult> {
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何文件"))
        }
        requireCapability(providerId, "operations.delete") {
            it.capabilities().operations.delete
        }?.let { return it }

        return when (
            val result = withProvider(providerId) {
                it.operations.delete(accountId, fileIds, permanent)
            }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                result.data.succeeded.forEach { key -> cache.invalidateItem(key) }
                knownParentId?.let { cache.invalidateFolder(accountId, providerId, it) }
                CloudResult.Success(result.data)
            }
        }
    }

    suspend fun setStarred(
        accountId: AccountId,
        providerId: ProviderId,
        fileIds: List<FileId>,
        starred: Boolean,
    ): CloudResult<Unit> {
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何文件"))
        }
        return when (
            val result = withProvider(providerId) {
                it.operations.setStarred(accountId, fileIds, starred)
            }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // The star flag lives on the item, not the listing, so only the item rows are dropped.
                fileIds.forEach { cache.invalidateItem(CloudFileKey(providerId, accountId, it)) }
                CloudResult.Success(Unit)
            }
        }
    }

    suspend fun share(
        accountId: AccountId,
        providerId: ProviderId,
        fileIds: List<FileId>,
        enable: Boolean = true,
        password: String? = null,
    ): CloudResult<com.notdisk.core.provider.ShareResult> {
        if (fileIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何文件"))
        }
        requireCapability(providerId, "operations.share") {
            it.capabilities().operations.share
        }?.let { return it }

        return withProvider(providerId) {
            it.operations.share(accountId, fileIds, enable, password)
        }
    }

    // ------------------------------------------------------------------ trash

    suspend fun listTrash(
        accountId: AccountId,
        providerId: ProviderId,
        cursor: String? = null,
        pageSize: Int = 30,
    ): CloudResult<TrashPage> = withProvider(providerId) {
        it.operations.listTrash(accountId, cursor, pageSize)
    }

    suspend fun restoreFromTrash(
        accountId: AccountId,
        providerId: ProviderId,
        trashIds: List<String>,
    ): CloudResult<BatchOperationResult> {
        if (trashIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何回收站条目"))
        }
        return when (
            val result = withProvider(providerId) {
                it.operations.restoreFromTrash(accountId, trashIds)
            }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // A restore puts files back into folders the cache may still hold as "without them",
                // but the cache has no way to know *which* folder. Dropping everything for the account
                // is the honest, cheap option: restore is a rare, user-initiated action.
                clearAccountCache(accountId, providerId)
                CloudResult.Success(result.data)
            }
        }
    }

    suspend fun purgeFromTrash(
        accountId: AccountId,
        providerId: ProviderId,
        trashIds: List<String>,
        safeKey: String? = null,
    ): CloudResult<BatchOperationResult> {
        if (trashIds.isEmpty()) {
            return CloudResult.Failure(CloudError.InvalidArgument(message = "未选择任何回收站条目"))
        }
        return withProvider(providerId) {
            it.operations.purgeFromTrash(accountId, trashIds, safeKey)
        }
    }

    // ------------------------------------------------------------------ downloads & preview

    /**
     * Resolves a fresh, short-lived download location.
     *
     * Called immediately before every transfer (including a resume), because the link is a signed,
     * expiring resource. Nothing about it is cached or persisted — see `DownloadSource`'s KDoc.
     */
    suspend fun prepareDownload(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
    ): CloudResult<DownloadSource> = withProvider(providerId) {
        it.operations.prepareDownload(accountId, fileId)
    }

    suspend fun preview(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        resolveAccess: Boolean = true,
        hint: PreviewHint = PreviewHint.Empty,
    ): CloudResult<PreviewResult> = withProvider(providerId) {
        it.preview.preview(accountId, fileId, resolveAccess, hint)
    }

    /**
     * Resolves a thumbnail URL.
     *
     * Deliberately **not** cached: a thumbnail URL carries `s=`/`t=` signature parameters, so caching
     * it would both serve expired images and put a credential-bearing URL in a place that outlives
     * the session. The image loader's own HTTP cache handles the "do not re-download" concern.
     */
    suspend fun thumbnail(
        accountId: AccountId,
        providerId: ProviderId,
        fileId: FileId,
        ref: com.notdisk.core.model.ThumbnailRef? = null,
    ): CloudResult<PreviewResourceOrNull> = withProvider(providerId) {
        it.preview.thumbnail(accountId, fileId, ref)
    }

    // ------------------------------------------------------------------ transfers

    suspend fun upload(
        accountId: AccountId,
        providerId: ProviderId,
        request: UploadRequest,
    ): CloudResult<TransferTask> {
        requireCapability(providerId, "operations.upload") {
            it.capabilities().operations.upload.supported
        }?.let { return it }

        return when (
            val result = withProvider(providerId) { it.operations.upload(accountId, request) }
        ) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                // An upload adds a file to the destination folder.
                cache.invalidateFolder(accountId, providerId, request.parentId)
                CloudResult.Success(result.data)
            }
        }
    }

    // ------------------------------------------------------------------ maintenance

    /** Drops everything cached for one account. Used after a bulk change and on logout. */
    suspend fun clearAccountCache(accountId: AccountId, providerId: ProviderId): CloudResult<Unit> =
        cache.invalidateFolder(accountId, providerId, parentId = null)

    // ------------------------------------------------------------------ internals

    /**
     * Refuses a page whose cursor did not advance.
     *
     * A provider that echoes the cursor it was given would make the UI's "load more" append the same
     * page forever — an infinite scroll that never ends and never errors. Catching it here turns an
     * unfixable UI hang into a clear, reportable failure. (The shared contract suite also asserts the
     * provider-side property; this is the defence for a provider that slips through.)
     */
    private fun guardCursorProgress(requested: String?, returned: String?): CloudResult<Nothing>? {
        if (requested != null && requested == returned) {
            logger.e(TAG, "provider echoed the pagination cursor $requested; refusing to loop")
            return CloudResult.Failure(
                CloudError.Unknown(
                    message = "分页游标未前进，已停止加载以避免死循环",
                    vendorCode = "cursor_not_advancing",
                ),
            )
        }
        return null
    }

    /**
     * Runs [block] against the registered provider.
     *
     * The error path is deliberately narrow: a missing provider is `Unsupported`, which the UI renders
     * as "this cloud is unavailable" rather than a crash — the situation arises legitimately when a
     * build variant drops a provider while an account row for it still exists.
     */
    private suspend fun <T> withProvider(
        providerId: ProviderId,
        block: suspend (com.notdisk.core.provider.CloudProvider) -> CloudResult<T>,
    ): CloudResult<T> = when (val provider = registry.require(providerId)) {
        is CloudResult.Failure -> CloudResult.Failure(provider.error)
        is CloudResult.Success -> block(provider.data)
    }

    /**
     * Returns a typed `Unsupported` when a capability is not declared `SUPPORTED`.
     *
     * Note it accepts `UNVERIFIED` as well: an unverified capability may still work, and refusing to
     * even try would hide a feature that the provider really implements. What must not happen is
     * declaring it `SUPPORTED` while it fails every time — that is a provider-side problem the shared
     * contract suite catches.
     */
    private fun requireCapability(
        providerId: ProviderId,
        capabilityName: String,
        read: (com.notdisk.core.provider.CloudProvider) -> CapabilitySupport,
    ): CloudResult<Nothing>? {
        val provider = registry.find(providerId)
            ?: return CloudResult.Failure(
                CloudError.Unsupported(message = "未注册的网盘 Provider: $providerId"),
            )
        return when (read(provider)) {
            CapabilitySupport.SUPPORTED, CapabilitySupport.UNVERIFIED -> null
            CapabilitySupport.UNSUPPORTED -> CloudResult.Failure(
                CloudError.Unsupported(
                    message = "${provider.displayName} 不支持该操作",
                    capability = capabilityName,
                ),
            )
        }
    }

    companion object {
        private const val TAG = "CloudFileRepository"

        /**
         * How long a cached folder listing is served without a network call.
         *
         * 30 minutes matches the settings default and is chosen for the common navigation pattern:
         * "open folder → open file → back". It is long enough that going back is instant, and short
         * enough that a user returning to the app after a break gets fresh data.
         */
        const val DEFAULT_CACHE_TTL_MILLIS = 30 * 60 * 1000L
    }
}

/** Convenience: unwraps a listing result to just the page. */
fun CloudResult<CloudFileRepository.ListingResult>.page(): CloudResult<FilePage> = map { it.page }
