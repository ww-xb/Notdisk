package com.notdisk.core.data.cache

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.database.dao.FileMetadataDao
import com.notdisk.core.database.entity.FileMetadataEntity
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileCapabilities
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FileType
import com.notdisk.core.model.ProviderId
import com.notdisk.core.model.ThumbnailRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Converts between the unified [FileItem] and its cached row.
 *
 * ### Why the mapping is explicit rather than a serialised blob
 * A blob would be shorter to write and worse in every other way: the UI sorts, filters and searches
 * on individual columns, and a blob forces a full deserialisation for each of those. The column list
 * also acts as a review surface — it is obvious at a glance that there is no URL column, which is the
 * property that keeps signed links out of the cache.
 *
 * ### The two JSON columns
 * `capabilities` and `extensions` are genuinely record-shaped (a fixed set of nullable flags; a
 * namespaced string bag) rather than something the UI queries on, so they are stored as JSON. Both
 * are *typed* rather than `Map<String, Any>`: an unknown key in `extensions` is preserved as a
 * string rather than turning into an arbitrary object that a later reader has to guess at.
 */
internal object FileItemMapper {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun toEntity(
        item: FileItem,
        cachedAt: Long,
        accountId: AccountId,
        providerId: ProviderId,
    ): FileMetadataEntity = FileMetadataEntity(
        accountId = accountId.value,
        providerId = providerId.value,
        fileId = item.id.value,
        parentId = item.parentId?.value,
        name = item.name,
        type = item.type.name,
        mimeType = item.mimeType,
        extension = item.extension,
        size = item.size,
        createdTime = item.createdTime,
        modifiedTime = item.modifiedTime,
        // Only the *hash* and size hint are stored. The signed thumbnail URL is re-resolved on
        // demand by the preview provider, so a stale URL can never be rendered from the cache.
        thumbnailHash = item.thumbnail?.contentHash,
        thumbnailSizeHint = item.thumbnail?.sizeHint,
        capabilitiesJson = json.encodeToString(item.capabilities.toDto()),
        // The extras bag is small and typed as string→string, so it round-trips through a
        // `Map<String, String>` rather than a free-form JSON tree.
        extensionsJson = json.encodeToString(item.extensions),
        cachedAt = cachedAt,
    )

    fun toDomain(entity: FileMetadataEntity): FileItem = FileItem(
        id = FileId(entity.fileId),
        providerId = ProviderId(entity.providerId),
        accountId = AccountId(entity.accountId),
        name = entity.name,
        type = runCatching { FileType.valueOf(entity.type) }.getOrDefault(FileType.FILE),
        mimeType = entity.mimeType,
        extension = entity.extension,
        size = entity.size,
        parentId = entity.parentId?.let { FileId(it) },
        createdTime = entity.createdTime,
        modifiedTime = entity.modifiedTime,
        thumbnail = if (entity.thumbnailHash != null || entity.thumbnailSizeHint != null) {
            ThumbnailRef(sizeHint = entity.thumbnailSizeHint, contentHash = entity.thumbnailHash)
        } else {
            null
        },
        capabilities = runCatching {
            json.decodeFromString<FileCapabilitiesDto>(entity.capabilitiesJson).toDomain()
        }.getOrDefault(FileCapabilities.Unknown),
        extensions = runCatching {
            json.decodeFromString<Map<String, String>>(entity.extensionsJson)
        }.getOrDefault(emptyMap()),
    )

    /**
     * The capabilities wire shape.
     *
     * Kept as a separate DTO with the same field names as the domain type so a rename in one place is
     * caught by the compiler rather than silently producing `null` capabilities in the cache. All
     * fields are nullable because `null` genuinely means "the provider did not say", which the UI
     * treats differently from `false`.
     */
    @Serializable
    private data class FileCapabilitiesDto(
        val canDownload: Boolean? = null,
        val canStream: Boolean? = null,
        val canPreview: Boolean? = null,
        val canRename: Boolean? = null,
        val canMove: Boolean? = null,
        val canCopy: Boolean? = null,
        val canDelete: Boolean? = null,
        val canShare: Boolean? = null,
        val hasThumbnail: Boolean? = null,
        val isStarred: Boolean? = null,
        val isTop: Boolean? = null,
        val isBlocked: Boolean? = null,
    )

