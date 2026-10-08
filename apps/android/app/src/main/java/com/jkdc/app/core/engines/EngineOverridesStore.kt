package com.jkdc.app.core.engines

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Per-provider user keys overriding the deployed bundled ones, encrypted at
 * rest with the same Keystore AES-GCM scheme as `CredentialStore`. An absent
 * (or undecryptable) entry means "use the bundled key" — the default.
 */
class EngineOverridesStore(
    context: Context,
    private val codec: KeyCodec = AndroidKeyCodec(),
) {
    /** Isolated so the validation-only logic is testable on the JVM. */
    interface KeyCodec {
        fun encrypt(plain: String): String
        fun decrypt(stored: String): String?
    }

    /** Android Keystore AES-GCM; one key, per-entry ciphertext and IV. */
    class AndroidKeyCodec : KeyCodec {
        private fun key(): SecretKey {
            val store = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
            generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
            return generator.generateKey()
        }

        override fun encrypt(plain: String): String {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        }

        override fun decrypt(stored: String): String? = try {
            val (encodedIv, encodedCiphertext) = stored.split(":", limit = 2).let { it[0] to it[1] }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, Base64.decode(encodedIv, Base64.NO_WRAP)))
            cipher.doFinal(Base64.decode(encodedCiphertext, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        } catch (_: Exception) { null }

        private companion object {
            const val ANDROID_KEY_STORE = "AndroidKeyStore"
            const val KEY_ALIAS = "engine_override_key"
            const val TRANSFORMATION = "AES/GCM/NoPadding"
            const val GCM_TAG_BITS = 128
        }
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Trims and validates; returns null when the key is not storable. */
    fun normalize(key: String): String? = normalizeKey(key)

    companion object {
        internal const val PREFERENCES = "engine_overrides"

        /** Trims and validates; returns null when the key is not storable. */
        internal fun normalizeKey(key: String): String? = key.trim()
            .takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isWhitespace) }
    }

    @Synchronized
    fun saveCustomKey(engineId: String, key: String): Boolean {
        val value = normalizeKey(key) ?: return false
        ConversationEngineFactory.validate(engineId)
        return preferences.edit().putString(entry(engineId), codec.encrypt(value)).commit()
    }

    @Synchronized
    fun removeCustomKey(engineId: String) {
        preferences.edit().remove(entry(engineId)).commit()
    }

    @Synchronized
    fun customKey(engineId: String): String? =
        preferences.getString(entry(engineId), null)?.let { stored -> codec.decrypt(stored)?.takeIf { it.isNotBlank() } }

    fun hasCustomKey(engineId: String): Boolean = customKey(engineId) != null

    private fun entry(engineId: String) = "custom_key_$engineId"
}

private val keyEnvBindings = listOf(RemoteEngineConfig.DEEPSEEK, RemoteEngineConfig.GLM, RemoteEngineConfig.GLM_45)
    .map { it.keyEnvVar to it.id }

/** Maps a provider's key environment variable to its engine ids; empty when
    the env var does not belong to a local provider (e.g. the OpenAI
    credential). Several engines can share one env var: both Zhipu engines
    read ZHIPUAI_API_KEY. */
fun localEngineIdsForKeyEnv(keyEnvVar: String): List<String> =
    keyEnvBindings.filter { it.first == keyEnvVar }.map { it.second }

fun localEngineKeyEnv(engineId: String): String? =
    keyEnvBindings.firstOrNull { it.second == engineId }?.first
