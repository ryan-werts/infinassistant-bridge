package com.w6tech.infinassistant.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException

class HaTokenStore(private val context: Context) {

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * REQ-040 — open the encrypted preferences lazily and inside a try/catch.
     * GeneralSecurityException / KeyStoreException / IOException can surface
     * when the Android keystore master key has rotated, the user wiped app data
     * via the OS, or the device underwent a factory-reset-from-backup. In any
     * of those cases the prior ciphertext is unreadable; we fail-closed (return
     * a null prefs reference) rather than crash the process. Callers then see
     * no token and re-prompt the user to authenticate.
     */
    private fun preferences(): SharedPreferences? {
        prefs?.let { return it }
        return synchronized(this) {
            prefs ?: try {
                @Suppress("DEPRECATION") // Migration to EncryptedDataStore planned — see Docs/runbooks/token-store-migration.md
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_FILE,
                    MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                ).also { prefs = it }
            } catch (e: GeneralSecurityException) {
                Log.w(TAG, "Token store unreadable (GeneralSecurityException) — keystore rotated?", e)
                clearOnUnrecoverable(e)
                null
            } catch (e: KeyStoreException) {
                Log.w(TAG, "Token store unreadable (KeyStoreException) — keystore unavailable?", e)
                clearOnUnrecoverable(e)
                null
            } catch (e: IOException) {
                Log.w(TAG, "Token store unreadable (IOException) — encrypted file corrupted?", e)
                clearOnUnrecoverable(e)
                null
            }
        }
    }

    /**
     * Best-effort wipe of the encrypted prefs file when its master key is no
     * longer recoverable. Without this, every subsequent open attempt would
     * raise the same exception, locking the user out until they reinstalled.
     */
    private fun clearOnUnrecoverable(cause: Throwable) {
        try {
            context.deleteSharedPreferences(PREFS_FILE)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete unreadable token store: ${t.message}", t)
        }
    }

    fun setToken(token: String?) {
        val p = preferences() ?: return
        p.edit().apply {
            if (token == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, token)
            commit() // synchronous so tests observe the write deterministically
        }
    }

    fun getToken(): String? = try {
        preferences()?.getString(KEY_TOKEN, null)
    } catch (e: GeneralSecurityException) {
        Log.w(TAG, "getToken: ciphertext unreadable, returning null", e)
        clearOnUnrecoverable(e)
        null
    }

    fun clearAll() {
        preferences()?.edit()?.clear()?.commit()
    }

    companion object {
        private const val TAG = "HaTokenStore"
        private const val PREFS_FILE = "ha_token_store"
        private const val KEY_TOKEN = "ha_long_lived_token"
    }
}
