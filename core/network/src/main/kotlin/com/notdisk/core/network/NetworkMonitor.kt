package com.notdisk.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.content.ContextCompat
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.logging.NoOpCloudLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Answers one question for the whole app: *is there a usable network right now?*
 *
 * ### Why this abstraction exists
 * Connectivity advice is easy to get wrong in a way that is invisible in review and infuriating in
 * use: telling a user "no network" while a captive portal, a VPN handover or an airplane-mode toggle
 * is mid-flight; or, in the other direction, letting a screen sit on a spinner forever when the
 * device is genuinely offline. Providers must be able to make two decisions from one source of
 * truth — "fail fast with [com.notdisk.core.common.error.CloudError.NetworkUnavailable]" and
 * "wait for connectivity and then retry" — and neither decision belongs in a provider, because a
 * provider cannot know about `ConnectivityManager` and must remain testable on a plain JVM.
 *
 * ### Why a `Flow<Boolean>` and not a callback
 * The UI and the retry policy both need the *stream* (to re-enable a button, to resume a queue) while
 * most call sites need only the *current* value. Modelling the stream as a cold `Flow` and the
 * current value as a separate, cheap function keeps a subscriber from accidentally owning a
 * `ConnectivityManager` registration just to ask one question, and it makes the "no subscribers =
 * no registration" property structural rather than a thing a developer must remember.
 *
 * ### Contract for implementations
 *  - [isOnline] is cold: no `ConnectivityManager` callback is registered until it is collected, and
 *    the registration is removed when the collector cancels. This is what keeps the Android
 *    implementation leak-free.
 *  - [isOnline] emits the *current* value immediately on collection, then every change, and never
 *    repeats an unchanged value.
 *  - [currentState] answers without subscribing and must never throw: a transport decision that can
 *    itself fail is worse than a conservative answer.
 */
interface NetworkMonitor {

    /**
     * Emits `true` while at least one network is validated for internet access, `false` otherwise.
     *
     * Cold: collecting registers the underlying OS callback, cancelling unregisters it.
     */
    val isOnline: Flow<Boolean>

    /**
     * The current answer, for callers that are about to issue exactly one request.
     *
     * Deliberately *not* a suspend function: it backs synchronous decisions such as "do not even
     * build the request", and a suspend call there would just move the same question one frame later
     * while adding a cancellation point to every call site.
     */
    fun currentState(): Boolean
}

/**
 * Reports "always online".
 *
 * ### Why this ships in `main` rather than in the test source set
 * Two production cases need it: a pure-JVM consumer of this module (there is no `ConnectivityManager`
 * off-device), and any future code path where connectivity is not the failure being modelled — for
 * example a direct Ethernet-connected kiosk build, or a diagnostics tool that must attempt a request
 * even when the OS claims the network is down. If it lived in `test`, both of those would either
 * reimplement it or, worse, stub the interface with `mockk` and lose the guarantee that "always
 * online" never blocks.
 *
 * It emits a single `true` and completes: a collector that expects a hot stream must not be led to
 * believe a real connectivity callback is still attached.
 */
object AlwaysOnlineNetworkMonitor : NetworkMonitor {

    override val isOnline: Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true)

    override fun currentState(): Boolean = true
}

/**
 * The Android implementation, backed by `ConnectivityManager`.
 *
 * ### What "online" means here, and why it is not `activeNetwork != null`
 * The cheap implementation asks for the active network and treats non-null as online. That answer is
 * wrong in both directions on a real phone:
 *  - an active Wi-Fi network that has not passed captive-portal validation is reported as connected
 *    while every request is intercepted by the portal — the user sees "online" and then a parse
 *    error, because the portal answered with an HTML login page;
 *  - a VPN or a secondary SIM can be the network that actually carries traffic while
 *    `activeNetwork` points at a Wi-Fi link with no upstream.
 * The implementation below therefore takes the *validated internet* capability as the criterion, and
 * it additionally re-reads the state whenever any network's capabilities change, so a portal that
 * finishes validating flips the stream to `true` without waiting for the user to toggle Wi-Fi.
 *
 * ### Why the state is also cached in a [StateFlow]
 * [currentState] must be answerable without collecting, and the OS query it would otherwise perform
 * is a binder call. Keeping the last observed value in a `StateFlow` makes the common case free and,
 * more importantly, makes it *consistent* with the last value [isOnline] emitted: a screen that reads
 * [currentState] and a screen that collects [isOnline] can never disagree.
 *
 * ### Leak safety
 * The callback is registered inside [callbackFlow] and unregistered in [awaitClose]. There is exactly
 * one registration per active collector, it is removed when the collector is cancelled or the scope
 * dies, and no reference to the callback escapes. A second registration is never created because a
 * cold flow re-runs its block per collector and each run owns its own callback instance.
 *
 * ### When `ACCESS_NETWORK_STATE` is missing or the service is unavailable
 * The permission can be absent (a stripped build, a background-restricted profile) and the system
 * service can legitimately be null on unusual devices. Both cases are handled by answering `true`
 * and logging once at debug level: connectivity is *advisory* here, and a monitor that lies "you are
 * offline" would block every request forever with no way for the user to fix it. Failing open keeps
 * the app functional and lets the request produce a real, typed error instead.
 *
 * @property context any `Context`; the application context is extracted, so holding this object does
 *   not keep an Activity alive.
 * @property logger receives one debug line when connectivity cannot be observed. Never logs SSIDs,
 *   network names or any identifier — those are location-correlated personal data.
 */
