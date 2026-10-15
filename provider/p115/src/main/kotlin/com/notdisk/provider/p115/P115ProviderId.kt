package com.notdisk.provider.p115

import com.notdisk.core.model.ProviderId

/**
 * The 115 provider's stable identity.
 *
 * A top-level constant (rather than a value buried in the provider class) because it is persisted
 * with every bound account and every cache row: renaming it would orphan a user's accounts. It is
 * deliberately **not** the vendor's own name for itself — this is *our* namespace, and it must stay
 * valid even if 115 rebrands.
 */
val P115ProviderId: ProviderId = ProviderId("p115")

/** Human-readable name for the accounts UI. */
const val P115_DISPLAY_NAME = "115 网盘"
