package com.notdisk.provider.baidu.api

import com.notdisk.core.common.error.CloudError

/**
 * The `errno` values the reference material documents for Baidu Netdisk.
 *
 * Kept as named constants rather than inline numbers because the same number carries **different
 * meanings in different endpoint families** — the reference material is explicit about this, and it
 * is the single most important thing to get right in this file:
 *
 * | `errno` | share family (§6.6) | filemanager family (§12.2) | recycle family (§12.3) |
 * |---|---|---|---|
 * | `111` | — | 当前还有未完成的任务 | 您还有任务未完成，请稍后重试 |
 * | `-1`  | 违规内容被封禁 | 用户名和密码验证失败 | — |
 *
 * So there is deliberately **no single global code→message table**. [mapBaiduError] takes the
 * family as an argument, and a code that is unknown in one family falls through to the shared
 * classification rather than borrowing another family's wording.
 */
internal object BaiduErrorCodes {

    // ---- §1.3 / §12.1 cross-cutting ----
    /** `成功`. The **only** success value (§0.3: the interceptor tests `0 !== errno`). */
    const val OK = 0

    /**
     * `未登录 / 会话失效`. The vendor's own interceptor redirects to `/login` on this code, and it
     * appears in *every* error table. It is therefore the authoritative re-authentication trigger.
     */
    const val NOT_LOGGED_IN = -6

    /** `参数错误 / 失败重试` — documented as the most common code of all. */
    const val INVALID_PARAMETER = 2

    /** `内容违规 / 操作失败`. */
    const val CONTENT_VIOLATION = -1

    /** `文件已存在 / 分享已过期`. */
    const val ALREADY_EXISTS_OR_SHARE_EXPIRED = -8

    /** `文件不存在 / 提取码错误`. */
    const val NOT_FOUND_OR_WRONG_PASSWORD = -9

    /** `空间不足`. */
    const val INSUFFICIENT_SPACE = -10

    /** `父目录不存在`. */
    const val PARENT_NOT_FOUND = -11

    /** `部分失败` — the batch partially applied. Merged into [PARTIAL_FAILURE_DUPLICATE]. */
    const val PARTIAL_FAILURE = -12

    /** `验证码错误`. */
    const val CAPTCHA_WRONG = -19

    /** `需要验证码` → `GET /api/getcaptcha?prod=<pan|mbox|share>`. */
    const val CAPTCHA_REQUIRED = -20

    /** `未绑定手机号` — measured on share creation, and required for private shares. */
    const val PHONE_NOT_BOUND = -21

    /** `共享文件不允许该操作`. */
    const val SHARED_FILE_LOCKED = -22

    /** `空间不足` (the second spelling). */
    const val INSUFFICIENT_SPACE_ALT = -32

    /** `提取码尝试次数耗尽`. */
    const val PASSWORD_ATTEMPTS_EXHAUSTED = -62

    /** `含病毒`. */
    const val VIRUS_DETECTED = -70

    /** `重名（部分失败）`. */
    const val DUPLICATE_PARTIAL = 12

    /** `页面已过期（签名失效）`. */
    const val PAGE_EXPIRED = 113

    /** `分享不存在`. */
    const val SHARE_NOT_FOUND = 116

    /** `无下载权限`. */
    const val NO_DOWNLOAD_PERMISSION = 118

    /** `参数/路径错误`. */
    const val INVALID_PATH = 120

    /** `选择文件过多`. */
    const val TOO_MANY_FILES = 121

    /**
     * `需要身份验证` — the response carries `authwidget {safetpl, saferand, safesign}` and
     * `verify_scene`. Measured on `/api/filemanager` (§3.6).
     */
    const val IDENTITY_VERIFICATION_REQUIRED = 132

    /**
     * `需要观看广告` — the response carries `adToken` / `adTime` / `ltime`. Measured on
     * `/api/streaming` (§7.3).
     */
    const val AD_REQUIRED = 133

    /** `操作失败`. */
    const val OPERATION_FAILED = 445

    /** `需付费引导` (both variants) — a quota/entitlement limit, not a transient failure. */
    const val NEED_PURCHASE = 3010
    const val NEED_PURCHASE_ALT = 3011

    /** `分享下载需验证` — measured as an anonymous `/api/sharedownload` response. */
    const val SHARE_DOWNLOAD_NEEDS_VERIFY = 9019

