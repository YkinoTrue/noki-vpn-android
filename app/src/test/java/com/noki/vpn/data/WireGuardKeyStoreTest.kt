package com.noki.vpn.data

import java.io.File
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WireGuardKeyStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun keySurvivesReopenWithoutAppearingInTheStoredBlob() {
        val file = File(temporaryFolder.root, "key.bin")
        val expected = keyPair(7, 9)
        var generations = 0
        val newStore = {
            WireGuardKeyStore(
                file = file,
                wrappingKey = { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
                deleteWrappingKey = {},
                generate = { generations++; expected },
            )
        }

        assertArrayEquals(expected.privateKey, newStore().getOrCreate().privateKey)
        assertArrayEquals(expected.publicKey, newStore().getOrCreate().publicKey)
        assertEquals(1, generations)
        val stored = file.readBytes()
        assertFalse(stored.asList().windowed(32).any { it.toByteArray().contentEquals(expected.privateKey) })
        assertFalse(stored.asList().windowed(32).any { it.toByteArray().contentEquals(expected.publicKey) })
    }

    @Test
    fun logoutClearRemovesOldKeyAndNextCreationRotatesIt() {
        val file = File(temporaryFolder.root, "key.bin")
        val keys = listOf(keyPair(7, 9), keyPair(11, 13)).iterator()
        var wrappingKeyDeleted = false
        val store = WireGuardKeyStore(
            file = file,
            wrappingKey = { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
            deleteWrappingKey = { wrappingKeyDeleted = true },
            generate = { keys.next() },
        )

        assertEquals(Base64.getEncoder().encodeToString(ByteArray(32) { 9 }), store.getOrCreate().publicKeyBase64())
        store.clear()
        assertFalse(file.exists())
        assertTrue(wrappingKeyDeleted)
        assertEquals(Base64.getEncoder().encodeToString(ByteArray(32) { 13 }), store.getOrCreate().publicKeyBase64())
    }

    @Test
    fun corruptedBlobFailsClosedWithoutGeneratingReplacementKey() {
        val file = File(temporaryFolder.root, "key.bin")
        var generations = 0
        val store = WireGuardKeyStore(
            file = file,
            wrappingKey = { SecretKeySpec(ByteArray(32) { 3 }, "AES") },
            deleteWrappingKey = {},
            generate = { generations++; keyPair(7, 9) },
        )
        store.getOrCreate()
        val corrupted = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file.writeBytes(corrupted)

        var failed = false
        try {
            store.getOrCreate()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
        assertEquals(1, generations)
    }

    @Test
    fun clearForDeviceThatNeverUsedWireGuardDoesNotOpenKeyStore() {
        val store = WireGuardKeyStore(
            file = File(temporaryFolder.root, "missing-key.bin"),
            wrappingKey = { error("wrapping key must not be opened") },
            deleteWrappingKey = { error("wrapping key must not be deleted") },
            generate = { error("key must not be generated") },
        )

        store.clear()
    }

    private fun keyPair(privateByte: Byte, publicByte: Byte): WireGuardKeyPair {
        val privateKey = ByteArray(32) { privateByte }
        privateKey[0] = (privateKey[0].toInt() and 248).toByte()
        privateKey[31] = ((privateKey[31].toInt() and 127) or 64).toByte()
        return WireGuardKeyPair(privateKey, ByteArray(32) { publicByte })
    }
}
