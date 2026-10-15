package com.notdisk.provider.mock

import com.notdisk.core.model.AccountId
import com.notdisk.core.model.FileId
import com.notdisk.core.provider.testing.ProviderContractFixture

/**
 * Ground truth for the shared contract suite, expressed against [MockTree].
 *
 * The fixture's job is to point the *generic* rules at concrete nodes. If a Mock tree node is
 * renamed, this file is the single place that needs updating — the rules themselves never mention
 * a specific id.
 */
internal class MockContractFixture(
    override val provider: MockCloudProvider,
    override val accountId: AccountId,
) : ProviderContractFixture {

    /** `大目录` has 37 generated children, which is enough to require several pages at size 5. */
    override val knownFolderId: FileId = FileId(MockTree.LARGE_ID)

    override val nonExistentFileId: FileId = FileId(MockTree.MISSING_ID)

    override val searchKeywordThatMatchesSomething: String = "readme"

    override val searchKeywordThatMatchesNothing: String = "zzz-no-such-file-zzz"

    override val paginationPageSize: Int = 5

    override val maxPaginationPages: Int = 20

    override val readableContentUriOrNull: String = "content://com.notdisk.test/document/1"

    override val knownVideoFileId: FileId = FileId("mock:f-video")

    override val knownImageFileId: FileId = FileId("mock:f-image")

    override val knownNonMediaFileId: FileId = FileId("mock:f-readme")

    override val knownUnpreviewableFileId: FileId = FileId("mock:f-opaque")

    /**
     * A folder that lists successfully but contains nothing: the shared suite uses it to prove
     * "empty" is a successful page rather than a `NotFound`.
     */
    val emptyFolderId: FileId = FileId(MockTree.EMPTY_ID)

    /** A folder whose listing must fail with `PermissionDenied`, not return an empty page. */
    val restrictedFolderId: FileId = FileId(MockTree.RESTRICTED_ID)
}
