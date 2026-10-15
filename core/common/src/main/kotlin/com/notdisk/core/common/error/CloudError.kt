package com.notdisk.core.common.error

import kotlinx.coroutines.CancellationException

/**
 * Unified, provider-agnostic error model.
 *
 * Every provider maps its vendor error codes into exactly one of these cases. The UI and the
 * domain layer only ever branch on this type, never on vendor codes.
 *
 * [vendorCode] / [vendorMessage] preserve the raw upstream signal for diagnostics. They must
 * never contain credentials (tokens, cookies, signed URLs); redact before constructing.
 */
sealed class CloudError(
    open val message: String,
    open val cause: Throwable? = null,
    open val httpStatus: Int? = null,
    open val vendorCode: String? = null,
    open val vendorMessage: String? = null,
) {

    /** The device has no usable network connection. */
    data class NetworkUnavailable(
        override val message: String = "网络不可用",
        override val cause: Throwable? = null,
    ) : CloudError(message, cause)

    /** The request did not complete inside the configured timeout. */
    data class Timeout(
        override val message: String = "请求超时",
        override val cause: Throwable? = null,
    ) : CloudError(message, cause)

    /** The remote service answered with a 5xx or an equivalent server-side failure. */
    data class ServerError(
        override val message: String = "服务端错误",
        override val httpStatus: Int? = null,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
        override val cause: Throwable? = null,
    ) : CloudError(message, cause, httpStatus, vendorCode, vendorMessage)

    /** The service is rate limiting this account/device. */
    data class RateLimited(
        override val message: String = "请求过于频繁，请稍后再试",
        val retryAfterSeconds: Long? = null,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 429, vendorCode, vendorMessage)

    /** No account is bound, or the session was never established. */
    data class NotAuthenticated(
        override val message: String = "尚未登录",
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 401, vendorCode, vendorMessage)

    /**
     * The stored credential is no longer valid and cannot be refreshed silently.
     * The UI must route the user to re-authorisation.
     */
    data class TokenExpired(
        override val message: String = "授权已失效，请重新登录",
        val canRefresh: Boolean = false,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 401, vendorCode, vendorMessage)

    /** Authenticated, but not allowed to perform this operation on this resource. */
    data class PermissionDenied(
        override val message: String = "权限不足",
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 403, vendorCode, vendorMessage)

    /** The account needs an additional secret (e.g. a safe key / second factor). */
    data class AdditionalVerificationRequired(
        override val message: String = "需要额外验证",
        val verificationKind: String,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 403, vendorCode, vendorMessage)

    /** The addressed file or folder does not exist on the remote side. */
    data class NotFound(
        override val message: String = "文件不存在",
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 404, vendorCode, vendorMessage)

    /** The file existed but has been deleted (or moved out of the reachable tree). */
    data class Deleted(
        override val message: String = "文件已被删除",
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 410, vendorCode, vendorMessage)

    /** The request was rejected because of malformed or inconsistent arguments. */
    data class InvalidArgument(
        override val message: String,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 400, vendorCode, vendorMessage)

    /** The provider genuinely does not implement this capability. */
    data class Unsupported(
        override val message: String = "该网盘不支持此操作",
        val capability: String? = null,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, null, vendorCode, vendorMessage)

    /** The previously resolved media/download URL is no longer valid; re-resolve it. */
    data class ResourceUrlExpired(
        override val message: String = "资源地址已过期",
        val refreshable: Boolean = true,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, 403, vendorCode, vendorMessage)

    /** The resource was fetched but the client cannot render/decode it. */
    data class IncompatibleResource(
        override val message: String = "资源格式不受支持",
        val mimeType: String? = null,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, null, vendorCode, vendorMessage)

    /** Quota / storage is exhausted on the remote side. */
    data class QuotaExceeded(
        override val message: String = "网盘空间不足",
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, null, null, vendorCode, vendorMessage)

    /** The upstream response could not be parsed into the expected shape. */
    data class ParseError(
        override val message: String = "响应解析失败",
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
        override val cause: Throwable? = null,
    ) : CloudError(message, cause, null, vendorCode, vendorMessage)

    /** Local I/O (disk, content resolver, database) failed. */
    data class LocalIoError(
        override val message: String = "本地读写失败",
        override val cause: Throwable? = null,
    ) : CloudError(message, cause)

    /** Anything not covered above. Never used to hide a more specific known case. */
    data class Unknown(
        override val message: String = "未知错误",
        override val cause: Throwable? = null,
        override val httpStatus: Int? = null,
        override val vendorCode: String? = null,
        override val vendorMessage: String? = null,
    ) : CloudError(message, cause, httpStatus, vendorCode, vendorMessage)

    /** True when retrying the *same* request may plausibly succeed. */
    open val isRetryable: Boolean
        get() = when (this) {
            is NetworkUnavailable, is Timeout, is ServerError, is RateLimited -> true
            else -> false
        }

    /** True when the user must re-authorise before anything else can work. */
    val requiresReauthentication: Boolean
        get() = this is NotAuthenticated || (this is TokenExpired)

    /** A short, user-facing message that never leaks vendor internals. */
    val userFacingMessage: String
        get() = message
}

/**
 * Executes [block], converting non-cancellation throwables into typed [CloudError]s.
 * Cancellation always propagates.
 */
inline fun <T> runCatchingToCloud(block: () -> T): com.notdisk.core.common.result.CloudResult<T> =
    try {
        com.notdisk.core.common.result.CloudResult.Success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (io: java.io.IOException) {
        com.notdisk.core.common.result.CloudResult.Failure(
            CloudError.NetworkUnavailable(cause = io),
        )
    } catch (t: Throwable) {
        com.notdisk.core.common.result.CloudResult.Failure(
            CloudError.Unknown(cause = t, message = t.message ?: "未知错误"),
        )
    }
