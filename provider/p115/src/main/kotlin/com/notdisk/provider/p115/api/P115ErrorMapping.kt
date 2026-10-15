package com.notdisk.provider.p115.api

import com.notdisk.core.common.error.CloudError

/**
 * The vendor error codes documented in `API_REFERENCE.md` §14.
 *
 * Kept as named constants (rather than inline numbers) because the same number appears in several
 * places and its meaning is not guessable from the value. Every constant below is traceable to the
 * reference manual; nothing here is invented.
 */
internal object P115ErrorCodes {

    // ---- §14.1 authentication ----
    /** `用户名或密码错误` */
    const val WRONG_CREDENTIALS = 40101045

    /** `需二次验证` — the account is under a risk-control challenge. */
    const val TWO_FACTOR_REQUIRED = 40101010
    const val TWO_FACTOR_REQUIRED_ALT = 70128

    /** `公钥过期` — re-fetch the RSA key and replay the login exactly once. */
    const val PUBLIC_KEY_EXPIRED = 40101060

    /** `账号异常` — the account itself is flagged; the user must appeal. */
    const val ACCOUNT_ABNORMAL = 40111010

    /** `老乡验证失败` — source/timestamp validation failed (e.g. exchanging a QR before scanning). */
    const val QR_EXCHANGE_REJECTED = 40101017

    /** `key invalid` — the QR token expired or its signature is stale; fetch a new token. */
    const val QR_KEY_INVALID = 40199002

    /** `短信触发风控` — an SMS challenge is required before continuing. */
    const val SMS_RISK_CONTROL = 20026

    // ---- §14.2 file operations ----
    /** `参数错误` — the classic symptom of using the wrong parameter name (`file_ids=` vs `fid[]=`). */
    const val INVALID_PARAMETER = 1001

    /** `删除失败，没有选择文件(夹)` — `rb/delete` without the `fid[i]` indexed parameters. */
    const val DELETE_NO_SELECTION = 231013

    /** `还原失败，没有选择文件(夹)` — `rb/revert` without the `rid[i]` indexed parameters. */
    const val RESTORE_NO_SELECTION = 231012

    /** `空间不足` */
    const val INSUFFICIENT_SPACE = 4500032

    /** `操作尚未执行完成` — an asynchronous job is still running. */
    const val OPERATION_PENDING = 990009

    /** `405 METHOD NOT ALLOWED` — a write endpoint was called with GET. */
    const val METHOD_NOT_ALLOWED = 980005

    /** `标签不能为空` */
    const val LABEL_REQUIRED = 21001

    /** `暂不支持此文件格式预览` — `files/preview` on an unsupported type. */
    const val PREVIEW_UNSUPPORTED = 4011

    // ---- §14.3 upload ----
    const val UPLOAD_INVALID_TYPE = -601
    const val UPLOAD_EMPTY = -602
    const val UPLOAD_INVALID_IMAGE = -700
    const val UPLOAD_INVALID_IMAGE_FORMAT = -701
    const val UPLOAD_STOPPED = -702
    const val UPLOAD_NO_SPACE = -800
}

/**
 * Translates a 115 error code into a unified [CloudError].
 *
 * ### Why a central table
 * The reference manual documents several cases where the *only* way to tell two situations apart is
 * the code (e.g. `-800` means "no space" for uploads but `4500032` means the same for files). It
 * also documents cases where the correct reaction is very specific:
 *  - `40101060` → re-fetch the key and replay once, not "retry blindly";
 *  - `980005` → the *method* was wrong, which is a bug in this client, not a user-facing failure;
 *  - `4011` → "this format has no preview" is a normal outcome, not an error to show the user.
 *
 * Mapping in one place keeps those decisions reviewable and lets the tests assert the exact
 * translation for each documented code.
 *
 * @param message the vendor's own message, preserved for diagnostics (never shown raw to the user
 *   when it could leak internals; [CloudError.userFacingMessage] carries the unified text).
 */
