package com.noki.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class WireGuardKeyPair(privateKey: ByteArray, publicKey: ByteArray) {
    val privateKey: ByteArray = privateKey.copyOf()
    val publicKey: ByteArray = publicKey.copyOf()

    init {
        require(this.privateKey.size == KEY_BYTES && this.publicKey.size == KEY_BYTES)
        require(this.privateKey.any { it != 0.toByte() } && this.publicKey.any { it != 0.toByte() })
        require(this.privateKey[0].toInt() and 7 == 0)
        require(this.privateKey[31].toInt() and 0x80 == 0)
        require(this.privateKey[31].toInt() and 0x40 != 0)
    }

    fun publicKeyBase64(): String = Base64.getEncoder().encodeToString(publicKey)

    private companion object {
        const val KEY_BYTES = 32
    }
}

internal object NativeWireGuardKeys {
    init { System.loadLibrary("noki_wireguard") }

    private external fun generateNative(): ByteArray?

    fun generate(): WireGuardKeyPair {
        val raw = generateNative() ?: throw IllegalStateException("wireguard_key_generation_failed")
        try {
            require(raw.size == 64) { "wireguard_key_generation_invalid" }
            val private = raw.copyOfRange(0, 32)
            val public = raw.copyOfRange(32, 64)
            try {
                return WireGuardKeyPair(private, public)
            } finally {
                private.fill(0)
                public.fill(0)
            }
        } finally {
            raw.fill(0)
        }
    }
}

/** Stores only one device WG key outside Android Backup, wrapped by Android Keystore. */
internal class WireGuardKeyStore(
    private val file: File,
    private val wrappingKey: () -> SecretKey,
    private val deleteWrappingKey: () -> Unit,
    private val generate: () -> WireGuardKeyPair,
) {
    fun getOrCreate(): WireGuardKeyPair = synchronized(lock) {
        if (file.exists()) return@synchronized read()
        val pair = generate()
        write(pair)
        pair
    }

    fun clear() = synchronized(lock) {
        if (!file.exists()) return@synchronized
        if (!file.delete()) {
            throw IllegalStateException("wireguard_key_delete_failed")
        }
        deleteWrappingKey()
    }

    private fun read(): WireGuardKeyPair {
        require(file.length() == BLOB_BYTES.toLong()) { "wireguard_key_blob_invalid" }
        val bytes = file.readBytes()
        require(bytes.size == BLOB_BYTES && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            "wireguard_key_blob_invalid"
        }
        val iv = bytes.copyOfRange(MAGIC.size, MAGIC.size + IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(MAGIC)
        val plain = cipher.doFinal(bytes, MAGIC.size + IV_BYTES, bytes.size - MAGIC.size - IV_BYTES)
        try {
            require(plain.size == 64) { "wireguard_key_blob_invalid" }
            val private = plain.copyOfRange(0, 32)
            val public = plain.copyOfRange(32, 64)
            try {
                return WireGuardKeyPair(private, public)
            } finally {
                private.fill(0)
                public.fill(0)
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun write(pair: WireGuardKeyPair) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val iv = cipher.iv ?: throw IllegalStateException("wireguard_key_iv_missing")
        require(iv.size == IV_BYTES) { "wireguard_key_iv_invalid" }
        cipher.updateAAD(MAGIC)
        val plain = pair.privateKey + pair.publicKey
        val encrypted = try { cipher.doFinal(plain) } finally { plain.fill(0) }
        val blob = MAGIC + iv + encrypted
        val parent = file.parentFile ?: throw IllegalStateException("wireguard_key_path_invalid")
        require(parent.isDirectory || parent.mkdirs()) { "wireguard_key_directory_failed" }
        val pending = File.createTempFile("ru-wg-key-", ".tmp", parent)
        try {
            FileOutputStream(pending).use { output ->
                output.write(blob)
                output.fd.sync()
            }
            Files.move(
                pending.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            pending.delete()
        }
    }

    companion object {
        private val lock = Any()
        private val MAGIC = byteArrayOf('N'.code.toByte(), 'W'.code.toByte(), 'G'.code.toByte(), 1)
        private const val IV_BYTES = 12
        private const val BLOB_BYTES = 4 + IV_BYTES + 64 + 16
        private const val KEY_ALIAS = "noki_ru_wireguard_wrap_v1"

        fun android(context: Context): WireGuardKeyStore = WireGuardKeyStore(
            file = File(context.noBackupFilesDir, "ru-wireguard-device-key-v1.bin"),
            wrappingKey = ::getOrCreateAndroidWrappingKey,
            deleteWrappingKey = ::deleteAndroidWrappingKey,
            generate = { NativeWireGuardKeys.generate() },
        )

        private fun androidKeyStore(): KeyStore =
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        private fun getOrCreateAndroidWrappingKey(): SecretKey {
            (androidKeyStore().getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply { init(spec) }
                .generateKey()
        }

        private fun deleteAndroidWrappingKey() {
            androidKeyStore().deleteEntry(KEY_ALIAS)
        }
    }
}
