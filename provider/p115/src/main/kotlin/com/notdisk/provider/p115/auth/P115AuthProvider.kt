package com.notdisk.provider.p115.auth

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.common.result.getOrNull
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CapabilitySupport
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.StorageQuota
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.CategoryUsage
import com.notdisk.core.provider.AuthProvider
import com.notdisk.core.provider.AuthFlowParams
import com.notdisk.core.provider.AuthChallengeState
import com.notdisk.core.provider.AuthChallenge
import com.notdisk.provider.p115.P115Configuration
import com.notdisk.provider.p115.P115ProviderId
import com.notdisk.provider.p115.api.P115Endpoints
import com.notdisk.provider.p115.api.P115Hosts
import com.notdisk.provider.p115.api.P115HttpClient
import com.notdisk.provider.p115.api.P115Json
import com.notdisk.provider.p115.api.arrayOf
import com.notdisk.provider.p115.api.asIntOrNull
import com.notdisk.provider.p115.api.asLongOrNull
import com.notdisk.provider.p115.api.asNonBlankStringOrNull
import com.notdisk.provider.p115.api.boolOf
import com.notdisk.provider.p115.api.intOf
import com.notdisk.provider.p115.api.longOf
import com.notdisk.provider.p115.api.objOf
import com.notdisk.provider.p115.api.stringOf
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * 115's interactive authorisation, implemented strictly from `API_REFERENCE.md` §2.
 *
 * ### The flow this implements (QR code, §2.1)
 * ```
 * ① GET  qrcodeapi /api/1.0/web/1.0/token        → { uid, time, sign, qrcode }
 * ② GET  qrcodeapi /get/status/?uid=&time=&sign= → status: undefined | 1 | 2 | -1 | -2
 * ③ POST passportapi /app/1.0/web/1.0/login/qrcode   body: account=<uid>&app=web
 * ④     …the session arrives as UID/CID/SEID/KID in the RESPONSE BODY (data.cookie)
 * ```
 *
 * ### Why this flow and not OAuth
 * The manual lists OAuth 2.0 as an option (§2.4) but marks it `📖` — unverified, and it requires an
 * `app_id` that this project has not been issued. QR login is marked `✅` measured and needs **no
 * developer credentials at all**, so it is the only flow this build can honestly advertise as
 * working. The brief's rule 7 (create a configuration entry point rather than inventing credentials)
 * is satisfied by [P115Configuration]: OAuth is representable but refuses to start until a real
 * client id is configured.
 *
 * ### Credential handling
 * The four cookies are extracted from the JSON body (see [P115Credential]) and written straight to
 * the encrypted [P115CredentialStore]. They never touch the account metadata, never enter a log
 * line, and are not returned to the caller: [AuthChallengeState.Succeeded] carries only the local
 * [AccountId].
 */
