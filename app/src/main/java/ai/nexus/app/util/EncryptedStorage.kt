package ai.nexus.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import ai.nexus.core.memory.MemoryStorage
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Memory, tasks and skills are stored encrypted (AES-256-GCM with a key held
 * in the Android Keystore, never exported). Old plain files are migrated.
 */
class EncryptedStorage(private val file: File, private val alias: String = "nexus_data_key") : MemoryStorage {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    override fun read(): String? {
        if (!file.exists()) return null
        val raw = file.readText()
        if (!raw.startsWith(PREFIX)) return raw // legacy plain JSON: migrated on next write
        return runCatching {
            val bytes = Base64.decode(raw.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            String(c.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(data: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(data.toByteArray(Charsets.UTF_8))
        val out = c.iv + enc
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(PREFIX + Base64.encodeToString(out, Base64.NO_WRAP))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object { private const val PREFIX = "NXE1:" }
}
