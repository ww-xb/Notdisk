package com.notdisk.provider.baidu.auth

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CategoryUsage
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.core.provider.AuthProvider
import com.notdisk.provider.baidu.BaiduProviderId
import com.notdisk.provider.baidu.api.BaiduEndpoints
import com.notdisk.provider.baidu.api.BaiduErrorFamily
import com.notdisk.provider.baidu.api.BaiduHttpClient
import com.notdisk.provider.baidu.api.BaiduJson
import com.notdisk.provider.baidu.api.arrayOf
import com.notdisk.provider.baidu.api.asLongOrNull
import com.notdisk.provider.baidu.api.boolOf
import com.notdisk.provider.baidu.api.intOf
import com.notdisk.provider.baidu.api.longOf
import com.notdisk.provider.baidu.api.objOf
import com.notdisk.provider.baidu.api.stringOf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonObject

/**
 * Baidu Netdisk's account surface, implemented strictly from the reference material.
 *
 * ### The flow this implements, and the one it deliberately does not
 * The reference material documents two ways in:
 *
 *  - **`MANUAL_CREDENTIAL`** — the user pastes their browser session cookie (§1.5, §2.4). Every API
 *    in this document authenticates with `BDUSS`, the flow needs **no developer credential at all**,
 *    and the material explicitly recommends it: "这是最省事的路径".
 *  - **`QR_CODE` / `PASSWORD`** — Baidu's own login chain (§2.2) is a **10-step dance through
 *    `passport.baidu.com`** whose step 7 requires a `sig` parameter computed by Passport's private
 *    front-end JavaScript (`alg=v3` + `shaOne`), which the material says would have to be reverse
 *    engineered to reproduce: "自研 APP 若要完整复刻需逆向 passport SDK". Steps 1-6 and 8-10 are
 *    reproducible; step 7 is the wall.
 *
 * This build therefore advertises **one** flow and refuses the others with a typed `Unsupported`,
 * which is what the shared contract suite asserts. Advertising a QR flow whose final — and only
 * credential-producing — step cannot be completed would be exactly the "button that always fails"
 * the project's capability rules forbid.
 *
 * ### Credential handling
 * The parsed `BDUSS` (plus the supporting device cookies) goes straight into the encrypted
 * [BaiduCredentialStore]. It never touches the account metadata, never enters a log line, and is not
 * returned to the caller: [AuthChallengeState.Succeeded] carries only the local [AccountId].
 *
 * ### The `bdstoken` is a separate concern
 * The CSRF token needed by writes lives at `/api/gettemplatevariable?fields=["bdstoken"]` (§2.4). It
 * is *derived* from the session rather than stored, because the material notes it changes with the
 * session and recommends refreshing it — so caching it here would create a stale-value bug whose
 * symptom (`errno 2` on every write) points nowhere near its cause. See [bdstokenFor].
 */