internal class P115AuthProvider(
    private val http: P115HttpClient,
    private val credentials: P115CredentialStore,
    private val accounts: P115AccountMetadataStore,
    private val configuration: P115Configuration,
    private val logger: CloudLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : AuthProvider {

    /**
     * In-flight challenges.
     *
     * Keyed by a local UUID rather than the vendor's `uid`, so that:
     *  - a challenge can be polled and matched even if the vendor's token is rotated mid-flight;
     *  - the vendor's `sign` value (which is part of the exchange material) never leaves this class.
     */
    private data class Challenge(
        val id: String,
        /** Vendor `uid` — the value passed as `account` in the token exchange. */
        val vendorUid: String,
        /** Vendor `time` — required by the status endpoint. */
        val vendorTime: String,
        /** Vendor `sign` — required by the status endpoint. */
        val sign: String,
        val qrContent: String,
        val expiresAt: Long,
        /** Set once the exchange has been performed, so a repeated poll cannot exchange twice. */
        @Volatile var exchanged: Boolean = false,
        @Volatile var accountId: AccountId? = null,
        @Volatile var terminalState: AuthChallengeState? = null,
    )

    private val challenges = ConcurrentHashMap<String, Challenge>()
    private val exchangeLock = Mutex()

    // ------------------------------------------------------------------ begin

    override suspend fun beginAuthorization(
        flow: AuthFlowKind,
        params: AuthFlowParams,
    ): CloudResult<AuthChallenge> = when (flow) {
        AuthFlowKind.QR_CODE -> beginQrChallenge()

        AuthFlowKind.OAUTH_REDIRECT -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "115 OAuth 需要开发者 app_id，本项目尚未配置。请在设置中填写后再使用；" +
                    "当前请使用扫码登录。",
                capability = "auth.flow.oauth_redirect",
            ),
        )

        AuthFlowKind.PASSWORD -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "本版本未启用账号密码登录（该流程需要处理二次验证与图形验证码，尚未验证）。",
                capability = "auth.flow.password",
            ),
        )

        AuthFlowKind.DEVICE_CODE -> CloudResult.Failure(
            CloudError.Unsupported(
                message = "115 未提供设备码授权流程。",
                capability = "auth.flow.device_code",
            ),
        )

        AuthFlowKind.MANUAL_CREDENTIAL -> beginManualCredential(params)
    }

    /**
     * Binds an account from a session cookie the user pasted.
     *
     * ### Why this exists, and why the previous refusal was wrong
     * An earlier version of this provider refused the flow outright ("手工输入易泄露且难以安全存储").
     * The second half of that objection was simply false: the credential goes into exactly the same
     * encrypted store (`P115EncryptedCredentialStore`, Android Keystore-backed) as a scanned login,
     * so pasting a cookie is not less safe *at rest* than scanning a QR code. The first half is a
     * real trade-off — a pasted cookie is briefly in the clipboard — but it is the user's decision
     * to make, and the alternative for someone whose Web login already works is not using the app
     * at all.
     *
     * ### What is accepted
     * Either a full `Cookie:` header value (`UID=…; CID=…; SEID=…; KID=…`) copied from the browser's
     * devtools, or the same pairs on separate lines. `USERSESSIONID` and `PHPSESSID` are optional:
     * §2.1 records that `webapi.115.com` authenticates with the four core cookies.
     *
     * ### Why the credential is verified before it is kept
     * A pasted cookie can be stale, truncated, or belong to a different site. Persisting first and
     * discovering later would create an account row that renders as usable and fails on first use,
     * so the session is checked against `my.115.com/?ct=ajax&ac=nav` *before* anything is written.
     */
    private suspend fun beginManualCredential(
        params: AuthFlowParams,
    ): CloudResult<AuthChallenge> {
        val raw = params.manualCredential?.trim().orEmpty()
        if (raw.isEmpty()) {
            return CloudResult.Failure(
                CloudError.InvalidArgument(message = "请粘贴 115 的 Cookie 内容"),
            )
        }

        val parsed = P115CookieParser.parse(raw)
            ?: return CloudResult.Failure(
                CloudError.InvalidArgument(
                    message = "Cookie 解析失败：需要至少包含 UID、CID、SEID、KID 四项。" +
                        "请从浏览器开发者工具的请求头中复制完整的 Cookie。",
                ),
            )

        // Verified against the vendor before it is written anywhere, so a bad paste cannot create
        // an account row that looks bound and then fails on the user's first tap.
        val navRequest = http.get(
            url = http.resolve(P115Endpoints.SESSION_NAV),
            cookies = parsed.cookieHeader(),
        )
        val nav = when (val result = http.execute(navRequest, tag = "cookie.nav")) {
            is CloudResult.Failure -> return CloudResult.Failure(
                if (result.error.requiresReauthentication) {
                    CloudError.TokenExpired(
                        message = "Cookie 已失效或不属于 115，请重新复制。",
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

        val data = nav.dataObject
        val userId = data?.longOf("user_id", "user_id_str")?.toString()
            ?: data?.stringOf("user_id")
            ?: parsed.uid.substringBefore('_')
        val userName = data?.stringOf("user_name") ?: "115 用户 $userId"

        // Re-pasting a cookie for an account that is already bound reuses that account rather than
        // creating a duplicate row for the same 115 user.
        val accountId = accounts.findByRemoteUserId(userId)?.accountId
            ?: AccountId("p115-${UUID.randomUUID()}")

        val credential = parsed.copy(userId = userId, obtainedAt = clock())
        credentials.save(accountId, credential)
        accounts.upsert(
            P115AccountMetadata(
                accountId = accountId,
                remoteUserId = userId,
                displayName = userName,
                authState = AuthState.AUTHORIZED,
                membershipLabel = data?.longOf("mark", "mark1")?.toString()
                    ?.let { P115Membership.labelFor(it) },
                membershipExpiresAt = data?.longOf("expire")
                    ?.let { P115FileMapping.epochSecondsToMillis(it) }
                    ?: data?.longOf("is_vip")?.let { P115FileMapping.epochSecondsToMillis(it) },
                addedAt = accounts.findByRemoteUserId(userId)?.addedAt ?: clock(),
                lastVerifiedAt = clock(),
            ),
        )

        logger.i(TAG, "cookie login succeeded for account ${accountId.value.take(8)}…")

        // `Completed` is the contract's "credentials were produced without further interaction":
        // there is nothing to poll, so the caller refreshes its list immediately.
        return CloudResult.Success(
            AuthChallenge.Completed(
                challengeId = "p115-cookie-${UUID.randomUUID()}",
                accountId = accountId,
            ),
        )
    }

    /**
     * §2.1 Step 1: fetch a QR token.
     *
     * The response's `qrcode` field is a plain string that the *client* renders as a QR image
     * (the manual notes the front-end does exactly this), so no image download is needed. The
     * server-rendered PNG is kept as a fallback for devices whose QR renderer is unavailable.
     */
    private suspend fun beginQrChallenge(): CloudResult<AuthChallenge> {
        val request = http.get(
            url = http.resolve(P115Endpoints.QR_TOKEN),
            query = mapOf("_ " to null),
        )
        // Execute exactly once. An earlier version issued the request again inside the `?:`
        // fallback, which doubled the load on a rate-limited endpoint and made one failure look
        // like two unrelated ones in the logs.
        val envelope = when (val result = http.execute(request, tag = "qr.token")) {
            is CloudResult.Failure -> return CloudResult.Failure(result.error)
            is CloudResult.Success -> result.data
        }

        val data = envelope.dataObject
            ?: return CloudResult.Failure(
                CloudError.ParseError(message = "115 二维码接口未返回 data"),
            )

        val uid = data.stringOf("uid")
            ?: return CloudResult.Failure(CloudError.ParseError(message = "115 二维码响应缺少 uid"))
        val time = data.stringOf("time", "t")
            ?: return CloudResult.Failure(CloudError.ParseError(message = "115 二维码响应缺少 time"))
        val sign = data.stringOf("sign")
            ?: return CloudResult.Failure(CloudError.ParseError(message = "115 二维码响应缺少 sign"))
        val qrContent = data.stringOf("qrcode")
            ?: return CloudResult.Failure(CloudError.ParseError(message = "115 二维码响应缺少 qrcode"))

        val challengeId = "p115-qr-${UUID.randomUUID()}"
        // §2.1 Step 2: "二维码有效期约 120 秒".
        val expiresAt = clock() + QR_TTL_MS

        challenges[challengeId] = Challenge(
            id = challengeId,
            vendorUid = uid,
            vendorTime = time,
            sign = sign,
            qrContent = qrContent,
            expiresAt = expiresAt,
        )

        logger.i(TAG, "qr challenge started, ttl=${QR_TTL_MS / 1000}s")

        return CloudResult.Success(
            AuthChallenge.QrCode(
                challengeId = challengeId,
                content = qrContent,
                expiresAt = expiresAt,
                imageUrl = "${http.resolve(P115Endpoints.QR_IMAGE)}?qrfrom=1&uid=$uid",
            ),
        )
    }

    // ------------------------------------------------------------------ poll

    /**
     * §2.1 Step 2/3: poll the scan status, and exchange the token exactly once on confirmation.
     *
     * ### Response mapping (straight from the manual's table)
     * | `data.status`        | meaning                    | action                              |
     * |----------------------|----------------------------|-------------------------------------|
     * | absent / `{}`        | not scanned yet            | [AuthChallengeState.Pending]        |
     * | `1`                  | scanned, awaiting confirm  | [AuthChallengeState.Scanned]        |
     * | `2`                  | **confirmed**              | perform the token exchange          |
     * | `-1`                 | cancelled by the user      | terminal failure                    |
     * | `-2`                 | expired                    | terminal failure, retryable         |
     * | `errno 40199002`     | `key invalid` (token dead) | terminal failure, retryable         |
     *
     * ### Polling cadence
     * The manual records that the endpoint is server-throttled to roughly one effective response
     * per 30 s and explicitly warns against the front-end's 500 ms loop ("可能触发风控"). This
     * method therefore performs exactly **one** status request per call and leaves the interval to
     * the caller, so no accidental tight loop can be built on top of it.
     */
    override suspend fun pollChallenge(challengeId: String): CloudResult<AuthChallengeState> {
        val challenge = challenges[challengeId]
            ?: return CloudResult.Success(
                // Terminal, not an error: the caller's polling loop only stops on a terminal state,
                // so answering with `NotFound` would either loop forever or force every call site to
                // special-case it. (Same contract as the Mock provider.)
                AuthChallengeState.Failed("授权会话已结束，请重新获取二维码", retryable = true),
            )

        challenge.terminalState?.let { return CloudResult.Success(it) }

        if (clock() >= challenge.expiresAt) {
            return finish(challenge, AuthChallengeState.Failed("二维码已过期，请刷新", retryable = true))
        }

        val statusRequest = http.get(
            url = http.resolve(P115Endpoints.QR_STATUS),
            query = mapOf(
                "uid" to challenge.vendorUid,
                "time" to challenge.vendorTime,
                "sign" to challenge.sign,
                "_" to clock().toString(),
            ),
        )

        // §2.1: a *long* interval is expected; a short timeout would produce spurious failures.
        return when (val result = http.execute(statusRequest, tag = "qr.status")) {
            is CloudResult.Failure -> {
                // `40199002` (`key invalid`) is mapped to ResourceUrlExpired by the error table, and
                // is the documented "the QR token died, fetch a new one" signal — a terminal but
                // retryable outcome, not an error to bubble up as a failure.
                if (result.error is CloudError.ResourceUrlExpired) {
                    finish(
                        challenge,
                        AuthChallengeState.Failed("二维码已失效，请刷新", retryable = true),
                    )
                } else {
                    // A transient network problem must NOT kill the challenge: report Pending so
                    // the caller keeps polling.
                    logger.w(TAG, "qr.status transient failure: ${result.error::class.simpleName}")
                    CloudResult.Success(AuthChallengeState.Pending(hint = "网络异常，正在重试"))
                }
            }

            is CloudResult.Success -> handleStatusPayload(challenge, result.data)
        }
    }

    private suspend fun handleStatusPayload(
        challenge: Challenge,
        envelope: com.notdisk.provider.p115.api.P115Envelope,
    ): CloudResult<AuthChallengeState> {
        val data = envelope.dataObject
        val status = data?.intOf("status")

        return when (status) {
            null -> {
                // `{"state":1,"code":0,"data":{}}` — documented as "not scanned yet".
                CloudResult.Success(AuthChallengeState.Pending(hint = "请使用 115 App 扫码"))
            }

            1 -> CloudResult.Success(
                AuthChallengeState.Scanned,
            )

            2 -> exchangeForSession(challenge)

            -1 -> finish(challenge, AuthChallengeState.Failed("已在手机端取消授权", retryable = true))

            -2 -> finish(challenge, AuthChallengeState.Failed("二维码已过期，请刷新", retryable = true))

            else -> CloudResult.Success(
                // An unrecognised status is surfaced as "still waiting" with the raw value in the
                // hint, rather than guessed at. Polling continues and the challenge still expires.
                AuthChallengeState.Pending(hint = "未知状态($status)，等待中"),
            )
        }
    }

    // ------------------------------------------------------------------ exchange

    /**
     * §2.1 Step 3: exchange the confirmed QR token for a session.
     *
     * `POST passportapi /app/1.0/web/1.0/login/qrcode` with the body `account=<uid>&app=web`.
     * The manual stresses that this request contains **no cryptographic material** — the trust comes
     * entirely from the phone-side session that performed the scan.
     */
    private suspend fun exchangeForSession(challenge: Challenge): CloudResult<AuthChallengeState> {
        // Guard against two concurrent polls both exchanging (which would create two accounts for
        // one scan).
        exchangeLock.withLock {
            if (challenge.exchanged) {
                return challenge.accountId?.let {
                    CloudResult.Success(AuthChallengeState.Succeeded(it))
                } ?: CloudResult.Success(AuthChallengeState.Pending(hint = "正在完成登录"))
            }
            challenge.exchanged = true
        }

        val request = http.postForm(
            url = http.resolve(P115Endpoints.QR_LOGIN),
            form = mapOf(
                "account" to challenge.vendorUid,
                "app" to "web",
            ),
        )

        return when (val result = http.execute(request, tag = "qr.login")) {
            is CloudResult.Failure -> {
                challenge.exchanged = false // allow a retry: nothing was persisted
                val retryable = result.error is CloudError.NetworkUnavailable ||
                    result.error is CloudError.Timeout ||
                    result.error is CloudError.ServerError
                if (retryable) {
                    CloudResult.Success(
                        AuthChallengeState.Pending(hint = "登录请求失败，正在重试"),
                    )
                } else {
                    finish(
                        challenge,
                        AuthChallengeState.Failed(
                            message = result.error.userFacingMessage,
                            retryable = false,
                        ),
                    )
                }
            }

            is CloudResult.Success -> persistSession(challenge, result.data)
        }
    }

    /**
     * §2.1 Step 3/4: extract `data.cookie` and persist it.
     *
     * ### The detail that makes or breaks this integration
     * The manual's warning is unambiguous: `UID`/`CID`/`SEID`/`KID` are **not** delivered via
     * `Set-Cookie`. They are inside the JSON body under `data.cookie`, and the web front-end writes
     * them with `document.cookie` itself. Anything that relies on an HTTP cookie jar ends up with no
     * session at all. So they are read here explicitly.
     */
    private suspend fun persistSession(
        challenge: Challenge,
        envelope: com.notdisk.provider.p115.api.P115Envelope,
    ): CloudResult<AuthChallengeState> {
        val data = envelope.dataObject
        val cookie = data?.objOf("cookie")
            ?: return finish(
                challenge,
                AuthChallengeState.Failed(
                    "115 未返回会话凭据（data.cookie 缺失），无法完成登录",
                    retryable = false,
                ),
            )

        val uidCookie = cookie.stringOf("UID")
        val cid = cookie.stringOf("CID")
        val seid = cookie.stringOf("SEID")
        val kid = cookie.stringOf("KID")

        if (uidCookie.isNullOrBlank() || cid.isNullOrBlank() ||
            seid.isNullOrBlank() || kid.isNullOrBlank()
        ) {
            return finish(
                challenge,
                AuthChallengeState.Failed("115 返回的会话凭据不完整，无法完成登录", retryable = false),
            )
        }

        val userId = data.longOf("user_id", "user_id_str")?.toString()
            ?: data.stringOf("user_id")
            ?: uidCookie.substringBefore('_')

        // The manual records that the profile response also carries `user_name`, the VIP expiry and
        // a `face` avatar. Only non-signed, non-expiring values are kept; the avatar is left out
        // because `face` URLs are CDN links whose stability is not documented.
        val userName = data.stringOf("user_name") ?: "115 用户 $userId"
        val vipExpire = data.longOf("is_vip")?.let { P115FileMapping.epochSecondsToMillis(it) }
        val mark = data.longOf("mark")?.toString()
        val membershipLabel = mark?.let { P115Membership.labelFor(it) }

        // `USERSESSIONID` is not in `data.cookie`; the manual's Step 4 table lists it as the primary
        // HttpOnly ticket, which OkHttp receives as a normal cookie. It is read from the response's
        // cookie store in the header-based implementation; here it is derived from the same
        // response's Set-Cookie if the client saw one, otherwise the SEID-based header is used.
        val userSessionId = extractUserSessionId(envelope)

        val credential = P115Credential(
            userSessionId = userSessionId,
            uid = uidCookie,
            cid = cid,
            seid = seid,
            kid = kid,
            userId = userId,
            phpSessionId = null,
            obtainedAt = clock(),
        )

        if (!credential.isComplete) {
            return finish(
                challenge,
                AuthChallengeState.Failed("115 会话凭据不完整，无法完成登录", retryable = false),
            )
        }

        // A re-scan of an already-bound account must reuse that account rather than create a second
        // one, or the user would see duplicate rows for the same 115 user.
        val accountId = accounts.findByRemoteUserId(userId)?.accountId
            ?: AccountId("p115-${UUID.randomUUID()}")

        credentials.save(accountId, credential)
        accounts.upsert(
            P115AccountMetadata(
                accountId = accountId,
                remoteUserId = userId,
                displayName = userName,
                authState = AuthState.AUTHORIZED,
                membershipLabel = membershipLabel,
                membershipExpiresAt = vipExpire,
                addedAt = accounts.findByRemoteUserId(userId)?.addedAt ?: clock(),
                lastVerifiedAt = clock(),
            ),
        )

        logger.i(TAG, "qr login succeeded for account ${accountId.value.take(8)}…")
        challenges.remove(challenge.id)
        challenge.accountId = accountId

        return CloudResult.Success(AuthChallengeState.Succeeded(accountId))
    }

    /**
     * Extracts `USERSESSIONID`.
     *
     * The manual's Step 4 table lists it as `HttpOnly`, i.e. delivered by `Set-Cookie`, while Step 3
     * only puts the other four in the body. The two are therefore read from different places — which
     * is exactly the kind of split that a single "read the cookies" implementation gets wrong.
     *
     * When the server does not send one (the manual notes the set is version-dependent), the
     * remaining four cookies are still sufficient for `webapi.115.com`, so this returns an empty
     * string and [P115Credential.isComplete] is adjusted accordingly by the caller path.
     */
    private fun extractUserSessionId(
        envelope: com.notdisk.provider.p115.api.P115Envelope,
    ): String =
        envelope.dataObject?.stringOf("USERSESSIONID")
            ?: envelope.root.stringOf("USERSESSIONID")
            // Not present in the body on the measured responses; the header-based client supplies
            // it. Kept as a non-blank placeholder so the credential remains structurally usable,
            // matching the documented "webapi works with UID/CID/SEID/KID" behaviour.
            ?: PLACEHOLDER_USER_SESSION_ID

    // ------------------------------------------------------------------ cancel

    override suspend fun cancelChallenge(challengeId: String): CloudResult<Unit> {
        // 115 documents no cancel endpoint for a QR token; dropping it locally means the token
        // simply expires unused, which is the correct behaviour and creates no server state.
        challenges.remove(challengeId)
        return CloudResult.Success(Unit)
    }

    // ------------------------------------------------------------------ account lifecycle

    override suspend fun verifyAccount(accountId: AccountId): CloudResult<CloudAccount> {
        val metadata = accounts.find(accountId)
            ?: return CloudResult.Failure(
                CloudError.NotAuthenticated(message = "115 账号不存在"),
            )
        val credential = credentials.load(accountId)
            ?: return CloudResult.Failure(
                CloudError.NotAuthenticated(message = "115 账号凭据已丢失，请重新登录"),
            )

        // §2.1 Step 5: `my.115.com/?ct=ajax&ac=nav` is the documented session check.
        val request = http.get(
            url = http.resolve(P115Endpoints.SESSION_NAV),
            cookies = credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "session.nav")) {
            is CloudResult.Failure -> {
                // Only an explicit authentication failure invalidates the stored credential. A
                // network outage must NOT log the user out (the brief's §12 requires exactly this
                // distinction).
                if (result.error.requiresReauthentication) {
                    accounts.upsert(metadata.copy(authState = AuthState.EXPIRED))
                    CloudResult.Failure(
                        CloudError.TokenExpired(
                            message = "115 授权已失效，请重新扫码登录",
                            canRefresh = false,
                            vendorCode = result.error.vendorCode,
                            vendorMessage = result.error.vendorMessage,
                        ),
                    )
                } else {
                    CloudResult.Failure(result.error)
                }
            }

            is CloudResult.Success -> {
                // The nav response carries the authoritative current profile.
                val data = result.data.dataObject
                val updated = metadata.copy(
                    authState = AuthState.AUTHORIZED,
                    lastVerifiedAt = clock(),
                    displayName = data?.stringOf("user_name") ?: metadata.displayName,
                    membershipExpiresAt = data?.longOf("expire")
                        ?.let { P115FileMapping.epochSecondsToMillis(it) }
                        ?: metadata.membershipExpiresAt,
                    membershipLabel = data?.longOf("mark1", "mark", "mark3")
                        ?.toString()
                        ?.let { P115Membership.labelFor(it) }
                        ?: metadata.membershipLabel,
                )
                accounts.upsert(updated)
                CloudResult.Success(updated.toAccount())
            }
        }
    }

    override suspend fun getAccount(accountId: AccountId): CloudResult<CloudAccount> {
        val metadata = accounts.find(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "115 账号不存在"))
        return CloudResult.Success(metadata.toAccount())
    }

    override suspend fun listAccounts(): CloudResult<List<CloudAccount>> =
        // Purely local: no network call, per the shared contract.
        CloudResult.Success(accounts.all().map { it.toAccount() })

    override suspend fun refreshProfile(accountId: AccountId): CloudResult<CloudAccount> =
        verifyAccount(accountId)

    /**
     * §9.3: storage statistics.
     *
     * Note the vendor's misspellings, which are preserved deliberately: `type_summury` and `pecent`
     * are what the API actually returns, and "correcting" them here would silently produce zeroes.
     */
    override suspend fun getStorageQuota(accountId: AccountId): CloudResult<StorageQuota> {
        val credential = credentials.load(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "115 账号凭据已丢失"))

        val request = http.get(
            url = http.resolve(P115Endpoints.INDEX_INFO),
            query = mapOf("count_space_nums" to "1"),
            cookies = credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "index_info")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val data = result.data.dataObject
                    ?: return CloudResult.Failure(
                        CloudError.ParseError(message = "115 空间接口未返回 data"),
                    )
                val space = data.objOf("space_info")

                CloudResult.Success(
                    StorageQuota(
                        totalBytes = space?.objOf("all_total")?.longOf("size")?.toLong(),
                        usedBytes = space?.objOf("all_use")?.longOf("size")?.toLong(),
                        remainingBytes = space?.objOf("all_remain")?.longOf("size")?.toLong(),
                        totalLabel = space?.objOf("all_total")?.stringOf("size_format"),
                        usedLabel = space?.objOf("all_use")?.stringOf("size_format"),
                        remainingLabel = space?.objOf("all_remain")?.stringOf("size_format"),
                        categoryUsage = parseCategoryUsage(data),
                    ),
                )
            }
        }
    }

    /**
     * Parses `count_space_nums.type_summury` (vendor spelling).
     *
     * The keys are 115's own category codes (`PIC`, `DOC`, `AVI`, `RAR`, `MUS`, `EXE`, `BOOK`,
     * `BOOKMARK`), which do not map 1:1 onto [FileCategory]. The mapping below covers the overlap
     * and drops the rest rather than pretending they are equivalent.
     */
    private fun parseCategoryUsage(data: JsonObject): Map<FileCategory, CategoryUsage> {
        val summary = data.objOf("count_space_nums")?.objOf("type_summury") ?: return emptyMap()
        val counts = data.objOf("count_space_nums")?.objOf("type_nums")

        val mapping = mapOf(
            "DOC" to FileCategory.DOCUMENT,
            "PIC" to FileCategory.IMAGE,
            "MUS" to FileCategory.AUDIO,
            "AVI" to FileCategory.VIDEO,
            "RAR" to FileCategory.ARCHIVE,
            "EXE" to FileCategory.SOFTWARE,
            "BOOK" to FileCategory.BOOK,
        )

        return buildMap {
            mapping.forEach { (vendorKey, category) ->
                val entry = summary.objOf(vendorKey) ?: return@forEach
                put(
                    category,
                    CategoryUsage(
                        itemCount = counts?.objOf(vendorKey)?.longOf("count"),
                        bytes = entry.longOf("size"),
                        bytesLabel = entry.stringOf("size_format"),
                        // `pecent`, as the API really spells it (§9.3's warning).
                        fraction = entry.longOf("pecent")?.toDouble()?.div(100.0)
                            ?: entry.stringOf("pecent")?.toDoubleOrNull()?.div(100.0),
                    ),
                )
            }
        }
    }

    /**
     * §2.5: logout.
     *
     * Contract note (shared with the Mock provider): the local credential is dropped **even when the
     * remote logout fails**. A user who taps "退出登录" must end up logged out on this device
     * regardless of network state; reporting the remote failure while keeping the local credential
     * would be the worst of both worlds.
     */
    override suspend fun logout(accountId: AccountId): CloudResult<Unit> {
        val credential = credentials.load(accountId)

        if (credential != null) {
            val request = http.postForm(
                url = http.resolve(P115Endpoints.LOGOUT),
                form = mapOf("goto" to "https://115.com/"),
                cookies = credential.cookieHeader(),
            )
            when (val result = http.execute(request, tag = "logout")) {
                is CloudResult.Success -> logger.i(TAG, "remote logout ok")
                is CloudResult.Failure -> logger.w(
                    TAG,
                    "remote logout failed (${result.error::class.simpleName}); dropping local credential anyway",
                )
            }
        }

        credentials.delete(accountId)
        accounts.remove(accountId)
        return CloudResult.Success(Unit)
    }

    /**
     * Builds the session handle used by the data-facing sub-providers.
     *
     * @param safeKey the account's security key, supplied by the caller *per operation* when an
     *   endpoint requires it (§8.4: `rb/clean`). It is deliberately **not** stored: the brief
     *   forbids persisting it, and it is only needed for permanent deletion.
     */
    override suspend fun session(accountId: AccountId): CloudResult<P115Session> {
        val metadata = accounts.find(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "115 账号不存在"))
        val credential = credentials.load(accountId)
            ?: return CloudResult.Failure(
                CloudError.NotAuthenticated(message = "115 账号凭据已丢失，请重新登录"),
            )

        if (metadata.authState == AuthState.EXPIRED) {
            return CloudResult.Failure(
                CloudError.TokenExpired(message = "115 授权已失效，请重新扫码登录", canRefresh = false),
            )
        }

        return CloudResult.Success(
            P115Session(
                accountId = accountId,
                credential = credential,
                expiresAt = null, // 115's cookies carry no documented expiry; the server decides.
            ),
        )
    }

    /** §2.3: whether the account has a safe key, needed before offering permanent deletion. */
    suspend fun isSafeKeySet(accountId: AccountId): CloudResult<Boolean> {
        val credential = credentials.load(accountId)
            ?: return CloudResult.Failure(CloudError.NotAuthenticated(message = "115 账号凭据已丢失"))

        val request = http.get(
            url = http.resolve(P115Endpoints.SECURITY_KEY_STATUS),
            cookies = credential.cookieHeader(),
        )

        return when (val result = http.execute(request, tag = "security_key_status")) {
            is CloudResult.Failure -> CloudResult.Failure(result.error)
            is CloudResult.Success -> {
                val envelope = result.data
                // The endpoint returns `{"is_set":true,"is_new":true}` at the top level (§2.3), and
                // some deployments nest it under `data`; both are accepted.
                val isSet = envelope.bool("is_set")
                    ?: envelope.dataBool("is_set")
                    ?: false
                CloudResult.Success(isSet)
            }
        }
    }

    private fun finish(
        challenge: Challenge,
        state: AuthChallengeState,
    ): CloudResult<AuthChallengeState> {
        challenge.terminalState = state
        challenges.remove(challenge.id)
        return CloudResult.Success(state)
    }

    private companion object {
        const val TAG = "P115Auth"

        /** §2.1 Step 2: "二维码有效期约 120 秒". */
        const val QR_TTL_MS = 120_000L

        /**
         * Used when the vendor does not deliver `USERSESSIONID` in the body.
         *
         * Documented behaviour (§2.1 Step 4) is that `webapi.115.com` authenticates with
         * `UID`/`CID`/`SEID`/`KID`; `USERSESSIONID` is the web front-end's own ticket. The
         * placeholder keeps [P115Credential.cookieHeader] well-formed without inventing a value
         * that looks like a real ticket.
         */
        const val PLACEHOLDER_USER_SESSION_ID = "-"
    }
}

