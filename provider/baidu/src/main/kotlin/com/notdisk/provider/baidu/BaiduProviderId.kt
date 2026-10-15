package com.notdisk.provider.baidu

import com.notdisk.core.model.ProviderId

/**
 * The Baidu Netdisk (百度网盘) provider's stable identity.
 *
 * A top-level constant rather than a value buried in the provider class, because it is persisted
 * with every bound account and every cache row: renaming it would orphan a user's accounts. It is
 * deliberately **not** the vendor's own name for itself — this is *our* namespace, and it must stay
 * valid even if Baidu rebrands the product.
 */
val BaiduProviderId: ProviderId = ProviderId("baidu")

/** Human-readable name for the accounts UI. */
const val BAIDU_DISPLAY_NAME = "百度网盘"
