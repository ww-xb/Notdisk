package com.notdisk.domain.files

import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.model.FilePath
import com.notdisk.core.model.ProviderId
import com.notdisk.core.provider.ProviderRegistry
import com.notdisk.domain.internal.withProvider

/**
 * Resolves the full root→file breadcrumb chain for one item.
 *
 * ### Why this is not just a UI nicety
 * A path is an *identity statement in human terms*, and it is the only one users can act on. When
 * a file is open from a deep link, a search result, or a "recent files" list, the app knows a
 * [FileId] and nothing else; "文件不存在" is unhelpful, whereas `我的文件/电视剧/第一季` tells the
 * user where to look. The same data drives the "reveal in folder" affordance and the confirmation
 * text for a destructive action ("确定删除 我的文件/…/x.mkv 吗？") — asking a user to confirm
 * deletion of a bare opaque id is a UX defect with data-loss consequences.
 *
 * ### Not every provider can answer cheaply
 * [com.notdisk.core.model.FileSystemCapabilities.getPath] declares whether a provider has a
 * real path endpoint (115's `/files` response carries the vendor's own `path` array) or must
 * reconstruct the chain by walking parents with repeated `getFile` calls. Providers without a path
 * endpoint are **not** gated here, because "reconstruct by walking" is a legitimate implementation
 * and refusing it would remove a working feature — but callers that render a path on *every list
 * row* should read the capability first and skip the call when it is
 * [com.notdisk.core.model.CapabilitySupport.UNSUPPORTED], rather than issuing N remote calls
 * while the user scrolls.
 *
 * The returned [FilePath] is ordered root → file, and its first segment may carry a `null`
 * [com.notdisk.core.model.FilePathSegment.id] for the synthetic root (see
 * [GetRootUseCase] for why the root is synthetic). Callers must tolerate that: the root is not
 * addressable as a file on every vendor.
 */
class GetFilePathUseCase(
    private val registry: ProviderRegistry,
) {

    /**
     * @param providerId the integration that issued [fileId].
     * @param accountId the local account id.
     * @param fileId the item whose breadcrumb is wanted.
     */
    suspend operator fun invoke(
        providerId: ProviderId,
        accountId: AccountId,
        fileId: FileId,
    ): CloudResult<FilePath> =
        registry.withProvider(providerId) { provider -> provider.files.getPath(accountId, fileId) }
}