    private fun FileCapabilities.toDto() = FileCapabilitiesDto(
        canDownload = canDownload,
        canStream = canStream,
        canPreview = canPreview,
        canRename = canRename,
        canMove = canMove,
        canCopy = canCopy,
        canDelete = canDelete,
        canShare = canShare,
        hasThumbnail = hasThumbnail,
        isStarred = isStarred,
        isTop = isTop,
        isBlocked = isBlocked,
    )

    private fun FileCapabilitiesDto.toDomain() = FileCapabilities(
        canDownload = canDownload,
        canStream = canStream,
        canPreview = canPreview,
        canRename = canRename,
        canMove = canMove,
        canCopy = canCopy,
        canDelete = canDelete,
        canShare = canShare,
        hasThumbnail = hasThumbnail,
        isStarred = isStarred,
        isTop = isTop,
        isBlocked = isBlocked,
    )
}

/**
 * Local metadata cache in front of a provider's file system.
 *
 * ### What the cache is and is not for
 * It makes **back-navigation instant** and lets a folder render while a refresh is in flight. It is
 * **not** a source of truth: every read that the user triggers explicitly (pull-to-refresh, opening
 * a folder whose entry is older than the configured TTL) goes to the provider.
 *
 * ### Staleness policy, and why it is coarse
 * 115 publishes no change-token, no per-folder revision and no delta API (see `docs/api-mapping.md`
 * §10.2), so any fine-grained invalidation would be invented. Two honest mechanisms are used instead:
 *  1. **age**: a row older than `ttlMillis` is treated as stale and re-fetched;
 *  2. **explicit invalidation after a mutation**: rename/move/delete/create invalidate the affected
 *     parent folder, because that is the case where staleness is actually visible to the user.
 *
 * A provider that *does* offer a change token can express it later by making [isFresh] consult it;
 * that is why freshness is a method rather than a column comparison inlined at every call site.
 */
