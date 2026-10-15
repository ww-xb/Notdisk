package com.notdisk.core.database.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Typed, non-sensitive user settings.
 *
 * ### Why DataStore rather than Room
 * These are a handful of scalars read on every screen. DataStore gives atomic writes and a `Flow` of
 * changes without a schema, a DAO and a migration for every new toggle.
 *
 * ### Why strict types instead of `Map<String, Any>`
 * The brief forbids free-form maps in shared models, and it applies here too: a settings bag that
 * accepts anything makes it impossible to tell which keys are live, and a typo silently reads the
 * default. Every setting is therefore a named property with a declared default.
 *
 * ### What must not go here
 * Credentials (encrypted provider store), signed URLs (nowhere), and anything per-file (Room).
 */
class NotDiskSettingsStore(
    private val dataStore: DataStore<Preferences>,
) {

    /** How the file list is displayed. */
    enum class ViewMode { LIST, GRID;
        companion object {
            fun fromName(value: String?): ViewMode =
                entries.firstOrNull { it.name == value } ?: LIST
        }
    }

    /** Theme preference. `SYSTEM` defers to the device. */
    enum class ThemeChoice { SYSTEM, LIGHT, DARK;
        companion object {
            fun fromName(value: String?): ThemeChoice =
                entries.firstOrNull { it.name == value } ?: SYSTEM
        }
    }

    /** Default sort applied to a freshly opened folder. */
    enum class SortChoice { NAME, MODIFIED_TIME, SIZE, TYPE;
        companion object {
            fun fromName(value: String?): SortChoice =
                entries.firstOrNull { it.name == value } ?: MODIFIED_TIME
        }
    }

    val viewMode: Flow<ViewMode> =
        dataStore.data.map { ViewMode.fromName(it[KEY_VIEW_MODE]) }

    val themeChoice: Flow<ThemeChoice> =
        dataStore.data.map { ThemeChoice.fromName(it[KEY_THEME]) }

    val dynamicColor: Flow<Boolean> =
        dataStore.data.map { it[KEY_DYNAMIC_COLOR] ?: true }

    val defaultSort: Flow<SortChoice> =
        dataStore.data.map { SortChoice.fromName(it[KEY_SORT]) }

    val sortAscending: Flow<Boolean> =
        dataStore.data.map { it[KEY_SORT_ASC] ?: false }

    val naturalSort: Flow<Boolean> =
        dataStore.data.map { it[KEY_NATURAL_SORT] ?: true }

    /** Page size requested from providers. Clamped on read so a corrupt value cannot break paging. */
    val pageSize: Flow<Int> =
        dataStore.data.map { (it[KEY_PAGE_SIZE] ?: DEFAULT_PAGE_SIZE).coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE) }

    /** Whether thumbnails are fetched while scrolling. Off means a faster, data-light list. */
    val loadThumbnails: Flow<Boolean> =
        dataStore.data.map { it[KEY_THUMBNAILS] ?: true }

    /**
     * Whether to prefer the original image over the thumbnail in the viewer.
     * Default `false`: originals can be tens of megabytes.
     */
    val preferOriginalImage: Flow<Boolean> =
        dataStore.data.map { it[KEY_PREFER_ORIGINAL] ?: false }

    /** The account the UI opens by default. Stored as `providerId\u0000accountId`. */
    val activeAccount: Flow<Pair<String, String>?> =
        dataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_ACCOUNT]?.split(SEPARATOR)?.takeIf { it.size == 2 }?.let {
                it[0] to it[1]
            }
        }

    /** Age after which cached file metadata is treated as stale, in milliseconds. */
    val cacheTtlMillis: Flow<Long> =
        dataStore.data.map { (it[KEY_CACHE_TTL_MINUTES] ?: DEFAULT_CACHE_TTL_MINUTES).toLong() * 60_000L }

    /** Whether the app should record recently-opened files at all (a privacy toggle). */
    val recordRecents: Flow<Boolean> =
        dataStore.data.map { it[KEY_RECORD_RECENTS] ?: true }

    // ------------------------------------------------------------------ writers

    suspend fun setViewMode(mode: ViewMode) = put(KEY_VIEW_MODE, mode.name)

    suspend fun setThemeChoice(choice: ThemeChoice) = put(KEY_THEME, choice.name)

    suspend fun setDynamicColor(enabled: Boolean) = put(KEY_DYNAMIC_COLOR, enabled)

    suspend fun setDefaultSort(choice: SortChoice) = put(KEY_SORT, choice.name)

    suspend fun setSortAscending(ascending: Boolean) = put(KEY_SORT_ASC, ascending)

    suspend fun setNaturalSort(enabled: Boolean) = put(KEY_NATURAL_SORT, enabled)

    suspend fun setPageSize(size: Int) =
        put(KEY_PAGE_SIZE, size.coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE))

    suspend fun setLoadThumbnails(enabled: Boolean) = put(KEY_THUMBNAILS, enabled)

    suspend fun setPreferOriginalImage(enabled: Boolean) = put(KEY_PREFER_ORIGINAL, enabled)

    suspend fun setActiveAccount(providerId: String, accountId: String) =
        put(KEY_ACTIVE_ACCOUNT, "$providerId$SEPARATOR$accountId")

    suspend fun clearActiveAccount() {
        dataStore.edit { it.remove(KEY_ACTIVE_ACCOUNT) }
    }

    suspend fun setCacheTtlMinutes(minutes: Int) =
        put(KEY_CACHE_TTL_MINUTES, minutes.coerceIn(1, 60 * 24 * 30))

    suspend fun setRecordRecents(enabled: Boolean) = put(KEY_RECORD_RECENTS, enabled)

    /** Snapshot read, for code paths that cannot suspend over a `Flow` (e.g. one-shot setup). */
    suspend fun snapshot(): SettingsSnapshot {
        val prefs = dataStore.data.first()
        return SettingsSnapshot(
            viewMode = ViewMode.fromName(prefs[KEY_VIEW_MODE]),
            themeChoice = ThemeChoice.fromName(prefs[KEY_THEME]),
            dynamicColor = prefs[KEY_DYNAMIC_COLOR] ?: true,
            defaultSort = SortChoice.fromName(prefs[KEY_SORT]),
            sortAscending = prefs[KEY_SORT_ASC] ?: false,
            naturalSort = prefs[KEY_NATURAL_SORT] ?: true,
            pageSize = (prefs[KEY_PAGE_SIZE] ?: DEFAULT_PAGE_SIZE)
                .coerceIn(MIN_PAGE_SIZE, MAX_PAGE_SIZE),
            loadThumbnails = prefs[KEY_THUMBNAILS] ?: true,
            preferOriginalImage = prefs[KEY_PREFER_ORIGINAL] ?: false,
            recordRecents = prefs[KEY_RECORD_RECENTS] ?: true,
        )
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { it[key] = value }
    }

    data class SettingsSnapshot(
        val viewMode: ViewMode,
        val themeChoice: ThemeChoice,
        val dynamicColor: Boolean,
        val defaultSort: SortChoice,
        val sortAscending: Boolean,
        val naturalSort: Boolean,
        val pageSize: Int,
        val loadThumbnails: Boolean,
        val preferOriginalImage: Boolean,
        val recordRecents: Boolean,
    )

    companion object {
        /**
         * The separator used to store a `(providerId, accountId)` pair in one preference.
         *
         * `\u0000` because neither id may contain it (both are validated non-blank strings used as
         * database keys), so the pair can never be ambiguous — a `:` or `|` separator would break the
         * moment an id contained one.
         */
        private const val SEPARATOR = "\u0000"

        private val KEY_VIEW_MODE = stringPreferencesKey("view_mode")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        private val KEY_SORT = stringPreferencesKey("sort_field")
        private val KEY_SORT_ASC = booleanPreferencesKey("sort_ascending")
        private val KEY_NATURAL_SORT = booleanPreferencesKey("natural_sort")
        private val KEY_PAGE_SIZE = intPreferencesKey("page_size")
        private val KEY_THUMBNAILS = booleanPreferencesKey("load_thumbnails")
        private val KEY_PREFER_ORIGINAL = booleanPreferencesKey("prefer_original_image")
        private val KEY_ACTIVE_ACCOUNT = stringPreferencesKey("active_account")
        private val KEY_CACHE_TTL_MINUTES = intPreferencesKey("cache_ttl_minutes")
        private val KEY_RECORD_RECENTS = booleanPreferencesKey("record_recents")

        const val DEFAULT_PAGE_SIZE = 40
        const val MIN_PAGE_SIZE = 10
        const val MAX_PAGE_SIZE = 200

        /** 30 minutes: long enough to make back-navigation instant, short enough to stay fresh. */
        const val DEFAULT_CACHE_TTL_MINUTES = 30
    }
}
