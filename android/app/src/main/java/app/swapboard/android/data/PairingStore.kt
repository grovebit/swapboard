package app.swapboard.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import app.swapboard.android.core.Base64Url
import app.swapboard.android.core.Pairing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps secrets with a non-exportable Android Keystore AES-GCM key. The key needs no user
 * authentication, so the service can unwrap the PSK after a reboot before the first unlock.
 */
class KeystoreWrapper(private val alias: String = "swapboard_psk_wrap") {
    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Returns `iv (12 bytes) ‖ ciphertext ‖ tag`. */
    fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    fun unwrap(blob: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return cipher.doFinal(blob, 12, blob.size - 12)
    }

    fun deleteKey() {
        runCatching { keyStore().deleteEntry(alias) }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** The single pairing with a Mac, in app-private preferences with the PSK Keystore-wrapped. */
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val wrapper = KeystoreWrapper()
    private val _pairing = MutableStateFlow(readStored())

    val pairing: StateFlow<Pairing?> = _pairing.asStateFlow()

    fun load(): Pairing? = _pairing.value

    fun save(pairing: Pairing) {
        prefs.edit()
            .putString(KEY_PID, pairing.pid)
            .putString(KEY_NAME, pairing.name)
            .putString(KEY_HOSTS, pairing.hosts.joinToString("\n"))
            .putInt(KEY_PORT, pairing.port)
            .putString(KEY_PSK, Base64Url.encode(wrapper.wrap(pairing.psk)))
            .putLong(KEY_PAIRED_AT, System.currentTimeMillis())
            .commit()
        _pairing.value = pairing
    }

    fun clear() {
        prefs.edit().clear().commit()
        wrapper.deleteKey()
        _pairing.value = null
    }

    private fun readStored(): Pairing? {
        val pid = prefs.getString(KEY_PID, null) ?: return null
        return try {
            Pairing(
                pid = pid,
                name = prefs.getString(KEY_NAME, null) ?: "Mac",
                psk = wrapper.unwrap(Base64Url.decode(prefs.getString(KEY_PSK, null) ?: return null)),
                hosts = (prefs.getString(KEY_HOSTS, null) ?: "").split('\n').filter { it.isNotBlank() },
                port = prefs.getInt(KEY_PORT, 0),
            ).takeIf { it.hosts.isNotEmpty() && it.port in 1..65535 }
        } catch (e: Exception) {
            Log.w("Swapboard", "stored pairing unreadable; re-pair needed", e)
            null
        }
    }

    private companion object {
        const val KEY_PID = "pid"
        const val KEY_NAME = "name"
        const val KEY_HOSTS = "hosts"
        const val KEY_PORT = "port"
        const val KEY_PSK = "psk_wrapped"
        const val KEY_PAIRED_AT = "paired_at"
    }
}
