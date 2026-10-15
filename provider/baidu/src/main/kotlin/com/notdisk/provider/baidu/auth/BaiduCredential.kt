package com.notdisk.provider.baidu.auth

import com.notdisk.core.model.AccountId

/**
 * The credential material for one Baidu Netdisk account.
 *
 * ### Why this is structurally different from the 115 credential
 * 115 delivers its session as four separate tokens (`UID`/`CID`/`SEID`/`KID`) inside a JSON body.
 * Baidu delivers **one** cookie that matters — `BDUSS` — plus a set of supporting device cookies and
 * a separate CSRF token (`bdstoken`) that the vendor documents as a *query parameter* rather than a
 * cookie (§13.2 item 6).
 *
 * The reference material is explicit about which cookie is load-bearing (§1.5): `BDUSS` is the
 * "登录凭证主令牌", and it appears in every documented request that needs authentication. The
 * others are device/session identifiers that the vendor's own client sends and that this client
 * therefore preserves verbatim, but that are not individually required to be correct.
 *
 * So the model is: **keep the whole cookie jar, but know which one is the credential.** That choice
 * matters because Baidu's risk control observes the full cookie set — dropping `BAIDUID` or
 * `BIDUPSID` would make an otherwise-valid session look like a new device, and the vendor's own
 * material records an `ndut_fmt` risk fingerprint cookie for exactly that reason.
 *
 * ### Security
 * This object is a credential. It must only ever live:
 *  - in memory for the duration of a session, and
 *  - in encrypted storage at rest ([BaiduCredentialStore]).
 *
 * It must never be logged, never be written to Room as a plain column, and never be included in a
 * `CloudAccount` (which is a *display* model). [toString] is overridden so that an accidental
 * interpolation into a log line is harmless.
 */
data class BaiduCredential(
    /**
     * `BDUSS` — the primary login token (§1.5). Issued by `passport.baidu.com` and scoped to
     * `.baidu.com`, which is why it authenticates both `pan.baidu.com` and the `pcs` hosts.
     *
     * This is the one field whose absence makes every API call fail with `errno -6`.
     */
    val bduss: String,
    /**
     * `STOKEN` — the "安全令牌" used by the pcs hosts (§1.5).
     *
     * Optional because it is not required for the `/api/…` surface this build drives; it is kept
     * because a user pasting a full cookie header will have it, and discarding a credential the
     * vendor issued would be a silent downgrade.
     */
    val stoken: String? = null,
    /**
     * The remaining cookies, preserved verbatim.
     *
     * Deliberately an ordered list of pairs rather than a `Map`, because a cookie header is ordered
     * and because two cookies with the same name on different paths must both survive. The vendor
     * documents `BAIDUID`/`BAIDUID_BFESS`/`BIDUPSID`/`PSTM`/`H_PS_PSSID`/`ndut_fmt` as meaningful
     * (§1.5), and a risk-control system that fingerprints the device is not something to
     * second-guess by pruning its inputs.
     */
    val extraCookies: List<Pair<String, String>> = emptyList(),
    /**
     * The local `uk` (Baidu user id) when known.
     *
     * Used for display and for building the `fid={uk}-250528-{fs_id}` segment the vendor's own
     * thumbnail URLs use (§8.2). It is **not** an authentication input: `uk` is public, appears in
     * every share link, and cannot authenticate anything on its own.
     */
    val userId: String? = null,
    /** When this credential was obtained, epoch millis. Diagnostics and rotation hints only. */
    val obtainedAt: Long,
) {
    /**
     * Renders the cookie header exactly as the vendor's own client would.
     *
     * `BDUSS` is emitted first so a captured request is byte-comparable during debugging, and blank
     * values are skipped rather than sent as `NAME=` — a header carrying an empty credential is a
     * *malformed* session that the vendor may reject outright, turning a working credential into a
     * failed one.
     */
    fun cookieHeader(): String = buildString {
        appendCookie(BDUSS, bduss)
        appendCookie(STOKEN, stoken)
        extraCookies.forEach { (name, value) ->
            // `BDUSS`/`STOKEN` may also appear in `extraCookies` when the user pasted a full header;
            // emitting them twice would be at best redundant and at worst a malformed header, so the
            // dedicated fields always win.
            if (!name.equals(BDUSS, ignoreCase = true) && !name.equals(STOKEN, ignoreCase = true)) {
                appendCookie(name, value)
            }
        }
    }

    /** Appends `[; ]NAME=value`, skipping a blank value and the separator it would need. */
    private fun StringBuilder.appendCookie(name: String, value: String?) {
        if (value.isNullOrBlank()) return
        if (isNotEmpty()) append("; ")
        append(name).append('=').append(value)
    }

    /**
     * True when the credential is structurally usable.
     *
     * Deliberately checks presence, not validity: only the server can say whether a session is still
     * alive (`/api/loginStatus`). A structurally-complete-but-expired cookie is exactly the case that
     * must produce a `TokenExpired` from a *server* call rather than a local "not logged in" guess —
     * the difference is that the first tells the user their session ended, while the second would
     * claim they never signed in.
     */
    val isComplete: Boolean get() = bduss.isNotBlank()

    /** Redacted by design: interpolating a credential into a string must never leak it. */
    override fun toString(): String =
        "BaiduCredential(userId=$userId, bduss=***, cookies=${extraCookies.size})"

    companion object {
        const val BDUSS = "BDUSS"
        const val STOKEN = "STOKEN"
    }
}

/**
 * Encrypted-at-rest storage for [BaiduCredential]s, keyed by the *local* [AccountId].
 *
 * ### Why keyed by the local account id
 * The local id is generated by the app. Keying by `uk` instead would lose every cached association
 * when a user re-pastes a cookie, and would make it impossible to bind two accounts that share a
 * vendor id.
 *
 * ### Implementation contract
 * Implementations must use Android's encrypted storage (see [BaiduEncryptedCredentialStore]). A
 * plain `SharedPreferences` or a Room column is **not** acceptable: a `BDUSS` is a long-lived bearer
 * secret that grants full access to the user's files, and `docs/security.md` forbids it.
 */
interface BaiduCredentialStore {

    suspend fun save(accountId: AccountId, credential: BaiduCredential)

    suspend fun load(accountId: AccountId): BaiduCredential?

    suspend fun delete(accountId: AccountId)

    /** Account ids with a stored credential. Never returns the credentials themselves. */
    suspend fun listAccountIds(): List<AccountId>
}

/** In-memory store for tests and for a build without Keystore access. Holds no data at rest. */
class InMemoryBaiduCredentialStore : BaiduCredentialStore {
    private val entries = LinkedHashMap<AccountId, BaiduCredential>()

    override suspend fun save(accountId: AccountId, credential: BaiduCredential) {
        entries[accountId] = credential
    }

    override suspend fun load(accountId: AccountId): BaiduCredential? = entries[accountId]

    override suspend fun delete(accountId: AccountId) {
        entries.remove(accountId)
    }

    override suspend fun listAccountIds(): List<AccountId> = entries.keys.toList()
}
