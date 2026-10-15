package com.notdisk.feature.player.testing

import com.notdisk.core.database.dao.FileMetadataDao
import com.notdisk.core.database.entity.FileMetadataEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A hand-written [FileMetadataDao] that behaves like a cache which never hit.
 *
 * ### Why this exists instead of a mock
 * `PlayerViewModel` reads the file's *name* through `CloudFileRepository.getFile`, which consults
 * `FileMetadataCache` before the provider. That makes the DAO an incidental collaborator: the
 * ViewModel's behaviour is not about caching, but an unstubbed DAO would be a `NullPointerException`
 * in the middle of an unrelated test.
 *
 * Modelling it as an always-miss, never-store cache is both simpler than a mock and *more honest*: it
 * is a real, describable state of the production cache (cold start, or a file never listed), so the
 * code path it exercises is one users actually hit. Every method that could influence an assertion
 * returns a real value; the pure writes are no-ops, which is exactly what "the cache did not retain
 * this" means.
 */
class FakeFileMetadataDao : FileMetadataDao {

    /** Rows the repository attempted to cache, kept only so a test may inspect them if it wishes. */
    val stored = mutableListOf<FileMetadataEntity>()

    /** Every row is reported absent: `peek` therefore always misses and the provider is consulted. */
    override suspend fun find(
        accountId: String,
        providerId: String,
        fileId: String,
    ): FileMetadataEntity? = null

    override suspend fun upsert(item: FileMetadataEntity) {
        stored += item
    }

    override suspend fun upsertAll(items: List<FileMetadataEntity>) {
        stored += items
    }

    override suspend fun childrenOf(
        accountId: String,
        providerId: String,
        parentId: String?,
    ): List<FileMetadataEntity> = emptyList()

    override fun observeChildrenOf(
        accountId: String,
        providerId: String,
        parentId: String?,
    ): Flow<List<FileMetadataEntity>> = flowOf(emptyList())

    override suspend fun searchLocal(
        accountId: String,
        providerId: String,
        keyword: String,
        limit: Int,
    ): List<FileMetadataEntity> = emptyList()

    override suspend fun delete(accountId: String, providerId: String, fileId: String) = Unit

    override suspend fun deleteChildrenOf(accountId: String, providerId: String, parentId: String?) = Unit

    override suspend fun evictOlderThan(threshold: Long): Int = 0

    override suspend fun countFor(accountId: String, providerId: String): Int = 0

    override suspend fun clearAll() {
        stored.clear()
    }
}
