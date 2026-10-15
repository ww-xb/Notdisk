package com.notdisk.domain.files

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.invalidArgument
import com.notdisk.domain.internal.withProvider

/**
 * Resolves a human-readable path such as `我的文件/电视剧/第一季` into a [FileId].
 *
 * ### Who needs this, given the app has a file browser
 * Three callers, none of which have a [FileId] to start from:
 *
 *  - **deep links / shortcuts.** A home-screen shortcut or a `notdisk://` URI carries a path
 *    (readable, shareable, survives a cache wipe) rather than an opaque id. Note that an id is
 *    *also* not durable across a re-bind of the same vendor account on some vendors, so a path is
 *    the more honest thing to put in a link.
 *  - **restore / import flows.** "Put it back where it was" needs `originalParentName` (a path
 *    fragment) turned back into an addressable id.
 *  - **tests and fixtures**, per the provider contract's own note.
 *
 * ### The validation this use case adds over the provider method
 * Path handling is where a client most often grows a **path traversal** bug, and the domain layer
 * is the last place that can catch it cheaply. The provider is the authority on how a path is
 * segmented (115 addresses folders by `cid`, others may accept `/` literally in a folder name), so
 * this use case deliberately does **not** split or normalise the string — doing so would break
 * vendors whose names legitimately contain the separator. What it rejects is narrower and
 * unambiguous:
 *
 *  - a blank path (the user pressed "go" on an empty field — a normal action, not a crash, so it
 *    becomes [com.notdisk.core.common.error.CloudError.InvalidArgument] rather than reaching a
 *    provider that would either 404 or, worse, resolve to the root);
 *  - a path containing a NUL byte, which cannot be part of any real name and is the classic way to
 *    truncate a string in a layer that uses C strings underneath (the provider's HTTP stack, a
 *    native SDK). Refusing it here means no provider has to think about it.
 *
 * A `..` segment is **not** rejected, deliberately. "Parent" is a legitimate navigation concept and
 * whether `..` means anything at all is a property of the provider's path grammar, not of this
 * layer; a client-side ban would either be wrong for a vendor that treats it literally (a folder
 * genuinely named `..`) or give false comfort, since the traversal that matters — escaping the
 * account's root — is enforced server-side by the vendor's own authorisation. The use case's job is
 * to make the call well-formed, not to re-implement vendor authorisation.
 */
class ResolvePathUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration to resolve against.
     * @param accountId the local account id.
     * @param path the vendor-facing human path. Segmented by the provider, not by this layer.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        path: String,
    ): CloudResult<FileId> {
        if (path.isBlank()) {
            return invalidArgument("路径", "不能为空")
        }
        if (path.contains('\u0000')) {
            return invalidArgument("路径", "包含非法字符")
        }

        return registry.withProvider(providerId) { provider ->
            provider.files.resolvePath(accountId, path)
        }
    }
}