internal fun mapP115Error(
    code: Int?,
    message: String?,
    httpStatus: Int? = null,
): CloudError {
    val vendorMessage = message?.takeIf { it.isNotBlank() }
    val vendorCode = code?.toString()

    // An explicit "密钥错误" arrives with errno 0 (§14.2), so the message is the only signal.
    if (vendorMessage != null && vendorMessage.contains("密钥错误")) {
        return CloudError.AdditionalVerificationRequired(
            message = "需要账号安全密钥",
            verificationKind = SAFE_KEY,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )
    }

    return when (code) {
        null -> when {
            httpStatus == 401 || httpStatus == 403 -> CloudError.TokenExpired(
                message = "授权已失效，请重新登录",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

            httpStatus != null && httpStatus >= 500 -> CloudError.ServerError(
                httpStatus = httpStatus,
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

            else -> CloudError.Unknown(
                httpStatus = httpStatus,
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
                message = vendorMessage ?: "未知错误",
            )
        }

        // ---- authentication ----
        P115ErrorCodes.WRONG_CREDENTIALS -> CloudError.NotAuthenticated(
            message = "账号或密码错误",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.TWO_FACTOR_REQUIRED, P115ErrorCodes.TWO_FACTOR_REQUIRED_ALT ->
            CloudError.AdditionalVerificationRequired(
                message = "需要二次验证",
                verificationKind = TWO_FACTOR_DEVICE,
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

        P115ErrorCodes.PUBLIC_KEY_EXPIRED -> CloudError.TokenExpired(
            message = "登录公钥已过期，正在重新获取",
            canRefresh = true,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.ACCOUNT_ABNORMAL -> CloudError.PermissionDenied(
            message = "账号状态异常，请前往 115 申诉",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.QR_EXCHANGE_REJECTED -> CloudError.InvalidArgument(
            message = "扫码登录校验失败，请重新扫码",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.QR_KEY_INVALID -> CloudError.ResourceUrlExpired(
            message = "二维码已失效，请刷新后重试",
            refreshable = true,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.SMS_RISK_CONTROL -> CloudError.AdditionalVerificationRequired(
            message = "触发短信风控，需要短信验证",
            verificationKind = SMS,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        // ---- file operations ----
        P115ErrorCodes.INVALID_PARAMETER -> CloudError.InvalidArgument(
            // This code almost always means this client sent the wrong parameter name, so the
            // message says so rather than blaming the user.
            message = "请求参数错误（115 errno 1001）",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.DELETE_NO_SELECTION, P115ErrorCodes.RESTORE_NO_SELECTION ->
            CloudError.InvalidArgument(
                message = "未选择任何文件",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

        P115ErrorCodes.INSUFFICIENT_SPACE -> CloudError.QuotaExceeded(
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.OPERATION_PENDING -> CloudError.ServerError(
            message = "操作尚未完成，请稍后重试",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.METHOD_NOT_ALLOWED -> CloudError.Unknown(
            // Documented as a GET-on-a-write-endpoint mistake, i.e. a client bug. Surfacing it
            // distinctly makes such a regression obvious in logs instead of looking like a
            // transient server problem.
            message = "请求方法错误（该接口需要 POST）",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.LABEL_REQUIRED -> CloudError.InvalidArgument(
            message = "标签不能为空",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.PREVIEW_UNSUPPORTED -> CloudError.Unsupported(
            message = "暂不支持此文件格式预览",
            capability = "preview.format",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        // ---- upload ----
        P115ErrorCodes.UPLOAD_INVALID_TYPE -> CloudError.InvalidArgument(
            message = "文件类型不允许上传",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.UPLOAD_EMPTY -> CloudError.InvalidArgument(
            message = "不能上传空文件或文件夹",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.UPLOAD_INVALID_IMAGE, P115ErrorCodes.UPLOAD_INVALID_IMAGE_FORMAT ->
            CloudError.IncompatibleResource(
                message = "图片格式不正确",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

        P115ErrorCodes.UPLOAD_STOPPED -> CloudError.Unknown(
            message = "上传已停止",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        P115ErrorCodes.UPLOAD_NO_SPACE -> CloudError.QuotaExceeded(
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        else -> fallbackFor(code, vendorMessage, httpStatus)
    }
}

/**
 * Handles codes that the reference manual does not enumerate.
 *
 * The manual explicitly documents that the response envelope is inconsistent (`state` is
 * `true/false` in some endpoints and `1/0` in others, and error payloads carry `errno` in some
 * places and `code` in others). Rather than guessing a specific meaning for an unknown number, the
 * classification uses only what HTTP tells us and preserves the vendor code for diagnosis.
 */
private fun fallbackFor(
    code: Int,
    vendorMessage: String?,
    httpStatus: Int?,
): CloudError = when {
    code >= 500 -> CloudError.ServerError(
        httpStatus = httpStatus,
        vendorCode = code.toString(),
        vendorMessage = vendorMessage,
    )

    httpStatus == 401 || httpStatus == 403 -> CloudError.TokenExpired(
        message = "授权已失效，请重新登录",
        vendorCode = code.toString(),
        vendorMessage = vendorMessage,
    )

    else -> CloudError.Unknown(
        message = vendorMessage ?: "未知错误（115 errno $code）",
        httpStatus = httpStatus,
        vendorCode = code.toString(),
        vendorMessage = vendorMessage,
    )
}

/** `verificationKind` values used by [CloudError.AdditionalVerificationRequired]. */
internal const val SAFE_KEY = "115.safe_key"
internal const val TWO_FACTOR_DEVICE = "115.two_factor"
internal const val SMS = "115.sms"
