package org.hisn.app.sync

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins this client's sync cryptography to the desktop server's, primitive by primitive.
 *
 * Every expected value below was printed by the desktop implementation itself — a small harness
 * linked against `src/sync/SyncCrypto.cpp` (Botan 3) and run on the same inputs. If either side
 * ever drifts — a different HKDF info string, a little-endian counter, a changed salt — these
 * comparisons fail here rather than as an unexplained handshake rejection on a user's phone.
 *
 * The X25519 values are additionally the published RFC 7748 §6.1 vectors, so this also proves both
 * implementations agree with the standard rather than merely with each other.
 */
class SyncCryptoVectorTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte() }

    private fun hexOf(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    /** The same deterministic inputs the desktop harness used. */
    private val salt = ByteArray(32) { it.toByte() }
    private val ikm = ByteArray(96) { (255 - it).toByte() }
    private val prefix = hex("aabbccdd")

    @Test
    fun sha256MatchesDesktop() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hexOf(SyncCrypto.sha256(ByteArray(0))))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hexOf(SyncCrypto.sha256("abc".toByteArray(Charsets.US_ASCII))))
    }

    @Test
    fun keyScheduleMatchesDesktop() {
        assertEquals("client->server traffic key",
            "a7f14988eaecd1bda28e0ab6381262be20b460f3d335a3bfc7091549f65edb5b",
            hexOf(SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_KEY_C2S, 32)))
        assertEquals("server->client traffic key",
            "357ed4d74ddb0222413ca8457c8838c131a231b340384f7db67037e43d7a75fc",
            hexOf(SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_KEY_S2C, 32)))
        assertEquals("client->server nonce prefix",
            "b9aa4cc1", hexOf(SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_NONCE_C2S, 4)))
        assertEquals("server->client nonce prefix",
            "e80703d5", hexOf(SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_NONCE_S2C, 4)))
    }

    @Test
    fun confirmationTagsMatchDesktop() {
        assertEquals("ad723cf1d12c9fb02b05e9122288322ef4a557f71694094524b199e7b5fc5100",
            hexOf(SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_CONFIRM_SERVER, 32)))
        assertEquals("a77622be5d34dc5b40298402c0db775adbccfcbe89a527ea43ec8d58f2431d92",
            hexOf(SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_CONFIRM_CLIENT, 32)))
    }

    /** A little-endian counter here would silently reuse nonces against a big-endian peer. */
    @Test
    fun frameNoncesAreBigEndianLikeDesktop() {
        assertEquals("aabbccdd0000000000000000", hexOf(SyncCrypto.nonce(prefix, 0L)))
        assertEquals("aabbccdd0000000000000001", hexOf(SyncCrypto.nonce(prefix, 1L)))
        assertEquals("aabbccdd0102030405060708", hexOf(SyncCrypto.nonce(prefix, 0x0102030405060708L)))
    }

    @Test
    fun x25519MatchesDesktopAndRfc7748() {
        val alicePriv = X25519PrivateKeyParameters(
            hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"), 0)
        val bobPriv = X25519PrivateKeyParameters(
            hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"), 0)

        val alicePub = alicePriv.generatePublicKey().encoded
        val bobPub = bobPriv.generatePublicKey().encoded
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", hexOf(alicePub))
        assertEquals("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", hexOf(bobPub))

        val shared = SyncCrypto.agree(alicePriv, bobPub)
        assertEquals("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742", hexOf(shared))
        assertArrayEquals("both directions must agree", shared, SyncCrypto.agree(bobPriv, alicePub))
    }

    /** ChaCha20-Poly1305 is deterministic given key/nonce/AAD, so the ciphertext itself is a vector. */
    @Test
    fun aeadMatchesDesktop() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val nonce = SyncCrypto.nonce(prefix, 7L)
        val aad = hex("11223344")
        val plaintext = "hello حصن".toByteArray(Charsets.UTF_8)

        val sealed = SyncCrypto.seal(key, nonce, aad, plaintext)
        assertEquals("18d25eb5224ffd974f700d18007359ac7e4cb1cdfb4d7332d07f97df", hexOf(sealed))
        assertArrayEquals(plaintext, SyncCrypto.open(key, nonce, aad, sealed))
    }

    @Test
    fun aeadRejectsTamperedFrames() {
        val key = ByteArray(32) { (it * 3).toByte() }
        val nonce = SyncCrypto.nonce(prefix, 7L)
        val aad = hex("11223344")
        val sealed = SyncCrypto.seal(key, nonce, aad, "hello حصن".toByteArray(Charsets.UTF_8))

        val flipped = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue("a flipped ciphertext byte must not authenticate", runCatching {
            SyncCrypto.open(key, nonce, aad, flipped)
        }.isFailure)

        assertTrue("a changed length prefix (the AAD) must not authenticate", runCatching {
            SyncCrypto.open(key, nonce, hex("11223345"), sealed)
        }.isFailure)
    }
}
