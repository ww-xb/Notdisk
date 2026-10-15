package com.notdisk.core.model

/** Authorisation state of a bound account, as far as the client can tell. */
enum class AuthState {
    /** Credentials are present and a session check has not (yet) contradicted that. */
    AUTHORIZED,

    /** Credentials exist but the remote side rejected them; re-authorisation is required. */
    EXPIRED,

    /** Credentials were revoked locally (logout) or the user removed the account. */
    REVOKED,

    /** The provider uses an interactive flow that has been started but not completed. */
    PENDING_AUTHORIZATION,

    /** The provider could not be asked (offline). The previous state stands but is unverified. */
    UNKNOWN,
}

/**
 * Vendor account profile, normalised.
 *
 * Contains **no credentials**. Tokens/cookies live in the encrypted credential store, keyed by
 * ([providerId], [accountId]), and are never carried in this model.
 */
data class CloudAccount(
    val accountId: AccountId,
    val providerId: ProviderId,
    /** Vendor-side stable user id (e.g. 115's `user_id`). Diagnostic + de-duplication only. */
    val remoteUserId: String?,
    /** Human-readable account label shown in the UI (nickname, or a masked user id). */
    val displayName: String,
    /** Avatar URL if the vendor returns a non-signed, stable one. Signed avatars must be `null`. */
    val avatarUrl: String? = null,
    val authState: AuthState = AuthState.UNKNOWN,
    /** Vendor membership tier, normalised to a display string (e.g. "年费VIP"). `null` = unknown. */
    val membershipLabel: String? = null,
    /** Membership expiry, epoch millis. */
    val membershipExpiresAt: Long? = null,
    /** When this account was first bound locally, epoch millis. */
    val addedAt: Long = 0L,
    /** When the credential was last confirmed usable, epoch millis. */
    val lastVerifiedAt: Long? = null,
)

/**
 * A live, verified session handle passed into provider calls.
 *
 * Providers receive this instead of reading global state, which keeps them testable and makes
 * "which account is this call for?" explicit at every call site.
 *
 * Implementations are responsible for attaching the credential material. The domain layer only
 * needs [accountId] and [providerId]; it must not read the credential itself.
 */
interface AuthSession {
    val providerId: ProviderId
    val accountId: AccountId
    /** Epoch millis after which the credential is assumed invalid; `null` = no known expiry. */
    val expiresAt: Long?
    /**
     * True when [expiresAt] has passed (or is within [skewMillis]).
     * [nowMillis] is injectable so session-expiry handling is testable with a virtual clock.
     */
    fun isExpired(skewMillis: Long = 0L, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val expiry = expiresAt ?: return false
        return nowMillis + skewMillis >= expiry
    }
}

/** Storage usage reported by a vendor. */
data class StorageQuota(
    val totalBytes: Long?,
    val usedBytes: Long?,
    val remainingBytes: Long?,
    val totalLabel: String? = null,
    val usedLabel: String? = null,
    val remainingLabel: String? = null,
    /** Per-category breakdown, keyed by [FileCategory]. Vendors that report none leave it empty. */
    val categoryUsage: Map<FileCategory, CategoryUsage> = emptyMap(),
)

data class CategoryUsage(
    val itemCount: Long?,
    val bytes: Long?,
    val bytesLabel: String? = null,
    /** Vendor-reported percentage. Note: 115 misspells this field as `pecent` upstream. */
    val fraction: Double? = null,
)