class FileMetadataCache(
    private val dao: FileMetadataDao,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Reads cached children of a folder, without checking freshness. */
    suspend fun readChildren(
        accountId: AccountId,
        providerId: ProviderId,
        parentId: FileId?,
    ): CloudResult<List<FileItem>> = runCatching {
        dao.childrenOf(accountId.value, providerId.value, parentId?.value)
            .map(FileItemMapper::toDomain)
    }.fold(
        onSuccess = { CloudResult.Success(it) },
        onFailure = { throwable ->
            logger.w(TAG, "cache read failed", throwable)
            CloudResult.Failure(
                CloudError.LocalIoError(message = "读取本地缓存失败", cause = throwable),
            )
        },
    )

    /**
     * True when every cached child of the folder is newer than [ttlMillis].
     *
     * The **oldest** row decides: a folder is only "fresh" if nothing in it could have changed
     * unseen. Checking the newest would let one freshly-touched file mask a stale sibling.
     */
    suspend fun isFresh(
        accountId: AccountId,
        providerId: ProviderId,
        parentId: FileId?,
        ttlMillis: Long,
    ): Boolean = runCatching {
        val rows = dao.childrenOf(accountId.value, providerId.value, parentId?.value)
        rows.isNotEmpty() && rows.minOf { it.cachedAt } >= clock() - ttlMillis
    }.getOrDefault(false)

    /** Writes a freshly-loaded page, replacing whatever was cached for that parent. */
    suspend fun replaceChildren(
        accountId: AccountId,
        providerId: ProviderId,
        parentId: FileId?,
        items: List<FileItem>,
    ): CloudResult<Unit> = runCatching {
        val now = clock()
        // A folder listing is authoritative for that folder, so stale rows for the same parent are
        // dropped first. Without this, a file deleted on the server would linger in the cache
        // forever, which is exactly the "cache lies about a deletion" failure mode.
        dao.deleteChildrenOf(accountId.value, providerId.value, parentId?.value)
        dao.upsertAll(items.map { FileItemMapper.toEntity(it, now, accountId, providerId) })
    }.fold(
        onSuccess = { CloudResult.Success(Unit) },
        onFailure = { throwable ->
            logger.w(TAG, "cache write failed", throwable)
            CloudResult.Failure(
                CloudError.LocalIoError(message = "写入本地缓存失败", cause = throwable),
            )
        },
    )

    /** Caches a single item (e.g. a detail fetch). */
    suspend fun put(item: FileItem): CloudResult<Unit> = runCatching {
        dao.upsert(
            FileItemMapper.toEntity(item, clock(), item.accountId, item.providerId),
        )
    }.fold(
        onSuccess = { CloudResult.Success(Unit) },
        onFailure = { throwable ->
            logger.w(TAG, "cache write failed", throwable)
            CloudResult.Failure(
                CloudError.LocalIoError(message = "写入本地缓存失败", cause = throwable),
            )
        },
    )

    suspend fun find(key: CloudFileKey): CloudResult<FileItem?> = runCatching {
        dao.find(key.accountId.value, key.providerId.value, key.fileId.value)
            ?.let(FileItemMapper::toDomain)
    }.fold(
        onSuccess = { CloudResult.Success(it) },
        onFailure = { throwable ->
            CloudResult.Failure(
                CloudError.LocalIoError(message = "读取本地缓存失败", cause = throwable),
            )
        },
    )

    /**
     * Drops a folder's cached children.
     *
     * Called after every mutation that could change a listing. This is the mechanism that makes the
     * cache *correct* rather than merely fast: without it, a rename would leave the old name on
     * screen until the TTL expired, which users read as "the rename did not work".
     */
    suspend fun invalidateFolder(
        accountId: AccountId,
        providerId: ProviderId,
        parentId: FileId?,
    ): CloudResult<Unit> = runCatching {
        dao.deleteChildrenOf(accountId.value, providerId.value, parentId?.value)
    }.fold(
        onSuccess = { CloudResult.Success(Unit) },
        onFailure = { throwable ->
            CloudResult.Failure(
                CloudError.LocalIoError(message = "清理缓存失败", cause = throwable),
            )
        },
    )

    /** Drops one cached item (e.g. after it was deleted or moved away). */
    suspend fun invalidateItem(key: CloudFileKey): CloudResult<Unit> = runCatching {
        dao.delete(key.accountId.value, key.providerId.value, key.fileId.value)
    }.fold(
        onSuccess = { CloudResult.Success(Unit) },
        onFailure = { throwable ->
            CloudResult.Failure(
                CloudError.LocalIoError(message = "清理缓存失败", cause = throwable),
            )
        },
    )

    /** Local, offline search over whatever is cached. Complements the provider's server-side search. */
    suspend fun searchLocal(
        accountId: AccountId,
        providerId: ProviderId,
        keyword: String,
        limit: Int = 100,
    ): CloudResult<List<FileItem>> = runCatching {
        dao.searchLocal(accountId.value, providerId.value, keyword, limit)
            .map(FileItemMapper::toDomain)
    }.fold(
        onSuccess = { CloudResult.Success(it) },
        onFailure = { throwable ->
            CloudResult.Failure(
                CloudError.LocalIoError(message = "本地搜索失败", cause = throwable),
            )
        },
    )

    /**
     * Age-based eviction.
     *
     * Runs opportunistically (on app start), not on a timer: the rows are rebuildable, so the only
     * cost of keeping them too long is disk, while the cost of a background job is battery and
     * complexity.
     */
    suspend fun evictStale(olderThanMillis: Long = DEFAULT_TTL_MILLIS): CloudResult<Int> =
        runCatching { dao.evictOlderThan(clock() - olderThanMillis) }.fold(
            onSuccess = { removed ->
                if (removed > 0) logger.i(TAG, "evicted $removed stale metadata rows")
                CloudResult.Success(removed)
            },
            onFailure = { throwable ->
                CloudResult.Failure(
                    CloudError.LocalIoError(message = "缓存清理失败", cause = throwable),
                )
            },
        )

    /**
     * Whether the cache is usable for a provider at all.
     *
     * A provider that cannot list children has nothing worth caching, and the repository uses this to
     * skip the cache entirely rather than maintain a table that will always be empty.
     */
    fun isCacheable(provider: com.notdisk.core.provider.CloudProvider): Boolean =
        provider.capabilities().fileSystem.listChildren == CapabilitySupport.SUPPORTED

    /** Reads an already-cached item without touching the network; `null` when absent. */
    suspend fun peek(key: CloudFileKey): FileItem? = find(key).getOrNull()

    companion object {
        private const val TAG = "FileMetadataCache"

        /** 24 hours. Rows are rebuildable, so this only bounds disk usage. */
        const val DEFAULT_TTL_MILLIS = 24 * 60 * 60 * 1000L
    }
}
