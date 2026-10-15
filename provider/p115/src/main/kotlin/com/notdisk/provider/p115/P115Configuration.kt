package com.notdisk.provider.p115

import com.notdisk.core.model.CapabilitySupport

/**
 * Configuration the 115 integration needs from outside the source tree.
 *
 * The brief's rule 7 requires an explicit configuration entry point rather than fabricated values.
 * Everything here is **absent by default**, and the provider reports the gap through
 * [P115Configuration.notes] so the UI can tell the user exactly what is missing instead of offering
 * a button that cannot work.
 */
data class P115Configuration(
    /**
     * OAuth `client_id`, if the project is ever issued one.
     *
     * `null` means the OAuth flow is unavailable — which is the honest state, because §2.4's token
     * endpoint is marked unverified and this project has no registered app id.
     */
    val oauthClientId: String? = null,
    /** OAuth redirect URI paired with [oauthClientId]. */
    val oauthRedirectUri: String? = null,
    /**
     * Preferred page size for listings.
     *
     * Defaults to 40 because §3.1 records the web front-end using 30/40; a value in that range is
     * known to be accepted. It is configurable so a future change can be made without a code edit.
     */
    val pageSize: Int = 40,
) {
    val oauthConfigured: Boolean
        get() = !oauthClientId.isNullOrBlank() && !oauthRedirectUri.isNullOrBlank()

    /** Capability of the OAuth flow given the current configuration. */
    val oauthSupport: CapabilitySupport
        get() = if (oauthConfigured) CapabilitySupport.UNVERIFIED else CapabilitySupport.UNSUPPORTED

    /** Human-readable notes surfaced in the accounts/settings UI. */
    val notes: List<String>
        get() = buildList {
            if (!oauthConfigured) {
                add(
                    "OAuth 登录未启用：需要 115 开发者 app_id 与回调地址。" +
                        "当前仅提供官方未公开注册入口的扫码登录（不需要开发者凭据）。",
                )
            }
        }
}

