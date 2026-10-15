package com.notdisk.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.notdisk.core.database.entity.AccountEntity
import com.notdisk.core.database.entity.FavouriteEntity
import com.notdisk.core.database.entity.FileMetadataEntity
import com.notdisk.core.database.entity.RecentFileEntity
import com.notdisk.core.database.entity.TransferTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Account rows.
 *
 * Every method is scoped by `providerId` where it matters, because the same local `accountId` is
 * only unique together with its provider.
 */
@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts ORDER BY addedAt ASC")
    fun observeAll(): Flow<List<AccountEntity>>

    @Query("SELECT * FROM accounts ORDER BY addedAt ASC")
    suspend fun getAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun find(accountId: String, providerId: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE providerId = :providerId ORDER BY addedAt ASC")
    suspend fun findByProvider(providerId: String): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE providerId = :providerId AND remoteUserId = :remoteUserId LIMIT 1")
    suspend fun findByRemoteUserId(providerId: String, remoteUserId: String): AccountEntity?

    @Query("UPDATE accounts SET authState = :authState WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun updateAuthState(accountId: String, providerId: String, authState: String)

    /**
     * Removes an account **and** everything scoped to it.
     *
     * `@Transaction` is essential: leaving orphaned file-metadata or transfer rows behind would make
     * a later re-bind of the same vendor account show another account's stale data. SQLite's
     * `ON DELETE CASCADE` covers `recent_files`, but the other tables have no foreign key (they must
     * survive independently of the account row's lifetime in normal operation), so the deletes are
     * explicit.
     */
    @Transaction
    suspend fun deleteCascade(accountId: String, providerId: String) {
        deleteFiles(accountId, providerId)
        deleteRecents(accountId, providerId)
        deleteFavourites(accountId, providerId)
        deleteTransfers(accountId, providerId)
        delete(accountId, providerId)
    }

    @Query("DELETE FROM accounts WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun delete(accountId: String, providerId: String)

    @Query("DELETE FROM file_metadata WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun deleteFiles(accountId: String, providerId: String)

    @Query("DELETE FROM recent_files WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun deleteRecents(accountId: String, providerId: String)

    @Query("DELETE FROM favourites WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun deleteFavourites(accountId: String, providerId: String)

    @Query("DELETE FROM transfer_tasks WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun deleteTransfers(accountId: String, providerId: String)
}

/** Cached file metadata. */
@Dao
interface FileMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<FileMetadataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: FileMetadataEntity)

    /** Children of a folder, ordered the way a file browser shows them: folders first, then name. */
    @Query(
        """
        SELECT * FROM file_metadata
        WHERE accountId = :accountId AND providerId = :providerId AND parentId = :parentId
        ORDER BY CASE WHEN type = 'FOLDER' THEN 0 ELSE 1 END, name COLLATE NOCASE ASC
        """,
    )
    suspend fun childrenOf(
        accountId: String,
        providerId: String,
        parentId: String?,
    ): List<FileMetadataEntity>

    @Query(
        """
        SELECT * FROM file_metadata
        WHERE accountId = :accountId AND providerId = :providerId AND parentId = :parentId
        ORDER BY CASE WHEN type = 'FOLDER' THEN 0 ELSE 1 END, name COLLATE NOCASE ASC
        """,
    )
    fun observeChildrenOf(
        accountId: String,
        providerId: String,
        parentId: String?,
    ): Flow<List<FileMetadataEntity>>

    @Query("SELECT * FROM file_metadata WHERE accountId = :accountId AND providerId = :providerId AND fileId = :fileId")
    suspend fun find(accountId: String, providerId: String, fileId: String): FileMetadataEntity?

    @Query(
        """
        SELECT * FROM file_metadata
        WHERE accountId = :accountId AND providerId = :providerId AND name LIKE '%' || :keyword || '%'
        ORDER BY CASE WHEN type = 'FOLDER' THEN 0 ELSE 1 END, name COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun searchLocal(
        accountId: String,
        providerId: String,
        keyword: String,
        limit: Int,
    ): List<FileMetadataEntity>

    @Query("DELETE FROM file_metadata WHERE accountId = :accountId AND providerId = :providerId AND fileId = :fileId")
    suspend fun delete(accountId: String, providerId: String, fileId: String)

    @Query("DELETE FROM file_metadata WHERE accountId = :accountId AND providerId = :providerId AND parentId = :parentId")
    suspend fun deleteChildrenOf(accountId: String, providerId: String, parentId: String?)

    /**
     * Evicts rows written before [threshold].
     *
     * Cache expiry is deliberately coarse (age-based) rather than per-item TTLs: 115 gives no
     * change-token, so any finer scheme would be a guess. The repository additionally invalidates a
     * folder explicitly after a mutation, which is the case where staleness is actually visible.
     */
    @Query("DELETE FROM file_metadata WHERE cachedAt < :threshold")
    suspend fun evictOlderThan(threshold: Long): Int

    @Query("SELECT COUNT(*) FROM file_metadata WHERE accountId = :accountId AND providerId = :providerId")
    suspend fun countFor(accountId: String, providerId: String): Int

    @Query("DELETE FROM file_metadata")
    suspend fun clearAll()
}

/** Recently opened items. */
@Dao
interface RecentFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentFileEntity)

    @Query("SELECT * FROM recent_files ORDER BY openedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentFileEntity>>

    @Query(
        """
        SELECT * FROM recent_files
        WHERE accountId = :accountId AND providerId = :providerId
        ORDER BY openedAt DESC LIMIT :limit
        """,
    )
    suspend fun recentFor(accountId: String, providerId: String, limit: Int): List<RecentFileEntity>

    @Query("DELETE FROM recent_files WHERE accountId = :accountId AND providerId = :providerId AND fileId = :fileId")
    suspend fun delete(accountId: String, providerId: String, fileId: String)

    @Query("DELETE FROM recent_files")
    suspend fun clearAll()
}

/** Favourites. */
@Dao
interface FavouriteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FavouriteEntity)

    @Query("SELECT * FROM favourites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavouriteEntity>>

    @Query("SELECT * FROM favourites WHERE accountId = :accountId AND providerId = :providerId ORDER BY addedAt DESC")
    suspend fun forAccount(accountId: String, providerId: String): List<FavouriteEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM favourites WHERE accountId = :accountId AND providerId = :providerId AND fileId = :fileId)")
    suspend fun isFavourite(accountId: String, providerId: String, fileId: String): Boolean

    @Query("DELETE FROM favourites WHERE accountId = :accountId AND providerId = :providerId AND fileId = :fileId")
    suspend fun delete(accountId: String, providerId: String, fileId: String)

    @Query("DELETE FROM favourites")
    suspend fun clearAll()
}

/** Transfer task rows. */
@Dao
interface TransferTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TransferTaskEntity)

    @Query("SELECT * FROM transfer_tasks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<TransferTaskEntity>>

    @Query("SELECT * FROM transfer_tasks WHERE taskId = :taskId")
    suspend fun find(taskId: String): TransferTaskEntity?

    @Query("SELECT * FROM transfer_tasks WHERE status IN ('QUEUED', 'RUNNING', 'PAUSED') ORDER BY createdAt ASC")
    suspend fun findUnfinished(): List<TransferTaskEntity>

    @Query("SELECT * FROM transfer_tasks WHERE accountId = :accountId AND providerId = :providerId ORDER BY updatedAt DESC")
    suspend fun forAccount(accountId: String, providerId: String): List<TransferTaskEntity>

    @Query("UPDATE transfer_tasks SET status = :status, errorMessage = :errorMessage, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun updateStatus(taskId: String, status: String, errorMessage: String?, updatedAt: Long)

    @Query("DELETE FROM transfer_tasks WHERE taskId = :taskId")
    suspend fun delete(taskId: String)

    @Query("DELETE FROM transfer_tasks WHERE status IN ('COMPLETED', 'CANCELLED')")
    suspend fun clearFinished(): Int

    @Query("DELETE FROM transfer_tasks")
    suspend fun clearAll()
}
