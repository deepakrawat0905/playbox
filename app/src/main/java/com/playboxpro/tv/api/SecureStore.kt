package com.playboxpro.tv.api

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Encrypted on-device storage for the device identity. Keys and values are
 * encrypted with an AES-256 key held in the Android Keystore.
 *
 * A few TV boxes ship a broken Keystore; there the store falls back to
 * private app storage rather than leaving the device without an identity.
 */
object SecureStore {
    @Volatile private var prefs: SharedPreferences? = null

    fun prefs(context: Context): SharedPreferences {
        prefs?.let { return it }
        synchronized(this) {
            prefs?.let { return it }
            val p = runCatching {
                EncryptedSharedPreferences.create(
                    "playbox_secure",
                    MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
                    context.applicationContext,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            }.getOrElse {
                context.applicationContext.getSharedPreferences("playbox_secure_fallback", Context.MODE_PRIVATE)
            }
            prefs = p
            return p
        }
    }
}