    /** `操作过于频繁` — the vendor's rate-limit signal. */
    const val TOO_FREQUENT = 31034

    /** `user not exists` on the legacy PCS surface. */
    const val PCS_USER_NOT_EXISTS = 31045

    /** `原压缩包不存在 / 文件已不存在`. */
    const val FILE_GONE = 31066

    /** `有未完成任务`. */
    const val TASK_UNFINISHED = 31171

    /** `需要压缩包密码 / 解压密码错误`. */
    const val ARCHIVE_PASSWORD_REQUIRED = 31180

    /** `no authority` on the membership surface. */
    const val MEMBERSHIP_NO_AUTHORITY = 36003

    /** `无文件夹管理权限`. */
    const val NO_FOLDER_PERMISSION = 90003

    // ---- §7.3 / §3.8 playback and task codes ----
    /** `视频正在转码中` — the vendor's player retries this code 30 times, 1 s apart. */
    const val TRANSCODING_IN_PROGRESS = 31341

    /** `视频转码失败`. */
    const val TRANSCODING_FAILED = 31346

    /** `该视频无法播放`. */
    const val VIDEO_UNPLAYABLE = 31339

    /** `文件违规，已根据相关法律法规予以屏蔽`. */
    const val VIDEO_BLOCKED = 31391

    /** `type 参数错误` (HTTP 400) — an unsupported rendition string. */
    const val BAD_STREAMING_TYPE = 31023
}

/**
 * Which endpoint family produced an error.
 *
 * Required because [BaiduErrorCodes] documents that the same numeric code is worded differently per
 * family, and because the *correct user-facing action* differs: a share failure with `-1` means the
 * content was banned, whereas a filemanager failure with `-1` means the account's credentials were
 * rejected.
 */
internal enum class BaiduErrorFamily {
    /** `/api/list`, `/api/filemetas`, `/api/categorylist`, … — the generic envelope. */
    GENERAL,

    /** `/api/filemanager`, `/api/create`, `/api/taskquery`. */
    FILE_MANAGER,

    /** `/api/recycle/…`. */
    RECYCLE,

    /** `/share/…` and `/api/sharedownload`. */
    SHARE,

    /** `/api/streaming`, `/richsearch/autosubtitle/common`. */
    MEDIA,

    /** `/api/gettemplatevariable`, `/api/loginStatus`, `/api/user/getinfo`. */
    AUTH,
}

/**
 * Translates a Baidu `errno` into a unified [CloudError].
 *
 * ### Why the classification is centralized
 * Several codes demand a *specific* reaction that is easy to get wrong locally:
 *  - `-6` must produce a re-authentication error, not a generic failure: otherwise a session that
 *    expired overnight renders as "未知错误" and the user is never offered a way back in;
 *  - `132` carries an `authwidget` that only an interactive verification flow can consume, so it is
 *    neither "permission denied" nor retryable;
 *  - `133` is **not an error at all** for playback — it is phase one of a documented two-phase
 *    handshake — but the generic mapper still has to name it so callers can special-case it;
 *  - `31341` is explicitly documented as *retryable* (the vendor's own player retries it 30 times),
 *    which is the opposite of `31034`'s "slow down".
 *
 * @param family which endpoint family raised the code; see [BaiduErrorFamily].
 * @param code the vendor `errno`. A **non-numeric or absent** value is normalized to `-1` by the
 *   envelope parser before it reaches here, matching the vendor's own interceptor
 *   (`if (isNaN(i)) i = -1`), so `null` means "the response truly carried no code".
 * @param message the vendor's `show_msg` (the user-facing field) or `errmsg`.
 */
