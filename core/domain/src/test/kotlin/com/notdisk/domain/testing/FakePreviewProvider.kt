package com.notdisk.domain.testing

import com.notdisk.core.common.error.CloudError
import com.notdisk.core.common.result.CloudResult
import com.notdisk.core.model.AccountId
import com.notdisk.core.model.CloudFileKey
import com.notdisk.core.model.FileId
import com.notdisk.core.model.PreviewKind
import com.notdisk.core.model.PreviewResource
import com.notdisk.core.model.PreviewResult
import com.notdisk.core.model.ThumbnailRef
import com.notdisk.core.provider.PreviewHint
import com.notdisk.core.provider.PreviewProvider
import com.notdisk.core.provider.PreviewResourceOrNull

/**
 * Configurable [PreviewProvider] fake.
 *
 * ### Why the hint is recorded verbatim
 * The hint-forwarding test is the reason this fake exists in its current shape. `PreviewHint` is
 * an *advisory, best-effort* input — a provider may ignore it entirely — which makes it exactly the
 * kind of parameter that gets silently dropped during a refactor: nothing fails, the app just gets
 * slower, and the extra round trip per list row is invisible in tests but obvious in production.
 *
 * Recording the hint as received (rather than a summary of it) lets a test assert that a hint
 * carrying only a `name` arrives with that name intact, including the case where `extension` and
 * `mimeType` are unset. A test written against a reconstructed hint could not tell the difference
 * between "forwarded faithfully" and "forwarded after losing the null fields".
 *
 * ### Why `previewResult` is a full override
 * Preview is the one surface where a *successful* result carries a negative outcome
 * ([PreviewKind.Unsupported]). Testing that path requires the fake to return a concrete
 * `PreviewResult`, not a failure — so the fake exposes a direct override rather than only failure
 * hooks.
 */
class FakePreviewProvider(private val owner: FakeCloudProvider) : PreviewProvider {

    var previewFailure: CloudError? = null
    var thumbnailFailure: CloudError? = null

    /** When set, returned verbatim; otherwise a default image result is synthesised. */
    var previewResult: PreviewResult? = null

    /** What [thumbnail] returns inside its success. `null` = "no thumbnail for this item". */
    var thumbnailResource: PreviewResource? = PreviewResource(url = "https://cdn.invalid/thumb")

    override suspend fun preview(
        accountId: AccountId,
        fileId: FileId,
        resolveAccess: Boolean,
        hint: PreviewHint,
    ): CloudResult<PreviewResult> {
        owner.record(
            ProviderSurface.PREVIEW,
            "preview",
            "accountId" to accountId,
            "fileId" to fileId,
            "resolveAccess" to resolveAccess,
            // Recorded as the object itself so a test can assert every field, including nulls.
            "hint" to hint,
        )
        previewFailure?.let { return CloudResult.Failure(it) }
        previewResult?.let { return CloudResult.Success(it) }

        return CloudResult.Success(
            PreviewResult(
                key = CloudFileKey(owner.id, accountId, fileId),
                kind = PreviewKind.Image(originalAvailable = true),
                mimeType = hint.mimeType ?: "image/jpeg",
                resource = if (resolveAccess) {
                    PreviewResource(url = "https://cdn.invalid/preview/$fileId")
                } else {
                    null
                },
            ),
        )
    }

    override suspend fun thumbnail(
        accountId: AccountId,
        fileId: FileId,
        ref: ThumbnailRef?,
    ): CloudResult<PreviewResourceOrNull> {
        owner.record(
            ProviderSurface.PREVIEW,
            "thumbnail",
            "accountId" to accountId,
            "fileId" to fileId,
            "ref" to ref,
        )
        thumbnailFailure?.let { return CloudResult.Failure(it) }
        return CloudResult.Success(
            PreviewResourceOrNull(
                key = CloudFileKey(owner.id, accountId, fileId),
                resource = thumbnailResource,
            ),
        )
    }
}
