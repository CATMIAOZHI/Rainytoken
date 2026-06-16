package com.rainy.token.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 加解密 + 持久化封装。
 *
 * 加密：用 Android Keystore 中名为 [KEY_ALIAS] 的 AES-256 GCM 主密钥；
 *       输出 = `Base64(iv || ciphertext || tag)`。
 * 持久化：密文经 [DataStore] 存到 "secure_storage" 文件。
 *
 * 不使用 [androidx.security.crypto.EncryptedSharedPreferences]
 * （1.1.0-alpha06 起已 deprecated），自行实现更可控。
 */
class SecureStorage(
    private val dataStore: DataStore<Preferences>,
    private val json: Json = DefaultJson
) {

    suspend fun <T> put(
        key: String,
        value: T,
        serializer: KSerializer<T>
    ) {
        val plaintext = json.encodeToString(serializer, value)
        val ciphertext = encrypt(plaintext)
        dataStore.edit { prefs ->
            prefs[stringPreferencesKey(key)] = ciphertext
        }
    }

    suspend fun <T> get(
        key: String,
        serializer: KSerializer<T>
    ): T? {
        val ciphertext = dataStore.data
            .map { it[stringPreferencesKey(key)] }
            .first() ?: return null
        val plaintext = runCatching { decrypt(ciphertext) }.getOrNull() ?: return null
        return runCatching { json.decodeFromString(serializer, plaintext) }.getOrNull()
    }

    suspend fun remove(key: String) {
        dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey(key))
        }
    }

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    // --- 加密 / 解密 ---

    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keystore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_SIZE_BITS)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size).also {
            System.arraycopy(iv, 0, it, 0, iv.size)
            System.arraycopy(ciphertext, 0, it, iv.size, ciphertext.size)
        }
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > GCM_IV_LENGTH) { "密文长度异常" }
        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val payload = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
        return String(cipher.doFinal(payload), Charsets.UTF_8)
    }

    companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "rainy_token_master_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256

        val DefaultJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/** 顶层 DataStore 委托，文件名对应阶段 2.2 的"非加密之外"的密文 DataStore。 */
val Context.secureStorageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "secure_storage"
)