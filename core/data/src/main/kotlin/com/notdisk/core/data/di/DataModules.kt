package com.notdisk.core.data.di

import android.content.Context
import androidx.room.Room
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.CompositeCloudLogger
import com.notdisk.core.common.logging.InMemoryCloudLogger
import com.notdisk.core.data.cache.FileMetadataCache
import com.notdisk.core.data.media.MediaPlaybackCoordinator
import com.notdisk.core.data.repository.AccountRepository
import com.notdisk.core.data.repository.CloudFileRepository
import com.notdisk.core.data.transfer.TransferManager
import com.notdisk.core.database.NotDiskDatabase
import com.notdisk.core.database.dao.AccountDao
import com.notdisk.core.database.dao.FavouriteDao
import com.notdisk.core.database.dao.FileMetadataDao
import com.notdisk.core.database.dao.RecentFileDao
import com.notdisk.core.database.dao.TransferTaskDao
import com.notdisk.core.database.settings.NotDiskSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Local persistence wiring.
 *
 * Everything here is `@Singleton`: the database is a single connection, the DAOs are stateless, and
 * the caches are intentionally process-wide so that two screens looking at the same folder share one
 * cached listing rather than each holding its own.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): NotDiskDatabase =
        Room.databaseBuilder(context, NotDiskDatabase::class.java, NotDiskDatabase.NAME)
            // No `fallbackToDestructiveMigration`. The cache is rebuildable but favourites and
            // transfer history are not, and silently deleting a user's data on an app update is a
            // worse outcome than a visible migration failure during development. The schema is
            // exported (`exportSchema = true`) so a real migration can be written against it.
            .build()

    @Provides
    fun provideAccountDao(db: NotDiskDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideFileMetadataDao(db: NotDiskDatabase): FileMetadataDao = db.fileMetadataDao()

    @Provides
    fun provideRecentFileDao(db: NotDiskDatabase): RecentFileDao = db.recentFileDao()

    @Provides
    fun provideFavouriteDao(db: NotDiskDatabase): FavouriteDao = db.favouriteDao()

    @Provides
    fun provideTransferTaskDao(db: NotDiskDatabase): TransferTaskDao = db.transferTaskDao()
}

/**
 * Logging.
 *
 * A single [CompositeCloudLogger] fans out to Logcat and to the in-memory ring buffer the diagnostics
 * screen reads. Both sinks receive **already-redacted** strings: caller code is required to build log
 * lines through `LogRedaction` (see `docs/security.md`), so there is no path by which a credential or
 * a signed URL reaches either sink.
 */
@Module
@InstallIn(SingletonComponent::class)
object LoggingModule {

    @Provides
    @Singleton
    fun provideInMemoryLogger(): InMemoryCloudLogger = InMemoryCloudLogger(capacity = 500)

    @Provides
    @Singleton
    fun provideLogger(inMemory: InMemoryCloudLogger): CloudLogger =
        CompositeCloudLogger(listOf(inMemory, LogcatCloudLogger()))
}

/**
 * The data layer's own services.
 *
 * Note what is **not** here: no provider implementations. Providers register themselves through a
 * Hilt multibinding set in the `app` module, which is what keeps `core:data` free of any knowledge
 * of a specific cloud. If this module had to import `provider:p115`, the architecture goal would
 * already be lost.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /**
     * The typed settings store.
     *
     * Backed by a single process-wide DataStore file. Two instances over the same file would fight
     * over writes, so it is a singleton — and the name is a distinct constant so it cannot collide
     * with another DataStore a future module might add.
     */
    @Provides
    @Singleton
    fun provideSettingsStore(
        @ApplicationContext context: Context,
    ): NotDiskSettingsStore = NotDiskSettingsStore(
        context.settingsDataStore,
    )

    @Provides
    @Singleton
    fun provideFileMetadataCache(
        dao: FileMetadataDao,
        logger: CloudLogger,
    ): FileMetadataCache = FileMetadataCache(dao = dao, logger = logger)

    /**
     * The account surface.
     *
     * Scoped as a singleton because it owns no per-screen state — it reads through to the provider and
     * the local table — and because the "authoritative provider first, local table as a fallback"
     * reconciliation is cheaper when one instance does it.
     */
    @Provides
    @Singleton
    fun provideAccountRepository(
        registry: com.notdisk.core.provider.ProviderRegistry,
        accountDao: AccountDao,
        logger: CloudLogger,
    ): AccountRepository = AccountRepository(
        registry = registry,
        accountDao = accountDao,
        logger = logger,
    )

    @Provides
    @Singleton
    fun provideCloudFileRepository(
        registry: com.notdisk.core.provider.ProviderRegistry,
        cache: FileMetadataCache,
        logger: CloudLogger,
    ): CloudFileRepository = CloudFileRepository(
        registry = registry,
        cache = cache,
        logger = logger,
    )

    /**
     * The transfer engine.
     *
     * It gets the app-wide OkHttp client so that connection pooling is shared with provider traffic
     * — a download and a directory listing to the same CDN host reuse one connection.
     */
    @Provides
    @Singleton
    fun provideTransferManager(
        @ApplicationContext context: Context,
        registry: com.notdisk.core.provider.ProviderRegistry,
        dao: TransferTaskDao,
        logger: CloudLogger,
    ): TransferManager = TransferManager(
        context = context,
        registry = registry,
        dao = dao,
        httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build(),
        logger = logger,
    )

    @Provides
    @Singleton
    fun provideMediaPlaybackCoordinator(
        registry: com.notdisk.core.provider.ProviderRegistry,
        logger: CloudLogger,
    ): MediaPlaybackCoordinator = MediaPlaybackCoordinator(
        registry = registry,
        logger = logger,
    )
}

/**
 * The settings DataStore file.
 *
 * Declared as a top-level delegate because `preferencesDataStore` is a property delegate on
 * `Context`, and because a single delegate instance is what guarantees one file handle per process.
 */
private val Context.settingsDataStore: androidx.datastore.core.DataStore<Preferences> by
    preferencesDataStore(name = "notdisk_settings")
