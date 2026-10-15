package com.notdisk.core.provider

import com.notdisk.core.model.CloudCapabilities
import com.notdisk.core.model.ProviderId

/**
 * The unified cloud-drive contract.
 *
 * Every vendor integration implements exactly this interface (through its sub-interfaces). The
 * domain layer depends only on this package and on `core:model`; it must never import vendor code.
 *
 * ### Design rules baked into the signatures
 *  1. **Account context is explicit.** Every remote call takes an `AccountId`. There is no
 *     "current account" ambient state inside a provider, because two accounts of the same
 *     provider must be able to operate concurrently.
 *  2. **No throwing for expected failures.** Everything returns
 *     [com.notdisk.core.common.result.CloudResult]. Cancellation still propagates.
 *  3. **No vendor types.** No DTO, no `okhttp3.Response`, no vendor SDK object crosses this
 *     boundary.
 *  4. **Capabilities are declared, not assumed.** See [CloudCapabilities].
 */
interface CloudProvider {

    val id: ProviderId

    /** Human-readable provider name for the UI (e.g. "115 网盘"). */
    val displayName: String

    val auth: AuthProvider
    val files: FileSystemProvider
    val operations: FileOperationProvider
    val media: MediaProvider
    val preview: PreviewProvider

    /**
     * The provider's honest, current capability declaration.
     *
     * Must be cheap and side-effect free (no network call): it is read on every UI composition
     * that decides which affordances to render.
     */
    fun capabilities(): CloudCapabilities
}