class AndroidNetworkMonitor(
    context: Context,
    private val logger: CloudLogger = NoOpCloudLogger,
) : NetworkMonitor {

    private val applicationContext: Context = context.applicationContext

    /**
     * The system service, or `null` when this build cannot see it.
     *
     * Resolved once: `getSystemService` is cheap but the null case must be decided once so that every
     * call site cannot disagree about whether connectivity is observable.
     */
    private val connectivityManager: ConnectivityManager? =
        applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Whether connectivity can be observed at all. When `false`, both [isOnline] and [currentState]
     * answer `true` (see the class KDoc) instead of pretending the device is offline.
     */
    private val canObserve: Boolean = connectivityManager != null && hasNetworkStatePermission()

    private val state = MutableStateFlow(readCurrentState())

    override val isOnline: Flow<Boolean> = callbackFlow {
        val manager = connectivityManager
        if (!canObserve || manager == null) {
            // Fails open, and says so once so that a missing permission in a stripped build is
            // diagnosable rather than mysterious.
            logger.d(TAG, "connectivity not observable; reporting always-online")
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        val callback = object : ConnectivityManager.NetworkCallback() {

            /**
             * Any of these means "re-evaluate": a network appeared, disappeared, or changed its
             * capabilities (which is how a captive portal finishing validation surfaces).
             * Re-querying the manager rather than interpreting the callback's own arguments keeps
             * one code path — [readCurrentState] — as the single definition of "online".
             */
            private fun publish() {
                val online = readCurrentState()
                state.value = online
                trySend(online)
            }

            override fun onAvailable(network: Network) = publish()

            override fun onLost(network: Network) = publish()

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) = publish()

            override fun onUnavailable() = publish()
        }

        // `registerDefaultNetworkCallback` would be simpler, but it only reports the *default*
        // network: a validated VPN + Wi-Fi pair would then flap the stream, and a network that is
        // validated but not yet default would be missed. A request for the internet capability
        // reports every network that can carry traffic, which is what "is the app online" means.
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        trySend(readCurrentState())

        val registered = try {
            manager.registerNetworkCallback(request, callback)
            true
        } catch (security: SecurityException) {
            // The permission check above can race a permission revocation; treat it exactly like the
            // unobservable case rather than crashing the collector.
            logger.w(TAG, "registerNetworkCallback denied; reporting always-online", security)
            false
        }

        if (!registered) {
            trySend(true)
            awaitClose { }
            return@callbackFlow
        }

        awaitClose {
            // Unregistering is what makes this leak-free. It can itself throw if the manager is torn
            // down during process death, which must not turn a clean cancellation into a crash.
            runCatching { manager.unregisterNetworkCallback(callback) }
                .onFailure { logger.d(TAG, "unregisterNetworkCallback failed: ${it.javaClass.simpleName}") }
        }
    }.distinctUntilChanged()

    override fun currentState(): Boolean = state.value

    /**
     * Reads the OS's current verdict.
     *
     * `NET_CAPABILITY_VALIDATED` is required in addition to `NET_CAPABILITY_INTERNET` because the
     * latter only says the network *claims* to reach the internet — it is exactly the flag a captive
     * portal sets before it has let anything through.
     */
    private fun readCurrentState(): Boolean {
        val manager = connectivityManager ?: return true
        if (!canObserve) return true
        return try {
            val active = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(active) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (security: SecurityException) {
            logger.d(TAG, "connectivity query denied; reporting online: ${security.javaClass.simpleName}")
            true
        }
    }

    /**
     * `ACCESS_NETWORK_STATE` is a normal (install-time) permission, but it can be stripped from a
     * build or revoked for a restricted profile. Checking it explicitly is what turns a
     * `SecurityException` at registration time into a predictable, documented answer.
     */
    private fun hasNetworkStatePermission(): Boolean = try {
        ContextCompat.checkSelfPermission(
            applicationContext,
            android.Manifest.permission.ACCESS_NETWORK_STATE,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        // `checkSelfPermission` cannot normally throw, but a Robolectric/JVM context without a
        // package manager can; failing open is the documented policy.
        logger.d(TAG, "permission check unavailable: ${t.javaClass.simpleName}")
        false
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

/**
 * A [NetworkMonitor] whose answer the caller controls, for tests and for feature flags.
 *
 * ### Why this is not `mockk`
 * Connectivity is read from background threads and from UI state holders at the same time. A mock
 * with a stubbed `currentState()` returns a value that no flow will ever emit, so a test that asserts
 * "the retry resumes when the network returns" would pass against a monitor that can never change
 * its mind. This implementation drives *both* [currentState] and [isOnline] from one [StateFlow], so
 * the two can never disagree — which is precisely the property the real implementation is designed
 * around.
 *
 * @property initiallyOnline starting value.
 */
class MutableNetworkMonitor(initiallyOnline: Boolean = true) : NetworkMonitor {

    private val state = MutableStateFlow(initiallyOnline)

    override val isOnline: StateFlow<Boolean> = state.asStateFlow()

    override fun currentState(): Boolean = state.value

    /** Flips the reported state and notifies every collector. */
    fun setOnline(online: Boolean) {
        state.value = online
    }
}
