package com.notdisk.provider.baidu

/**
 * Configuration the Baidu Netdisk integration needs from outside the source tree.
 *
 * ### Why this type is nearly empty, and that is the point
 * The 115 integration needed a configuration entry point because its OAuth flow requires a developer
 * `app_id` this project has not been issued. Baidu is different in a way worth recording: the
 * **only** flow this build implements — [com.notdisk.core.model.AuthFlowKind.MANUAL_CREDENTIAL] —
 * needs nothing from the project at all. The user supplies their own browser session cookie, and
 * every request then identifies itself with the *web client's own* public `app_id` (`250528`,
 * §1.2), which is not a secret and not issued per-developer.
 *
 * So there is no credential gap to report, and no `notes` telling the user something is missing.
 * What remains configurable is sizing, which is a deployment concern rather than a capability one.
 *
 * ### What is deliberately NOT here
 *  - **No `app_id` field.** Switching to the enterprise (`24029990`) or team-space (`118511220`)
 *    product lines is not a configuration toggle: §13.2 item 5 records that mixing them returns
 *    permission errors, and the two lines have different endpoints, different response shapes and
 *    different error tables. Exposing an `appId` string here would invite exactly the mix-up the
 *    reference material warns about, so a future enterprise integration is a *new provider*, not a
 *    new field.
 *  - **No cookie or token field.** Credentials belong in the encrypted store, never in configuration
 *    that could be logged or committed.
 */
data class BaiduConfiguration(
    /**
     * Preferred page size for listings.
     *
     * Defaults to 100 because §3.2 documents `num` defaulting to 100 in the vendor's own client
     * (`{order:"time", desc:1, dir:"/", num:100, page:1}`) and the capture shows 100 in use. It is
     * configurable so a future change needs no code edit.
     */
    val pageSize: Int = 100,
) {
    /**
     * Human-readable notes surfaced in the accounts/settings UI.
     *
     * Empty, and that is a factual statement rather than an oversight: nothing is missing for the
     * flow this provider offers. See the class KDoc.
     */
    val notes: List<String> get() = emptyList()
}