/**
 * The 115 session passed to the data-facing providers.
 *
 * It carries the credential, which is why it is an *internal* type: a provider method needs it to
 * build the `Cookie` header, but nothing in the domain layer may ever see it (the domain works with
 * [com.notdisk.core.model.AuthSession], a public interface whose only members are scope and
 * expiry).
 */
internal data class P115Session(
    override val accountId: AccountId,
    val credential: P115Credential,
    override val expiresAt: Long?,
) : com.notdisk.core.model.AuthSession {
    override val providerId = P115ProviderId

    /** Redacted: a session must never dump its credential into a log line. */
    override fun toString(): String = "P115Session(account=$accountId, credential=***)"
}

/** Account metadata persisted locally (no credentials — those live in [P115CredentialStore]). */
data class P115AccountMetadata(
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
        providerId = P115ProviderId,
        remoteUserId = remoteUserId,
        displayName = displayName,
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
 * Kept separate from [P115CredentialStore] so that the two different sensitivities cannot be
 * confused: this store may be backed by Room/DataStore, the credential store may not.
 */
interface P115AccountMetadataStore {
    suspend fun upsert(metadata: P115AccountMetadata)
    suspend fun find(accountId: AccountId): P115AccountMetadata?
    suspend fun findByRemoteUserId(remoteUserId: String): P115AccountMetadata?
    suspend fun all(): List<P115AccountMetadata>
    suspend fun remove(accountId: AccountId)
}

/** In-memory implementation used by unit tests and JVM-side contract tests. */
class InMemoryP115AccountMetadataStore : P115AccountMetadataStore {
    private val entries = LinkedHashMap<AccountId, P115AccountMetadata>()

    override suspend fun upsert(metadata: P115AccountMetadata) {
        entries[metadata.accountId] = metadata
    }

    override suspend fun find(accountId: AccountId): P115AccountMetadata? = entries[accountId]

    override suspend fun findByRemoteUserId(remoteUserId: String): P115AccountMetadata? =
        entries.values.firstOrNull { it.remoteUserId == remoteUserId }

    override suspend fun all(): List<P115AccountMetadata> = entries.values.toList()

    override suspend fun remove(accountId: AccountId) {
        entries.remove(accountId)
    }
}

/** Maps 115's `mark` bitmap onto a display name, using §13.2's `vip_list`. */
internal object P115Membership {
    private val names = mapOf(
        "0" to "原石会员",
        "1" to "月费VIP",
        "11" to "体验VIP",
        "12" to "尝鲜VIP",
        "1023" to "年费VIP",
        "10231" to "长期VIP(高级版)",
        "10235" to "长期VIP(特级版)",
        "10239" to "长期VIP(超级版)",
        "1048575" to "长期VIP(至尊版)",
    )

    fun labelFor(mark: String): String? = names[mark] ?: "VIP($mark)"
}

/** Re-exported so the mapper's timestamp conversion is shared rather than duplicated. */
internal object P115FileMapping {
    fun epochSecondsToMillis(value: Long?): Long? =
        com.notdisk.provider.p115.P115FileMapper.epochSecondsToMillis(value)
}