internal fun mapBaiduError(
    code: Int?,
    message: String?,
    httpStatus: Int? = null,
    family: BaiduErrorFamily = BaiduErrorFamily.GENERAL,
): CloudError {
    val vendorMessage = message?.takeIf { it.isNotBlank() }
    val vendorCode = code?.toString()

    // A transport-level failure with no business code: classify on HTTP alone rather than guessing
    // a vendor meaning.
    if (code == null) {
        return when {
            httpStatus == 401 || httpStatus == 403 -> CloudError.TokenExpired(
                message = "登录信息已失效，请重新登录百度网盘",
                vendorMessage = vendorMessage,
            )

            httpStatus != null && httpStatus >= 500 -> CloudError.ServerError(
                httpStatus = httpStatus,
                vendorMessage = vendorMessage,
            )

            else -> CloudError.Unknown(
                httpStatus = httpStatus,
                vendorMessage = vendorMessage,
                message = vendorMessage ?: "未知错误",
            )
        }
    }

    return when (code) {
        BaiduErrorCodes.OK -> CloudError.Unknown(
            // `mapBaiduError(0)` means a caller classified a *successful* envelope as an error. That
            // is a bug in this client, so it is named rather than silently swallowed.
            message = "内部错误：成功响应被当作错误处理",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        // ---- cross-cutting, family-independent ----
        BaiduErrorCodes.NOT_LOGGED_IN -> CloudError.TokenExpired(
            message = "登录信息已失效，请重新登录百度网盘",
            // Baidu has no documented silent refresh: the session cookie must be replaced by the
            // user. Claiming a refreshable session would make the UI retry forever.
            canRefresh = false,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.CONTENT_VIOLATION -> when (family) {
            // Documented: "违规内容被封禁" for shares, but "用户名和密码验证失败" for filemanager.
            BaiduErrorFamily.SHARE -> CloudError.PermissionDenied(
                message = "文件因违规内容被封禁，无法分享",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

            BaiduErrorFamily.FILE_MANAGER -> CloudError.NotAuthenticated(
                message = "登录凭证无效，请重新登录",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

            else -> CloudError.Unknown(
                message = vendorMessage ?: "操作失败（errno -1）",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )
        }

        BaiduErrorCodes.INVALID_PARAMETER -> CloudError.InvalidArgument(
            // The vendor documents `2` as "参数错误" and it is by far the most common code, which
            // almost always means this client built the request wrong. Saying so is more useful than
            // blaming the user's input.
            message = vendorMessage ?: "请求参数错误（百度 errno 2）",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.ALREADY_EXISTS_OR_SHARE_EXPIRED -> when (family) {
            BaiduErrorFamily.SHARE -> CloudError.Deleted(
                message = "分享已过期或已被取消",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

            else -> CloudError.InvalidArgument(
                message = vendorMessage ?: "目标位置已存在同名文件",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )
        }

        BaiduErrorCodes.NOT_FOUND_OR_WRONG_PASSWORD -> when (family) {
            BaiduErrorFamily.SHARE -> CloudError.PermissionDenied(
                message = "提取码错误",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

            else -> CloudError.NotFound(
                message = vendorMessage ?: "文件不存在",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )
        }

        BaiduErrorCodes.INSUFFICIENT_SPACE,
        BaiduErrorCodes.INSUFFICIENT_SPACE_ALT,
        31116,
        36009,
        -> CloudError.QuotaExceeded(vendorCode = vendorCode, vendorMessage = vendorMessage)

        BaiduErrorCodes.PARENT_NOT_FOUND -> CloudError.NotFound(
            message = "父目录不存在",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.PARTIAL_FAILURE,
        BaiduErrorCodes.DUPLICATE_PARTIAL,
        -> CloudError.InvalidArgument(
            // Both codes mean "some items were already present at the destination". A batch that
            // partially applied is *not* a transport failure; the caller is expected to report it
            // per item, which is why this maps to InvalidArgument rather than to a retryable error.
            message = vendorMessage ?: "部分文件已存在于目标文件夹",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.CAPTCHA_REQUIRED, BaiduErrorCodes.CAPTCHA_WRONG ->
            CloudError.AdditionalVerificationRequired(
                message = "需要输入验证码",
                verificationKind = CAPTCHA,
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

        BaiduErrorCodes.IDENTITY_VERIFICATION_REQUIRED ->
            CloudError.AdditionalVerificationRequired(
                message = "该操作需要先完成身份验证",
                verificationKind = IDENTITY_VERIFICATION,
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

        BaiduErrorCodes.PHONE_NOT_BOUND -> CloudError.PermissionDenied(
            message = "该功能需要先在百度账号中绑定手机号",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.SHARED_FILE_LOCKED -> CloudError.PermissionDenied(
            message = "共享文件不允许该操作",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.PASSWORD_ATTEMPTS_EXHAUSTED -> CloudError.PermissionDenied(
            message = "提取码尝试次数已用尽，请稍后再试",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.VIRUS_DETECTED, BaiduErrorCodes.VIDEO_BLOCKED -> CloudError.PermissionDenied(
            message = "文件因违规或安全问题被限制",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.PAGE_EXPIRED -> CloudError.ResourceUrlExpired(
            // §5.3 measures this exact code for an expired download signature.
            message = "链接签名已过期，请重新获取",
            refreshable = true,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.SHARE_NOT_FOUND -> CloudError.NotFound(
            message = "分享不存在或已被删除",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.NO_DOWNLOAD_PERMISSION -> CloudError.PermissionDenied(
            message = "没有下载权限",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.INVALID_PATH -> CloudError.NotFound(
            message = vendorMessage ?: "路径无效或不存在",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.TOO_MANY_FILES -> CloudError.InvalidArgument(
            message = "选择的文件过多，请分批操作",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.AD_REQUIRED -> CloudError.Unsupported(
            // Not a capability hole: it is phase one of the documented playback handshake. It is
            // surfaced as `Unsupported` with a named capability so the *caller* can decide whether
            // to complete the handshake, and so the shared contract suite can see that this is a
            // narrow hole rather than a lie about `videoStreaming`.
            message = "百度网盘要求先获取播放令牌（errno 133）",
            capability = "media.streaming.adToken",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.OPERATION_FAILED -> CloudError.Unknown(
            message = vendorMessage ?: "操作失败",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.NEED_PURCHASE, BaiduErrorCodes.NEED_PURCHASE_ALT ->
            CloudError.QuotaExceeded(
                message = "该操作超出当前会员权益，需要开通会员",
                vendorCode = vendorCode,
                vendorMessage = vendorMessage,
            )

        BaiduErrorCodes.SHARE_DOWNLOAD_NEEDS_VERIFY -> CloudError.AdditionalVerificationRequired(
            message = "分享下载需要先通过验证",
            verificationKind = CAPTCHA,
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.TOO_FREQUENT -> CloudError.RateLimited(
            message = "操作过于频繁，请稍后再试",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.PCS_USER_NOT_EXISTS, BaiduErrorCodes.MEMBERSHIP_NO_AUTHORITY,
        BaiduErrorCodes.NO_FOLDER_PERMISSION,
        -> CloudError.PermissionDenied(
            message = vendorMessage ?: "权限不足",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.FILE_GONE -> CloudError.Deleted(
            message = "文件已不存在",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.TASK_UNFINISHED -> CloudError.ServerError(
            // Documented as "当前还有未完成的任务，需完成后才能操作" — a transient server-side state,
            // so it is retryable rather than a permanent rejection.
            message = "还有未完成的任务，请稍后重试",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.ARCHIVE_PASSWORD_REQUIRED -> CloudError.PermissionDenied(
            message = "压缩包需要密码",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        // ---- §7.3 media ----
        BaiduErrorCodes.TRANSCODING_IN_PROGRESS -> CloudError.ServerError(
            // The vendor's own player retries this 30 times, 1 s apart, so it is explicitly
            // transient. Reporting it as a permanent failure would be wrong.
            message = "视频正在转码中，请稍后重试",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.TRANSCODING_FAILED -> CloudError.IncompatibleResource(
            message = "视频转码失败",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.VIDEO_UNPLAYABLE -> CloudError.IncompatibleResource(
            message = "该视频无法播放",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        BaiduErrorCodes.BAD_STREAMING_TYPE -> CloudError.InvalidArgument(
            message = "不支持的清晰度参数",
            vendorCode = vendorCode,
            vendorMessage = vendorMessage,
        )

        else -> fallbackFor(code, vendorMessage, httpStatus)
    }
}

/**
 * Handles codes the reference material does not enumerate.
 *
 * The material is explicit that its error tables are **incomplete** (§12.1 lists 40-odd codes while
 * the vendor's own filemanager table has more, and several families list only ranges). Rather than
 * guessing a specific meaning, the classification uses only what the number and HTTP say, and
 * preserves the raw code for diagnosis.
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
        message = "登录信息已失效，请重新登录百度网盘",
        vendorCode = code.toString(),
        vendorMessage = vendorMessage,
    )

    else -> CloudError.Unknown(
        message = vendorMessage ?: "未知错误（百度 errno $code）",
        httpStatus = httpStatus,
        vendorCode = code.toString(),
        vendorMessage = vendorMessage,
    )
}

/** `verificationKind` values used by [CloudError.AdditionalVerificationRequired]. */
internal const val CAPTCHA = "baidu.captcha"
internal const val IDENTITY_VERIFICATION = "baidu.identity_verification"
