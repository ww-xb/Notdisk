package com.notdisk.core.provider

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.AuthFlowKind
import com.notdisk.core.model.AuthSession
import com.notdisk.core.model.AuthState
import com.notdisk.core.model.CloudAccount
import com.notdisk.core.model.FileCategory
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FileItem
import com.notdisk.core.model.FilePage
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.FileSearchQuery
import com.notdisk.core.model.FileSort
import com.notdisk.core.model.ListChildrenRequest
import com.notdisk.core.model.StorageQuota

/**
 * Account binding, authorisation and session lifecycle.
 *
 * ### Interactive flow contract
 * A provider that advertises [AuthFlowKind.QR_CODE] / [OAUTH_REDIRECT] / [DEVICE_CODE] returns a
 * [AuthChallenge] describing what the UI must do, then the caller polls [pollChallenge] until it
 * terminates. This keeps Activity/Intent handling out of the domain layer: the provider produces
 * *data* (a URL, a code to display), the platform layer decides how to render it.
 */
interface AuthProvider {

    /**
     * Begins an interactive authorisation and returns the challenge to present.
     *
     * Fails with [com.notdisk.core.common.error.CloudError.Unsupported] when [flow] is not in
     * `capabilities().account.interactiveFlows`.
     */
    suspend fun beginAuthorization(
        flow: AuthFlowKind,
        params: AuthFlowParams = AuthFlowParams.Empty,
    ): CloudResult<AuthChallenge>

    /**
     * Polls an in-flight challenge. Implementations must be safe to call repeatedly and must
     * degrade to [AuthChallengeState.Failed] rather than throwing when the challenge expired.
     */
    suspend fun pollChallenge(challengeId: String): CloudResult<AuthChallengeState>

    /** Cancels a challenge and releases any server-side state the provider created for it. */
    suspend fun cancelChallenge(challengeId: String): CloudResult<Unit>

    /**
     * Verifies that stored credentials for [accountId] are still usable, refreshing silently
     * when the provider supports it.
     *
     * Returns the refreshed [CloudAccount] (with a corrected [AuthState]) on success, or a typed
     * failure: [com.notdisk.core.common.error.CloudError.TokenExpired] when the user must
     * re-authorise, [com.notdisk.core.common.error.CloudError.NotAuthenticated] when nothing
     * is stored.
     */
    suspend fun verifyAccount(accountId: AccountId): CloudResult<CloudAccount>

    /** Reads the stored account profile without contacting the vendor. */
    suspend fun getAccount(accountId: AccountId): CloudResult<CloudAccount>

    /** Lists every account bound to this provider. Never contacts the vendor. */
    suspend fun listAccounts(): CloudResult<List<CloudAccount>>

    /** Fetches the vendor's current profile and persists it (display name, VIP, avatar). */
    suspend fun refreshProfile(accountId: AccountId): CloudResult<CloudAccount>

    /** Storage usage for the account. */
    suspend fun getStorageQuota(accountId: AccountId): CloudResult<StorageQuota>

    /**
     * Invalidates the credential on the vendor side when the vendor has a logout endpoint, then
     * removes it locally.
     *
     * Must succeed even when the remote logout fails (the local credential is still dropped) —
     * report the remote failure through the returned value only if the local removal failed.
     */
    suspend fun logout(accountId: AccountId): CloudResult<Unit>

    /**
     * Produces the session object used by the other sub-providers.
     *
     * Fails with [com.notdisk.core.common.error.CloudError.NotAuthenticated] when the account
     * is unknown, and [com.notdisk.core.common.error.CloudError.TokenExpired] when the stored
     * credential can no longer be used.
     */
    suspend fun session(accountId: AccountId): CloudResult<AuthSession>
}

/** Extra inputs an interactive flow may need. */
data class AuthFlowParams(
    val account: String? = null,
    val password: String? = null,
    val manualCredential: String? = null,
    val clientId: String? = null,
    val redirectUri: String? = null,
) {
    companion object {
        val Empty = AuthFlowParams()
    }
}

/**
 * What the UI must show the user to complete authorisation.
 *
 * Exactly one variant is produced per started flow.
 */
sealed interface AuthChallenge {

    val challengeId: String

