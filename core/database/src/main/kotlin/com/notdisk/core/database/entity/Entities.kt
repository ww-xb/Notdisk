package com.notdisk.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A bound account, as stored locally.
 *
 * ### What is deliberately absent
 * **No credentials.** 115's `USERSESSIONID`/`SEID`/`KID` and any future provider's token live in
 * encrypted storage behind `P115CredentialStore`; this table is a plain Room table and is therefore
 * unencrypted and (on some devices) backed up. Putting a long-lived bearer token here would be the
 * single easiest way to leak an account, so the split is enforced by the type: there is simply no
 * column to put it in.
 *
 * ### Why the primary key is a composite of strings
 * `AccountId` is a locally-generated UUID and `ProviderId` is our own namespace (not the vendor's
 * name for itself). Neither is a long, so both are stored as `TEXT`, and the primary key is the
 * pair — which also makes "two accounts of the same provider" representable without a surrogate key.
 */
@Entity(
    tableName = "accounts",
    primaryKeys = ["accountId", "providerId"],
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["remoteUserId"]),
    ],
)
data class AccountEntity(
    val accountId: String,
    val providerId: String,
    /** Vendor-side user id. Diagnostic + de-duplication only; never used as the local identity. */
    val remoteUserId: String?,
    val displayName: String,
    val avatarUrl: String?,
    /** Serialised `AuthState` name. Stored as text so the enum can evolve without a migration. */
    val authState: String,
    val membershipLabel: String?,
    val membershipExpiresAt: Long?,
    val addedAt: Long,
    val lastVerifiedAt: Long?,
)

/**
 * Cached metadata for one file or folder.
 *
 * ### Why a separate table rather than a `FileItem` blob
 * The UI sorts, filters and searches locally while offline, and it does so on individual columns.
 * A serialised blob would force every one of those operations to deserialise the whole row.
 *
 * ### What is deliberately absent
 * **No URL of any kind.** Thumbnails, download links and stream URLs are short-lived signed
 * resources; caching one would produce a broken image plus a credential-bearing URL at rest. The
 * `thumbnailHash` column stores only the *content hash* the vendor groups thumbnails by, so the URL
 * can be re-resolved on demand.
 *
 * ### Scoping
 * Every row is scoped by `(accountId, providerId)`. Two accounts of the same provider may hold
 * different files, and a bare file id is never globally unique (see `FileId`'s KDoc).
 */
@Entity(
    tableName = "file_metadata",
    primaryKeys = ["accountId", "providerId", "fileId"],
    indices = [
        Index(value = ["accountId", "providerId", "parentId"]),
        Index(value = ["accountId", "providerId", "name"]),
        Index(value = ["cachedAt"]),
    ],
)
data class FileMetadataEntity(
    val accountId: String,
    val providerId: String,
    /** The provider's opaque file handle, exactly as produced by its codec. */
    val fileId: String,
    val parentId: String?,
    val name: String,
    /** `FILE` or `FOLDER`. */
    val type: String,
    val mimeType: String?,
    val extension: String?,
    val size: Long?,
    val createdTime: Long?,
    val modifiedTime: Long?,
    /** Content hash used to re-resolve a thumbnail; never a URL. */
    val thumbnailHash: String?,
    val thumbnailSizeHint: String?,
    /** Serialised per-item capabilities. */
    val capabilitiesJson: String,
    /** Namespaced vendor extras, serialised. Kept small on purpose. */
    val extensionsJson: String,
    /** When this row was written, epoch millis. Drives staleness decisions. */
    val cachedAt: Long,
)

/**
 * A recently opened file, for the "recent" list.
 *
 * Kept as its own table rather than a boolean on [FileMetadataEntity] because the two have
 * different lifetimes: metadata expires and is evicted, whereas "the user opened this" is a durable
 * user-facing fact that must survive a cache clear.
 */
@Entity(
    tableName = "recent_files",
    primaryKeys = ["accountId", "providerId", "fileId"],
    indices = [Index(value = ["openedAt"])],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["accountId", "providerId"],
            childColumns = ["accountId", "providerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RecentFileEntity(
    val accountId: String,
    val providerId: String,
    val fileId: String,
    val name: String,
    val isFolder: Boolean,
    val mimeType: String?,
    val size: Long?,
    /** `BROWSE`, `PLAY` or `PREVIEW` — so the UI can show why it is in the list. */
    val kind: String,
    val openedAt: Long,
)

/**
 * A starred/favourite item.
 *
 * Stores the vendor id plus enough display data to render a list without a network round trip, so
 * the favourites screen works offline.
 */
@Entity(
    tableName = "favourites",
    primaryKeys = ["accountId", "providerId", "fileId"],
    indices = [Index(value = ["addedAt"])],
)
data class FavouriteEntity(
    val accountId: String,
    val providerId: String,
    val fileId: String,
    val name: String,
    val isFolder: Boolean,
    val mimeType: String?,
    val size: Long?,
    val addedAt: Long,
)

/**
 * A transfer task as stored locally.
 *
 * ### Why there is no URL column
 * A download link from 115 is a short-lived signed URL (§7.1 of `API_REFERENCE.md`), and a media URL
 * likewise. Persisting one would mean a stale credential at rest *and* a transfer that fails on
 * resume because the link died. The row therefore stores only the stable
 * `(providerId, accountId, fileId)` triple; the transfer layer re-resolves a fresh link whenever it
 * (re)starts.
 *
 * ### Why `sourceUri`/`destinationPath` are stored
 * `sourceUri` is an Android `content://` URI chosen by the user through the Storage Access
 * Framework — a local reference, not a credential. `destinationPath` is a plain local path. Both
 * are safe and both are needed to resume after a process death, which is exactly the case the
 * brief's §12 asks to survive.
 */
@Entity(
    tableName = "transfer_tasks",
    primaryKeys = ["taskId"],
    indices = [
        Index(value = ["accountId", "providerId"]),
        Index(value = ["status"]),
        Index(value = ["updatedAt"]),
    ],
)
data class TransferTaskEntity(
    val taskId: String,
    val accountId: String,
    val providerId: String,
    val fileId: String,
    /** `UPLOAD` or `DOWNLOAD`. */
    val direction: String,
    val fileName: String,
    val totalBytes: Long?,
    val transferredBytes: Long,
    val status: String,
    val sourceUri: String?,
    val destinationPath: String?,
    val errorMessage: String?,
    val autoRetryable: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)