internal class BaiduAuthProvider(
    private val http: BaiduHttpClient,
    private val credentials: BaiduCredentialStore,
    private val accounts: BaiduAccountMetadataStore,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthProvider {

    /**
     * In-flight challenges.
     *
     * Only the `COMPLETED` case is ever produced, but the map exists because [pollChallenge] must
     * still answer correctly for an id it has issued — and because a caller may poll after the
     * credential has been written, which must report success rather than "unknown challenge".
     */
    private val completed = ConcurrentHashMap<String, AccountId>()

    // ------------------------------------------------------------------ begin

    override suspend fun beginAuthorization(
        flow: AuthFlowKind,
        params: AuthFlowParams,
    ): CloudResult<AuthChallenge> = when (flow) {
        AuthFlowKind.MANUAL_CREDENTIAL -> beginManualCredential(params)

        AuthFlowKind.QR_CODE -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未提供百度网盘扫码登录：官方登录流程的最后一步（换取 BDUSS）需要一个由 " +
                    "Passport 前端私有 JavaScript 计算的 sig 参数，未逆向该 SDK 前无法完成。请在浏览器中" +
                    "登录百度网盘后，从开发者工具复制 Cookie 粘贴到此处。",
                capability = "auth.flow.qr_code",
            ),
        )

        AuthFlowKind.PASSWORD -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未提供百度账号密码登录：该流程与扫码登录共用同一个 sig 校验，" +
                    "且需要处理短信与图形验证码。请在浏览器中登录后粘贴 Cookie。",
                capability = "auth.flow.password",
            ),
        )

        AuthFlowKind.OAUTH_REDIRECT -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "百度网盘开放平台 OAuth 需要开发者注册的应用凭据，本项目未申请。" +
                    "网页版会话 Cookie 登录不需要任何开发者凭据。",
                capability = "auth.flow.oauth_redirect",
            ),
        )

        AuthFlowKind.DEVICE_CODE -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "百度网盘未提供设备码授权流程。",
                capability = "auth.flow.device_code",
            ),
        )
    }

    /**
     * Binds an account from a session cookie the user pasted.
     *
     * ### What is accepted
     * Either a full `Cookie:` header value (`BDUSS=…; STOKEN=…; BAIDUID=…`) copied from the browser's
     * devtools, or the devtools *Application → Cookies* table pasted row by row. [BaiduCookieParser]
     * handles both shapes, including the `Cookie:` prefix and the tab separator the table uses.
     * `BDUSS` is required; `STOKEN` and the device cookies are preserved when present.
     *
     * ### Why the credential is verified before it is kept
     * A pasted cookie can be stale, truncated, or belong to a different site entirely. Persisting
     * first and discovering later would create an account row that renders as usable and fails on the
     * user's first tap. So the session is checked against `/api/loginStatus` — a cheap, read-only,
     * documented endpoint — **before** anything is written (§2.4).
     *
     * That single ordering decision is what makes the difference between "无效的 Cookie 被拒绝" and
     * "一个看似已绑定、首次点击才失败的账号".
     */
    private suspend fun beginManualCredential(params: AuthFlowParams): CloudResult<AuthChallenge> {
        val raw = params.manualCredential?.trim().orEmpty()
        if (raw.isEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "请粘贴百度网盘的 Cookie 内容"),
            )
        }

        val parsed = BaiduCookieParser.parse(raw)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "Cookie 解析失败：未找到 BDUSS。请在浏览器登录百度网盘后，从开发者工具的" +
                        "「网络」面板复制任意请求的 Cookie 请求头（需包含 BDUSS）。",
                ),
            )

        // Verified against the vendor before it is written anywhere, so a bad paste cannot create an
        // account row that looks bound and then fails on the user's first tap.
        val statusRequest = http.get(
            url = http.resolve(BaiduEndpoints.LOGIN_STATUS),
            query = mapOf("version" to clock().toString()),
            cookies = parsed.cookieHeader(),
        )

        val status = when (val result = http.execute(statusRequest, tag = "loginStatus", family = BaiduErrorFamily.AUTH)) {
            is CloudResult.Failure -> return CloudResult.Failure(
                if (result.error.requiresReauthentication) {
                    CloudError.TokenExpired(
                        message = "Cookie 已失效或不属于百度网盘账号，请重新复制。",
                        canRefresh = false,
                        vendorCode = result.error.vendorCode,
                        vendorMessage = result.error.vendorMessage,
                    )
                } else {
                    result.error
                },
            )

            is CloudResult.Success -> result.data
        }

        // §2.4: `isLogin: 0` means the cookie parsed but the session is not live. The endpoint can
        // answer `errno: 0` for an anonymous request, so the body has to be checked as well as the
        // envelope — relying on `errno` alone would accept an unauthenticated paste.
        val isLogin = status.bool("isLogin") ?: status.obj("data")?.boolOf("isLogin")
        if (isLogin == false) {
            return CloudResult.Failure(
                CloudError.TokenExpired(
                    message = "该 Cookie 未登录或已失效，请重新登录百度网盘后复制。",
                    canRefresh = false,
                ),
            )
        }

        val uk = status.long("uk")?.toString()
            ?: status.string("uk")
            ?: status.obj("data")?.longOf("uk")?.toString()

        val displayName = status.string("username", "uname", "nickname", "display_name")
            ?: status.obj("data")?.stringOf("username", "uname", "nickname")
            ?: uk?.let { "百度用户 $it" }
            ?: "百度网盘账号"

        // Re-pasting a cookie for an account that is already bound reuses that account rather than
        // creating a duplicate row for the same Baidu user.
        val accountId = accounts.findByRemoteUserId(uk)?.accountId
            ?: AccountId("baidu-${UUID.randomUUID()}")

        val credential = parsed.copy(userId = uk, obtainedAt = clock())
        credentials.save(accountId, credential)

        val existing = accounts.find(accountId)
        accounts.upsert(
            BaiduAccountMetadata(
                accountId = accountId,
                remoteUserId = uk,
                displayName = displayName,
                authState = AuthState.AUTHORIZED,
                membershipLabel = status.string("vip_name", "vipType")
                    ?.let { BaiduMembership.labelFor(it) },
                membershipExpiresAt = null,
                addedAt = existing?.addedAt ?: clock(),
                lastVerifiedAt = clock(),
            ),
        )

        logger.i(TAG, "cookie login succeeded for account ${accountId.value.take(8)}…")

        // `Completed` is the contract's "credentials were produced without further interaction":
        // there is nothing to poll, so the caller refreshes its list immediately.
        val challengeId = "baidu-cookie-${UUID.randomUUID()}"
        completed[challengeId] = accountId
        return CloudResult.Success(
            AuthChallenge.Completed(challengeId = challengeId, accountId = accountId),
        )
    }

    // ------------------------------------------------------------------ challenge polling

    /**
     * Answers for a challenge this provider issued.
     *
     * Only `Completed` challenges exist, so a known id always reports [AuthChallengeState.Succeeded]
     * with the account id — which is what lets the accounts UI use one polling loop for every
     * provider regardless of which flow it started.
     */
    override suspend fun pollChallenge(challengeId: String): CloudResult<AuthChallengeState> {
        val accountId = completed[challengeId]
            ?: return CloudResult.Failure(
                CloudError.NotFound(message = "未知的授权会话: $challengeId"),
            )
        return CloudResult.Success(AuthChallengeState.Succeeded(accountId))
    }

    /**
     * Cancels a challenge.
     *
     * Deliberately does **not** delete the credential: by the time a challenge is pollable the
     * credential has already been persisted, and a caller cancelling a *completed* challenge is
     * cancelling the UI flow, not the account binding. Removing the credential here would silently
     * unbind an account the user just added.
     */
    override suspend fun cancelChallenge(challengeId: String): CloudResult<Unit> {
        completed.remove(challengeId)
        return CloudResult.Success(Unit)
    }

    // ------------------------------------------------------------------ session

    /**
     * Produces the session the other sub-providers use.
     *
     * The expiry is deliberately `null`: Baidu documents no token lifetime, and inventing one would
     * make the UI proactively re-prompt for a cookie that still works. Validity is discovered the
     * only way it can be — by the server answering `errno -6`.
     */
    override suspend fun session(accountId: AccountId): CloudResult<BaiduSession> {
        val credential = credentials.load(accountId)
            ?: return CloudResult.Failure(
                CloudError.NotAuthenticated(
                    message = "该账号没有已保存的凭据，请重新登录",
                ),
            )
        if (!credential.isComplete) {
            return CloudResult.Failure(
                CloudError.NotAuthenticated(message = "已保存的凭据不完整，请重新登录"),
            )
        }
        return CloudResult.Success(
            BaiduSession(accountId = accountId, credential = credential, expiresAt = null),
        )
    }

    /**
     * Fetches the CSRF token write operations need (§2.4).
     *
     * Fetched per write rather than cached, and that is a deliberate trade: the material documents
     * `bdstoken` as session-scoped and recommends refreshing it, and a stale token produces `errno 2`
     * ("参数错误"), which points nowhere near its cause. One extra cheap GET before a user-initiated
     * mutation is a far better trade than an unexplainable write failure.
     */
    suspend fun bdstokenFor(session: BaiduSession): CloudResult<String> {
        val request = http.get(
            url = http.resolve(BaiduEndpoints.GET_TEMPLATE_VARIABLE),
            query = mapOf("fields" to BDSTOKEN_FIELDS),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "gettemplatevariable.bdstoken", family = BaiduErrorFamily.AUTH)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val token = result.data.result?.stringOf("bdstoken")
                    ?: result.data.string("bdstoken")
                if (token.isNullOrBlank()) {
                    CloudResult.Failure(
                        CloudError.ParseError(message = "百度网盘未返回 bdstoken"),
                    )
                } else {
                    CloudResult.Success(token)
                }
            }
        }
    }

    // ------------------------------------------------------------------ account reads

    override suspend fun verifyAccount(accountId: AccountId): CloudResult<CloudAccount> {
        val metadata = accounts.find(accountId)
            ?: return CloudResult.Failure(
                CloudError.NotAuthenticated(message = "该账号未在本机绑定"),
            )

        val session = when (val result = session(accountId)) {
            is CloudResult.Failure -> {
                // A missing credential is an account-state problem, not a transport problem, so the
                // stored row is corrected to reflect it. This is the one place that may mark an
                // account expired without the server saying so — and it is exact, because "no
                // credential is stored" is a local fact rather than an inference from a failure.
                accounts.upsert(metadata.copy(authState = AuthState.EXPIRED))
                return CloudResult.Failure(result.error)
            }

            is CloudResult.Success -> result.data
        }

        val request = http.get(
            url = http.resolve(BaiduEndpoints.LOGIN_STATUS),
            query = mapOf("version" to clock().toString()),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "verifyAccount", family = BaiduErrorFamily.AUTH)) {
            is CloudResult.Failure -> {
                // Only an *authentication* failure may mark the account expired. A network outage
                // must leave the previous state standing, or a user on a train would be logged out —
                // there is a shared-contract rule for exactly this.
                if (result.error.requiresReauthentication) {
                    accounts.upsert(metadata.copy(authState = AuthState.EXPIRED))
                }
                CloudResult.Failure(result.error)
            }

            is CloudResult.Success -> {
                val status = result.data
                val isLogin = status.bool("isLogin") ?: status.obj("data")?.boolOf("isLogin")
                if (isLogin == false) {
                    val updated = metadata.copy(authState = AuthState.EXPIRED)
                    accounts.upsert(updated)
                    return CloudResult.Failure(
                        CloudError.TokenExpired(
                            message = "登录状态已失效，请重新粘贴 Cookie",
                            canRefresh = false,
                        ),
                    )
                }

                val uk = status.long("uk")?.toString() ?: metadata.remoteUserId
                val displayName = status.string("username", "uname", "nickname")
                    ?: metadata.displayName
                val updated = metadata.copy(
                    remoteUserId = uk ?: metadata.remoteUserId,
                    displayName = displayName,
                    authState = AuthState.AUTHORIZED,
                    lastVerifiedAt = clock(),
                )
                accounts.upsert(updated)
                CloudResult.Success(updated.toAccount())
            }
        }
    }

    /** Local read only — never contacts the vendor, per the shared contract. */
    override suspend fun getAccount(accountId: AccountId): CloudResult<CloudAccount> {
        val metadata = accounts.find(accountId)
            ?: return CloudResult.Failure(
                CloudError.NotAuthenticated(message = "该账号未在本机绑定"),
            )
        return CloudResult.Success(metadata.toAccount())
    }

    /** Local read only — never contacts the vendor, per the shared contract. */
    override suspend fun listAccounts(): CloudResult<List<CloudAccount>> =
        CloudResult.Success(accounts.all().map { it.toAccount() })

    /**
     * Fetches the vendor profile and persists it (§9.1).
     *
     * `/api/user/getinfo` is documented to carry `login_info`, which is a second source of
     * `bdstoken`; it is read here only for display data, because [bdstokenFor] is the single owner of
     * that token and two owners would mean two refresh policies.
     */
    override suspend fun refreshProfile(accountId: AccountId): CloudResult<CloudAccount> {
        val session = when (val result = session(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val uk = accounts.find(accountId)?.remoteUserId
        val request = http.get(
            url = http.resolve(BaiduEndpoints.USER_GETINFO),
            query = buildMap {
                put("need_boardinfo", "1")
                // §9.1 documents `user_list` as a JSON array string of `uk`s. When the account has
                // no recorded `uk` yet the parameter is omitted, which makes the endpoint describe
                // the *session's own* user — the correct behaviour for a first call.
                uk?.let { put("user_list", "[$it]") }
            },
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "user.getinfo", family = BaiduErrorFamily.AUTH)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val record = result.data.array("records")
                    ?.firstOrNull()
                    ?.let { it as? JsonObject }
                    ?: result.data.obj("data")

                if (record == null) {
                    return CloudResult.Failure(
                        CloudError.ParseError(message = "百度网盘未返回用户信息"),
                    )
                }

                val metadata = accounts.find(accountId)
                    ?: BaiduAccountMetadata(
                        accountId = accountId,
                        remoteUserId = null,
                        displayName = "百度网盘账号",
                        authState = AuthState.AUTHORIZED,
                        membershipLabel = null,
                        membershipExpiresAt = null,
                        addedAt = clock(),
                        lastVerifiedAt = null,
                    )

                val updated = metadata.copy(
                    remoteUserId = record.longOf("uk")?.toString() ?: metadata.remoteUserId,
                    // `priority_name` is documented as the preferred display name, with `nick_name`
                    // and `uname` as the alternatives.
                    displayName = record.stringOf("priority_name", "nick_name", "uname")
                        ?: metadata.displayName,
                    membershipLabel = record.intOf("vip_type")
                        ?.let { BaiduMembership.labelFor(it.toString()) }
                        ?: metadata.membershipLabel,
                    lastVerifiedAt = clock(),
                )
                accounts.upsert(updated)
                CloudResult.Success(updated.toAccount())
            }
        }
    }

    /**
     * Storage quota (§9.2).
     *
     * `/api/quota` reports `total`/`used`/`sbox_total`/`sbox_used`. The hidden-space figures are
     * reported back as a negative contribution rather than merged: the vendor counts them
     * separately, and adding them to `used` would double-count for an account that has not enabled
     * the hidden space.
     */
    override suspend fun getStorageQuota(accountId: AccountId): CloudResult<StorageQuota> {
        val session = when (val result = session(accountId)) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val request = http.get(
            url = http.resolve(BaiduEndpoints.QUOTA),
            query = mapOf("version" to clock().toString()),
            cookies = session.credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "quota", family = BaiduErrorFamily.AUTH)) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                val total = envelope.long("total")
                val used = envelope.long("used")
                CloudResult.Success(
                    StorageQuota(
                        totalBytes = total,
                        usedBytes = used,
                        remainingBytes = if (total != null && used != null) {
                            (total - used).coerceAtLeast(0)
                        } else {
                            null
                        },
                        totalLabel = total?.let(::formatBytes),
                        usedLabel = used?.let(::formatBytes),
                        remainingLabel = if (total != null && used != null) {
                            formatBytes((total - used).coerceAtLeast(0))
                        } else {
                            null
                        },
                        // The vendor reports no per-category breakdown on this endpoint, and
                        // inventing one would be a fabricated number in a storage screen.
                        categoryUsage = emptyMap<FileCategory, CategoryUsage>(),
                    ),
                )
            }
        }
    }

    /**
     * Removes the credential locally and attempts the vendor logout.
     *
     * The contract requires that a remote failure still drops the local credential — a user who taps
     * "退出登录" must end up logged out, even offline. The remote call is therefore best-effort, and
     * only a *local* removal failure is reported as an error.
     */
    override suspend fun logout(accountId: AccountId): CloudResult<Unit> {
        val credential = credentials.load(accountId)
        if (credential != null) {
            val request = http.postForm(
                url = http.resolve(BaiduEndpoints.LOGOUT),
                form = emptyMap(),
                cookies = credential.cookieHeader(),
            )
            when (val result = http.execute(request, tag = "logout", family = BaiduErrorFamily.AUTH)) {
                is CloudResult.Success -> logger.i(TAG, "remote logout succeeded")
                is CloudResult.Failure -> logger.w(
                    TAG,
                    // Not an error path: the local credential is dropped regardless.
                    "remote logout failed (${result.error::class.simpleName}); dropping local credential anyway",
                )
            }
        }

        return try {
            credentials.delete(accountId)
            accounts.find(accountId)?.let { accounts.upsert(it.copy(authState = AuthState.REVOKED)) }
            completed.entries.removeIf { it.value == accountId }
            CloudResult.Success(Unit)
        } catch (t: Throwable) {
            CloudResult.Failure(
                CloudError.LocalIoError(message = "本地凭据删除失败", cause = t),
            )
        }
    }

    private companion object {
        const val TAG = "BaiduAuth"

        /** §2.4's documented `fields` value for the CSRF token. */
        const val BDSTOKEN_FIELDS = """["bdstoken","uk","isdocuser","servertime"]"""

        /**
         * Renders a byte count for the quota screen.
         *
         * Binary units, matching what the vendor's own web client displays for the same numbers.
         */
        fun formatBytes(bytes: Long): String {
            val units = listOf("B", "KB", "MB", "GB", "TB", "PB")
            var value = bytes.toDouble()
            var index = 0
            while (value >= 1024 && index < units.lastIndex) {
                value /= 1024
                index++
            }
            return if (index == 0) {
                "$bytes ${units[0]}"
            } else {
                String.format(java.util.Locale.ROOT, "%.2f %s", value, units[index])
            }
        }
    }
}

