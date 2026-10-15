package com.notdisk.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.notdisk.core.database.dao.AccountDao
import com.notdisk.core.database.dao.FavouriteDao
import com.notdisk.core.database.dao.FileMetadataDao
import com.notdisk.core.database.dao.RecentFileDao
import com.notdisk.core.database.dao.TransferTaskDao
import com.notdisk.core.database.entity.AccountEntity
import com.notdisk.core.database.entity.FavouriteEntity
import com.notdisk.core.database.entity.FileMetadataEntity
import com.notdisk.core.database.entity.RecentFileEntity
import com.notdisk.core.database.entity.TransferTaskEntity

/**
 * The app's only Room database.
 *
 * ### What lives here, and what must not
 * This database holds **non-sensitive, rebuildable** state: account display metadata, cached file
 * metadata, recents, favourites and transfer *records*. Credentials never appear here — they live
 * behind each provider's encrypted credential store. Signed URLs never appear here either; the
 * transfer row stores a `(providerId, accountId, fileId)` triple and the transfer layer re-resolves
 * a fresh link. See `docs/security.md`.
 *
 * ### Versioning
 * `version = 1` with `exportSchema = true`. The schema JSON is committed so a future migration can
 * be written against a real baseline instead of a remembered one; a destructive fallback is *not*
 * configured, because silently wiping a user's favourites and transfer history on an app update
 * would be worse than refusing to open.
 */
@Database(
    entities = [
        AccountEntity::class,
        FileMetadataEntity::class,
        RecentFileEntity::class,
        FavouriteEntity::class,
        TransferTaskEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NotDiskDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao

    abstract fun fileMetadataDao(): FileMetadataDao

    abstract fun recentFileDao(): RecentFileDao

    abstract fun favouriteDao(): FavouriteDao

    abstract fun transferTaskDao(): TransferTaskDao

    companion object {
        const val NAME = "notdisk.db"
    }
}
