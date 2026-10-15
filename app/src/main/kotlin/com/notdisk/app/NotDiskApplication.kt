package com.notdisk.app

import android.app.Application
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.data.cache.FileMetadataCache
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The application entry point.
 *
 * ### Why it does housekeeping
 * Two jobs belong here and nowhere else, because both are process-scoped and must happen exactly once
 * per launch:
 *
 *  1. **Cache eviction.** Cached file metadata is rebuildable, so an age-based sweep on startup is
 *     enough; a scheduled job would cost battery for no benefit. Running it here rather than on a
 *     background scheduler also means it never races the first screen's reads.
 *  2. **Registration logging.** Recording which providers were registered makes a misconfigured DI
 *     graph (a missing module) obvious in the diagnostics screen instead of surfacing as an
 *     inexplicably empty account list.
 *
 * It deliberately does **not** create the database eagerly, contact any network, or read credentials:
 * all of that is lazy, so a cold start is not blocked by work the first screen may not need.
 */
@HiltAndroidApp
class NotDiskApplication : Application() {

    @Inject
    lateinit var logger: CloudLogger

    @Inject
    lateinit var fileMetadataCache: FileMetadataCache

    @Inject
    lateinit var registry: com.notdisk.core.provider.ProviderRegistry

    /**
     * A process-lifetime scope for startup work.
     *
     * `SupervisorJob` so that one failing startup task cannot cancel the others; this is the one place
     * in the app where an unstructured scope is correct, because the work must outlive any Activity
     * but must not outlive the process.
     */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        startupScope.launch {
            val providers = registry.descriptors()
            logger.i(
                TAG,
                "startup: ${providers.size} provider(s) registered " +
                    providers.joinToString { it.id.value },
            )

            when (val evicted = fileMetadataCache.evictStale()) {
                is com.notdisk.core.common.result.CloudResult.Success ->
                    logger.d(TAG, "startup: evicted ${evicted.data} stale metadata rows")

                is com.notdisk.core.common.result.CloudResult.Failure ->
                    // A failed sweep is not worth interrupting the user for: the cache is rebuildable
                    // and the next launch will try again.
                    logger.w(TAG, "startup: cache eviction failed (${evicted.error::class.simpleName})")
            }
        }
    }

    private companion object {
        const val TAG = "App"
    }
}
