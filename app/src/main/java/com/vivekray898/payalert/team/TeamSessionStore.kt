package com.vivekray898.payalert.team

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import java.nio.charset.StandardCharsets.UTF_8
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface TeamSessionStorage {
    fun readAuth(): TeamAuthState
    fun writeAuth(state: TeamAuthState)
    fun clearAuth()
    fun deviceId(origin: String, userId: String): String
}

/**
 * Auth and installation IDs are AES/GCM encrypted with a non-exportable Android Keystore key.
 * team_private.xml is excluded from cloud backup and device transfer. No payment preferences
 * or database are touched. Signing out removes auth only, preserving the per-account device ID.
 */
internal class TeamSessionStore(context: Context) : TeamSessionStorage {
    private val preferences = context.applicationContext.getSharedPreferences("team_private", Context.MODE_PRIVATE)
    private val gson = Gson()

    @Synchronized
    override fun readAuth(): TeamAuthState = securely {
        val encrypted = preferences.getString(AUTH, null) ?: return@securely TeamAuthState()
        requireNotNull(gson.fromJson(decrypt(AUTH, encrypted), TeamAuthState::class.java))
    }

    @Synchronized
    override fun writeAuth(state: TeamAuthState) = securely {
        check(preferences.edit().putString(AUTH, encrypt(AUTH, gson.toJson(state))).commit())
    }

    @Synchronized
    override fun clearAuth() = securely {
        // Removal doesn't require the keystore: logout works even when a key is unavailable.
        check(preferences.edit().remove(AUTH).commit())
    }

    @Synchronized
    override fun deviceId(origin: String, userId: String): String = securely {
        val hash = MessageDigest.getInstance("SHA-256").digest("$origin\n$userId".toByteArray(UTF_8))
        val entry = "installation_${TeamEncoding.encode(hash)}"
        preferences.getString(entry, null)?.let {
            return@securely UUID.fromString(decrypt(entry, it)).toString()
        }
        UUID.randomUUID().toString().also {
            check(preferences.edit().putString(entry, encrypt(entry, it)).commit())
        }
    }

    private fun encrypt(entry: String, plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(entry.toByteArray(UTF_8))
        check(cipher.iv.size == 12)
        return "1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(UTF_8)), Base64.NO_WRAP)
    }

    private fun decrypt(entry: String, encrypted: String): String {
        require(encrypted.startsWith("1:") && encrypted.length <= 100_000)
        val bytes = Base64.decode(encrypted.substring(2), Base64.NO_WRAP)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(entry.toByteArray(UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), UTF_8)
    }

    private fun key(): SecretKey = synchronized(KEY_LOCK) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey) ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build())
            }.generateKey()
    }

    private fun <T> securely(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw TeamClientException("Secure sign-in storage is unavailable. Please try signing in again.")
    }

    private companion object {
        const val AUTH = "auth"
        const val KEY_ALIAS = "payalert.team.aes.v1"
        val KEY_LOCK = Any()
    }
}
