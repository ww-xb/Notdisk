package com.notdisk.provider.p115.auth

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
 * The production [P115CredentialStore]: Android Keystore-backed encrypted preferences.
 *
 * ### Why encrypted storage and not Room/DataStore
 * A 115 credential contains a 120-hex `SEID`, documented in `API_REFERENCE.md` §2.1 Step 4 as the
 * account's **long-lived session key**. Anyone holding it can read and modify the account's files.
 * It must therefore be encrypted at rest with a key the app cannot export, which is exactly what
 * `EncryptedSharedPreferences` + a Keystore-held `MasterKey` provide.
 *
 * ### What this class deliberately does not do
 *  - **No logging of credential material.** Only the account id (redacted) and the operation name
 *    are ever logged.
 *  - **No fallback to plaintext.** If the Keystore is unavailable (a device with a broken/absent
 *    keystore, or a `clearCredentials`-wiped device) the store throws. A silent downgrade to
 *    unencrypted storage would be the worst possible failure mode, so it is not offered.
 *
 * ### Recovery from a wiped Keystore
 * When the user removes the device lock or the key is invalidated, the encrypted file can no longer
 * be read. [clearOnKeyLoss] exists for that case: the app drops the unreadable store and asks the
 * user to sign in again, rather than failing forever with a corrupt file.
 */
class P115EncryptedCredentialStore(
    context: Context,
    private val logger: CloudLogger,
) : P115CredentialStore {

    private val appContext = context.applicationContext

    /**
     * A private codec instance.
     *
     * `explicitNulls = false` keeps an absent `PHPSESSID` out of the stored JSON, which makes the
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

    override suspend fun save(accountId: AccountId, credential: P115Credential) =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(StoredCredential.serializer(), credential.toStored())
            prefs.edit().putString(key(accountId), payload).commit()
            logger.i(TAG, "credential stored for account ${accountId.value.take(8)}…")
        }

    override suspend fun load(accountId: AccountId): P115Credential? = withContext(Dispatchers.IO) {
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
     * A dedicated DTO (rather than serialising [P115Credential] directly) so the on-disk shape is an
     * explicit, versioned contract: adding a field to the domain type cannot silently change what is
     * written to disk.
     */
    @Serializable
    private data class StoredCredential(
        val userSessionId: String,
        val uid: String,
        val cid: String,
        val seid: String,
        val kid: String,
        val userId: String,
        val phpSessionId: String? = null,
        val obtainedAt: Long,
        /** Bumped if the stored shape ever changes, so an old record can be detected and rejected. */
        val schemaVersion: Int = CURRENT_SCHEMA,
    )

    private fun P115Credential.toStored() = StoredCredential(
        userSessionId = userSessionId,
        uid = uid,
        cid = cid,
        seid = seid,
        kid = kid,
        userId = userId,
        phpSessionId = phpSessionId,
        obtainedAt = obtainedAt,
    )

    private fun StoredCredential.toCredential(): P115Credential {
        require(schemaVersion == CURRENT_SCHEMA) {
            "Unsupported credential schema version: $schemaVersion"
        }
        return P115Credential(
            userSessionId = userSessionId,
            uid = uid,
            cid = cid,
            seid = seid,
            kid = kid,
            userId = userId,
            phpSessionId = phpSessionId,
            obtainedAt = obtainedAt,
        )
    }

    private companion object {
        const val TAG = "P115Credentials"
        const val FILE_NAME = "p115_credentials"
        const val MASTER_KEY_ALIAS = "notdisk_p115_master_key"
        const val PREFIX = "credential:"
        const val CURRENT_SCHEMA = 1
    }
}