    /**
     * Render [content] as a QR code and poll until [status].
     * 115's flow: fetch a token, render `data.qrcode`, poll `/get/status`.
     */
    data class QrCode(
        override val challengeId: String,
        /** Text to encode into the QR image. */
        val content: String,
        /** Epoch millis after which the QR must be regenerated. */
        val expiresAt: Long?,
        /** Server-rendered QR image as a fallback when scanning `content` fails. */
        val imageUrl: String? = null,
    ) : AuthChallenge

    /** Open [authorizationUrl] in a browser/Custom Tab; the platform layer captures the redirect. */
    data class Redirect(
        override val challengeId: String,
        val authorizationUrl: String,
        /** The scheme/host the platform layer must intercept. */
        val redirectUri: String,
        val state: String?,
    ) : AuthChallenge

    /** Show [userCode] and [verificationUrl]; poll in the background. */
    data class DeviceCode(
        override val challengeId: String,
        val userCode: String,
        val verificationUrl: String,
        val expiresAt: Long?,
    ) : AuthChallenge

    /** The flow already produced credentials without user interaction (e.g. token paste accepted). */
    data class Completed(
        override val challengeId: String,
        val accountId: AccountId,
    ) : AuthChallenge
}

/**
 * The state of an in-flight challenge.
 *
 * [Succeeded.accountId] is the **local** account id: the provider has already persisted the
 * credential by the time it reports success.
 */
sealed interface AuthChallengeState {

    /** Waiting for the user. [hint] is a display string from the vendor when it provides one. */
    data class Pending(val hint: String? = null) : AuthChallengeState

    /** The user completed the flow on the vendor side, but the exchange is still in progress. */
    data object Scanned : AuthChallengeState

    data class Succeeded(val accountId: AccountId) : AuthChallengeState

    /** The user cancelled, or the vendor invalidated the challenge. */
    data class Failed(
        val message: String,
        val retryable: Boolean = true,
    ) : AuthChallengeState
}

/**
 * The remote file system surface.
 *
 * Every method is scoped to an [AccountId]; nothing here knows about "the current account".
 */
interface FileSystemProvider {

    /**
     * The account's root folder.
     *
     * For 115 this is the synthetic `cid = 0` ("根目录") node, which is **not** returned by
     * `/files` itself; it is materialised here.
     */
    suspend fun getRoot(accountId: AccountId): CloudResult<FileItem>

    /** One page of a folder's children. */
    suspend fun listChildren(
        accountId: AccountId,
        request: ListChildrenRequest,
    ): CloudResult<FilePage>

    /** Details for a single file or folder. */
    suspend fun getFile(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<FileItem>

    /** Full root→file path. Providers without a path endpoint reconstruct it by walking parents. */
    suspend fun getPath(
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<FilePath>

    /** Server-side search when supported; otherwise fails with `CloudError.Unsupported`. */
    suspend fun search(
        accountId: AccountId,
        query: FileSearchQuery,
    ): CloudResult<FilePage>

    /**
     * Resolves a human path such as `我的文件/电视剧` into a [FileId].
     * Used by deep links and by tests; not required for normal navigation.
     */
    suspend fun resolvePath(
        accountId: AccountId,
        path: String,
    ): CloudResult<FileId>
}

/** Convenience overload mirroring the interface sketch in the project brief. */
suspend fun FileSystemProvider.listChildren(
    accountId: AccountId,
    parentId: FileId,
    cursor: String? = null,
    pageSize: Int = ListChildrenRequest.DEFAULT_PAGE_SIZE,
    sort: FileSort = FileSort.Default,
): CloudResult<FilePage> = listChildren(
    accountId,
    ListChildrenRequest(parentId = parentId, cursor = cursor, pageSize = pageSize, sort = sort),
)

/** Convenience overload for category/suffix-filtered listing. */
suspend fun FileSystemProvider.listCategory(
    accountId: AccountId,
    parentId: FileId,
    category: FileCategory,
): CloudResult<FilePage> = search(
    accountId,
    FileSearchQuery(keyword = "", parentId = parentId, category = category),
)

/** Non-null [AuthState] helper used by the UI to render account badges. */
fun CloudAccount.authBadge(): String = when (authState) {
    AuthState.AUTHORIZED -> "已授权"
    AuthState.EXPIRED -> "授权失效"
    AuthState.REVOKED -> "已退出"
    AuthState.PENDING_AUTHORIZATION -> "等待授权"
    AuthState.UNKNOWN -> "状态未知"
}
