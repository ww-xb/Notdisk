package com.notdisk.provider.baidu.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.notdisk.core.common.logging.CloudLogger
import com.notdisk.core.model.AccountId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The production [BaiduCredentialStore]: Android Keystore-backed encrypted preferences.
 *
 * ### Why encrypted storage and not Room/DataStore
 * A Baidu `BDUSS` is the account's primary login token (§1.5). Anyone holding it can read and modify
 * the account's files, and it is long-lived — the vendor sets it on `.baidu.com` with a multi-month
 * lifetime. It must therefore be encrypted at rest with a key the app cannot export, which is exactly
 * what `EncryptedSharedPreferences` plus a Keystore-held `MasterKey` provide.
 *
 * ### What this class deliberately does not do
 *  - **No logging of credential material.** Only the account id (redacted) and the operation name are
 *    ever logged.
 *  - **No fallback to plaintext.** If the Keystore is unavailable (a device with a broken or absent
 *    keystore, or one whose credentials were wiped) the store throws. A silent downgrade to
 *    unencrypted storage would be the worst possible failure mode, so it is not offered.
 *
 * ### Recovery from a wiped Keystore
 * When the user removes the device lock or the key is invalidated, the encrypted file can no longer be
 * read. [clearOnKeyLoss] exists for that case: the app drops the unreadable store and asks the user
 * to sign in again, rather than failing forever with a corrupt file.
 */
class BaiduEncryptedCredentialStore(
    context: Context,
    private val logger: CloudLogger,
) : BaiduCredentialStore {

    private val appContext = context.applicationContext

    /**
     * A private codec instance.
     *
     * `explicitNulls = false` keeps an absent `STOKEN` out of the stored JSON, which makes the
     * on-disk record smaller and — more importantly — makes a future schema addition backward
     * compatible without a migration.
     */
    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * Created lazily so that a Keystore failure surfaces on first *use* with a clear error rather
     * than crashing app startup during dependency-graph construction.
     */
    private val prefs: SharedPreferences by lazy { openEncryptedPreferences() }

    private fun openEncryptedPreferences(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun save(accountId: AccountId, credential: BaiduCredential) =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(StoredCredential.serializer(), credential.toStored())
            prefs.edit().putString(key(accountId), payload).commit()
            logger.i(TAG, "credential stored for account ${accountId.value.take(8)}…")
        }

    override suspend fun load(accountId: AccountId): BaiduCredential? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(key(accountId), null) ?: return@withContext null
        try {
            json.decodeFromString(StoredCredential.serializer(), raw).toCredential()
        } catch (t: Throwable) {
            // A decode failure means the stored shape no longer matches (an older build wrote it) or
            // the value was tampered with. Either way the credential is unusable, and reporting it as
            // "absent" routes the user to a clean re-login instead of an endless error.
            logger.w(TAG, "stored credential for ${accountId.value.take(8)}… is unreadable; treating as absent", t)
            null
        }
    }

    override suspend fun delete(accountId: AccountId) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key(accountId)).commit()
        logger.i(TAG, "credential removed for account ${accountId.value.take(8)}…")
    }

    override suspend fun listAccountIds(): List<AccountId> = withContext(Dispatchers.IO) {
        prefs.all.keys
            .filter { it.startsWith(PREFIX) }
            .mapNotNull { stored -> stored.removePrefix(PREFIX).takeIf { it.isNotBlank() } }
            .map { AccountId(it) }
    }

    /**
     * Drops the entire store.
     *
     * Called when the Keystore key is known to be unusable: the file cannot be decrypted, so keeping
     * it only produces repeated failures. Every account must be re-authorised afterwards, which the
     * accounts UI reports explicitly.
     */
    suspend fun clearOnKeyLoss() = withContext(Dispatchers.IO) {
        prefs.edit().clear().commit()
        logger.w(TAG, "encrypted credential store cleared after key loss")
    }

    private fun key(accountId: AccountId): String = "$PREFIX${accountId.value}"

    /**
     * The serialised form.
     *
     * A dedicated DTO (rather than serialising [BaiduCredential] directly) so the on-disk shape is an
     * explicit, versioned contract: adding a field to the domain type cannot silently change what is
     * written to disk.
     */
    @Serializable
    private data class StoredCredential(
        val bduss: String,
        val stoken: String? = null,
        /**
         * Flattened `name=value` pairs.
         *
         * A list of strings rather than a list of pairs because `kotlinx.serialization` cannot derive
         * a serializer for `Pair` without an explicit one, and because a single flat list keeps the
         * stored JSON readable when diagnosing a failed paste.
         */
        val extraCookies: List<String> = emptyList(),
        val userId: String? = null,
        val obtainedAt: Long,
        /** Bumped if the stored shape ever changes, so an old record can be detected and rejected. */
        val schemaVersion: Int = CURRENT_SCHEMA,
    )

    private fun BaiduCredential.toStored() = StoredCredential(
        bduss = bduss,
        stoken = stoken,
        extraCookies = extraCookies.map { (name, value) -> "$name=$value" },
        userId = userId,
        obtainedAt = obtainedAt,
    )

    private fun StoredCredential.toCredential(): BaiduCredential {
        require(schemaVersion == CURRENT_SCHEMA) {
            "Unsupported credential schema version: $schemaVersion"
        }
        return BaiduCredential(
            bduss = bduss,
            stoken = stoken,
            extraCookies = extraCookies.mapNotNull { pair ->
                // A stored entry with no `=` is dropped rather than turned into an empty cookie: a
                // malformed member would produce a malformed header, which the vendor may reject
                // outright — turning a working credential into a failed one.
                val separator = pair.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                pair.substring(0, separator) to pair.substring(separator + 1)
            },
            userId = userId,
            obtainedAt = obtainedAt,
        )
    }

    private companion object {
        const val TAG = "BaiduCredentials"
        const val FILE_NAME = "baidu_credentials"
        const val MASTER_KEY_ALIAS = "notdisk_baidu_master_key"
        const val PREFIX = "credential:"
        const val CURRENT_SCHEMA = 1
    }
}