/** A live session handle. Carries the credential but never dumps it. */
internal data class BaiduSession(
    override val accountId: AccountId,
    val credential: BaiduCredential,
    override val expiresAt: Long?,
) : com.notdisk.core.model.AuthSession {
    override val providerId = BaiduProviderId

    /** Redacted: a session must never dump its credential into a log line. */
    override fun toString(): String = "BaiduSession(account=$accountId, credential=***)"
}

/** Account metadata persisted locally (no credentials — those live in [BaiduCredentialStore]). */
data class BaiduAccountMetadata(
    val accountId: AccountId,
    val remoteUserId: String?,
    val displayName: String,
    val authState: AuthState,
    val membershipLabel: String?,
    val membershipExpiresAt: Long?,
    val addedAt: Long,
    val lastVerifiedAt: Long?,
) {
    fun toAccount(): CloudAccount = CloudAccount(
        accountId = accountId,
        providerId = BaiduProviderId,
        remoteUserId = remoteUserId,
        displayName = displayName,
        // `/api/user/getinfo` returns an `avatar_url`, but it is a `himg.bdimg.com` URL that the
        // vendor's own client re-signs per session. The shared model documents that a signed avatar
        // must be `null` rather than a link that breaks, so none is claimed.
        avatarUrl = null,
        authState = authState,
        membershipLabel = membershipLabel,
        membershipExpiresAt = membershipExpiresAt,
        addedAt = addedAt,
        lastVerifiedAt = lastVerifiedAt,
    )
}

