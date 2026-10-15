package com.notdisk.core.model

import kotlin.jvm.JvmInline

/**
 * Stable identity of a provider implementation (one per cloud vendor integration).
 *
 * This is a *compile-time constant* style identifier, not user data. It is persisted with
 * accounts and cache rows, so existing values must never be renamed.
 */
@JvmInline
value class ProviderId(val value: String) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Stable identity of one *bound account* within one provider.
 *
 * A single user may bind several accounts of the same provider, so every remote call and every
 * cache row is scoped by `(ProviderId, AccountId)`.
 *
 * The value is generated locally (UUID) and mapped to the vendor's own user id in the
 * credential store; it must not be the vendor user id itself, so that a re-authorisation of the
 * same vendor account keeps the same local identity.
 */
@JvmInline
value class AccountId(val value: String) {
    init {
        require(value.isNotBlank()) { "AccountId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Opaque, provider-defined handle for one remote file or folder.
 *
 * ### Why it is opaque
 * Vendors disagree on how a file is addressed:
 *  - 115 needs a `pickcode` for most single-file operations and a `cid` for folders;
 *  - other vendors may need a numeric id plus a drive id.
 *
 * The domain layer never parses or constructs this value; it only round-trips it. The provider
 * that produced it is the only code allowed to interpret it. See
 * `provider/p115/.../P115FileIdCodec.kt` for the concrete 115 encoding.
 *
 * ### It is NOT globally unique
 * A [FileId] is only meaningful together with the [ProviderId] and [AccountId] it came from.
 * Always use [CloudFileKey] when a value may cross those boundaries (cache keys, deep links,
 * transfer records).
 */
@JvmInline
value class FileId(val value: String) {
    init {
        require(value.isNotBlank()) { "FileId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * The fully-qualified identity of a remote file: provider + account + file.
 *
 * Use this — never a bare [FileId] — for anything that outlives a single navigation session.
 */
data class CloudFileKey(
    val providerId: ProviderId,
    val accountId: AccountId,
    val fileId: FileId,
) {
    override fun toString(): String = "$providerId/$accountId/${fileId.value}"
}

/** Identity of a bound account: provider + local account id. */
data class CloudAccountKey(
    val providerId: ProviderId,
    val accountId: AccountId,
)
