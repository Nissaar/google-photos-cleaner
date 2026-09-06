package xyz.photocleaner.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Supplies the SQLCipher passphrase for the local decisions database.
 *
 * The passphrase is 32 random bytes generated on first run. It is stored in
 * EncryptedSharedPreferences, whose own master key lives in the Android Keystore —
 * hardware-backed (StrongBox or TEE) where the device provides it. The passphrase
 * therefore never exists in plaintext on disk, and cannot be recovered by reading
 * the app's data directory off a rooted or imaged device.
 */
object DatabaseKeyProvider {

    private const val PREFS_FILE = "pc_secure_prefs"
    private const val KEY_DB_PASSPHRASE = "db_passphrase"
    private const val PASSPHRASE_BYTES = 32

    @Volatile
    private var cached: ByteArray? = null

    fun getPassphrase(context: Context): ByteArray {
        cached?.let { return it.copyOf() }

        synchronized(this) {
            cached?.let { return it.copyOf() }

            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                // Prefer hardware-isolated key storage when the device has it.
                .setRequestStrongBoxBacked(true)
                .build()

            val prefs = try {
                buildPrefs(context, masterKey)
            } catch (e: Exception) {
                // A corrupted keystore entry (e.g. after a restore onto a new device)
                // makes the store permanently unreadable. Reset it: the contents are
                // only local decisions, which are safe to rebuild.
                context.applicationContext.deleteSharedPreferences(PREFS_FILE)
                buildPrefs(context, masterKey)
            }

            val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
            val passphrase = if (existing != null) {
                Base64.decode(existing, Base64.NO_WRAP)
            } else {
                ByteArray(PASSPHRASE_BYTES).also { fresh ->
                    SecureRandom().nextBytes(fresh)
                    prefs.edit()
                        .putString(KEY_DB_PASSPHRASE, Base64.encodeToString(fresh, Base64.NO_WRAP))
                        .apply()
                }
            }

            cached = passphrase
            return passphrase.copyOf()
        }
    }

    private fun buildPrefs(context: Context, masterKey: MasterKey) =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    /** Called on sign-out / wipe so the next launch starts from a fresh key. */
    fun clear(context: Context) {
        synchronized(this) {
            cached?.fill(0)
            cached = null
            context.applicationContext.deleteSharedPreferences(PREFS_FILE)
        }
    }
}