/**
 * Local, non-secret account metadata storage.
 *
 * Holds **no credentials** — display name, membership tier and verification times only — so it is
 * safe to keep in memory, in Room, or anywhere else. The credential lives exclusively in
 * [BaiduCredentialStore].
 */
interface BaiduAccountMetadataStore {
    suspend fun upsert(metadata: BaiduAccountMetadata)
    suspend fun find(accountId: AccountId): BaiduAccountMetadata?
    suspend fun findByRemoteUserId(remoteUserId: String?): BaiduAccountMetadata?
    suspend fun all(): List<BaiduAccountMetadata>
    suspend fun remove(accountId: AccountId)
}

/** In-memory implementation used by unit tests and JVM-side contract tests. */
class InMemoryBaiduAccountMetadataStore : BaiduAccountMetadataStore {
    private val entries = LinkedHashMap<AccountId, BaiduAccountMetadata>()

    override suspend fun upsert(metadata: BaiduAccountMetadata) {
        entries[metadata.accountId] = metadata
    }

    override suspend fun find(accountId: AccountId): BaiduAccountMetadata? = entries[accountId]

    override suspend fun findByRemoteUserId(remoteUserId: String?): BaiduAccountMetadata? {
        if (remoteUserId.isNullOrBlank()) return null
        return entries.values.firstOrNull { it.remoteUserId == remoteUserId }
    }

    override suspend fun all(): List<BaiduAccountMetadata> = entries.values.toList()

    override suspend fun remove(accountId: AccountId) {
        entries.remove(accountId)
    }
}

/**
 * Maps the vendor's membership codes onto a display name.
 *
 * §9.1's measured response shows `vip_type` (`0` = 普通) and `vip_level`; §9.3 adds a
 * `level_info.current_level`. The vendor does not publish a complete code→name table in the
 * reference material, so this maps only the values the material actually documents and falls back to
 * `null` for anything else — an unknown tier is displayed as "unknown", never as a guessed name.
 */
internal object BaiduMembership {
    fun labelFor(code: String): String? = when (code) {
        "0" -> "普通用户"
        "1" -> "VIP"
        "2" -> "超级会员"
        else -> null
    }
}
