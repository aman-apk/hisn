package org.hisn.app.sync

import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.json.JSONException
import org.json.JSONObject
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * ============================================================================================
 *  HISN SYNC PROTOCOL, version 1
 * ============================================================================================
 *
 *  A LAN-only, internet-free sync channel between the Hisn phone app (client/initiator) and the
 *  KeePassXC-based desktop app (server/responder). The desktop listens on a TCP port and shows a
 *  QR code; the phone scans it and connects. The threat model is a home network that may contain
 *  hostile devices: everything on the wire is encrypted and authenticated, and an attacker who
 *  did not see the QR code cannot complete the handshake.
 *
 *  The desktop implementation is written from this same description, so every byte layout below
 *  is normative. All multi-byte integers are LITTLE ENDIAN unless the field explicitly says
 *  big endian. All hashes are SHA-256. All key agreement is X25519 (RFC 7748). All bulk
 *  encryption is ChaCha20-Poly1305 (RFC 8439) with a 16-byte tag.
 *
 *  --------------------------------------------------------------------------------------------
 *  0. PAIRING PAYLOAD (the QR code)
 *  --------------------------------------------------------------------------------------------
 *  Compact single-line JSON, no whitespace:
 *
 *      {"v":1,"n":"<device name>","h":"<ipv4/ipv6 literal>","p":<port>,
 *       "k":"<base64 32-byte pairing secret>","f":"<base64 sha256 of desktop static pubkey>"}
 *
 *   v : protocol version, must be 1.
 *   n : human readable desktop name, <= 64 UTF-8 bytes.
 *   h : address the phone dials; the desktop picks its LAN address.
 *   p : TCP port, 1..65535.
 *   k : 32 random bytes, freshly generated for each pairing session, base64 (standard alphabet,
 *       padding optional, no line breaks). Mixed into the handshake so only a device that saw
 *       the QR code can pair. The phone stores it and reuses it for every later sync with that
 *       desktop; the desktop stores it per paired device.
 *   f : SHA-256 of the desktop's 32-byte static X25519 public key, base64. Pinned on first use
 *       (trust on first use); a later mismatch aborts the sync.
 *
 *  --------------------------------------------------------------------------------------------
 *  1. HANDSHAKE  (plaintext, fixed-size records, no framing)
 *  --------------------------------------------------------------------------------------------
 *  MAGIC = the 8 ASCII bytes "HISNSYN1".
 *
 *  1.1  client -> server, ClientHello, exactly 42 bytes
 *
 *       offset  size  field
 *       0       8     MAGIC
 *       8       2     version, u16 LE, = 1
 *       10      32    client ephemeral X25519 public key
 *
 *  1.2  server -> client, ServerHello, exactly 106 bytes
 *
 *       offset  size  field
 *       0       8     MAGIC
 *       8       2     version, u16 LE, = 1
 *       10      32    server ephemeral X25519 public key
 *       42      32    server STATIC X25519 public key
 *       74      32    server confirmation tag (see 1.4)
 *
 *  1.3  Shared secrets. Both sides compute two X25519 agreements:
 *
 *       ee = X25519(client_ephemeral_private, server_ephemeral_public)
 *          = X25519(server_ephemeral_private, client_ephemeral_public)
 *       es = X25519(client_ephemeral_private, server_static_public)
 *          = X25519(server_static_private,    client_ephemeral_public)
 *
 *       An all-zero agreement result (low-order peer key) MUST abort the connection.
 *
 *  1.4  Key schedule.
 *
 *       transcript = MAGIC || version_u16le || client_eph_pub || server_eph_pub || server_static_pub
 *                    (8 + 2 + 32 + 32 + 32 = 106 bytes)
 *       salt = SHA-256(transcript)
 *       IKM  = ee || es || pairing_secret            (32 + 32 + 32 = 96 bytes)
 *       PRK  = HKDF-Extract(salt, IKM)               (RFC 5869, SHA-256)
 *
 *       key_c2s     = HKDF-Expand(PRK, "hisn-sync/1 key client->server",   32)
 *       key_s2c     = HKDF-Expand(PRK, "hisn-sync/1 key server->client",   32)
 *       nonce_c2s   = HKDF-Expand(PRK, "hisn-sync/1 nonce client->server",  4)
 *       nonce_s2c   = HKDF-Expand(PRK, "hisn-sync/1 nonce server->client",  4)
 *       confirm_s   = HKDF-Expand(PRK, "hisn-sync/1 confirm server",       32)
 *       confirm_c   = HKDF-Expand(PRK, "hisn-sync/1 confirm client",       32)
 *
 *       Info strings are ASCII, without a terminating NUL byte.
 *
 *  1.5  Confirmation. The server puts confirm_s in ServerHello. The client recomputes it and
 *       compares in constant time; a mismatch means the peer does not know the pairing secret or
 *       does not hold the advertised static key, and the client aborts. The client then sends
 *       confirm_c as 32 raw bytes; the server compares it in constant time and aborts on
 *       mismatch. Both confirmations are derived from the same PRK as the traffic keys but under
 *       distinct labels, so publishing them reveals nothing about the traffic keys.
 *
 *  1.6  Identity pinning. The client compares SHA-256(server_static_public) against the
 *       fingerprint from the QR code (first pairing) or the stored fingerprint (later syncs),
 *       in constant time, and aborts on mismatch.
 *
 *  --------------------------------------------------------------------------------------------
 *  2. TRANSPORT FRAMES  (everything after the handshake)
 *  --------------------------------------------------------------------------------------------
 *      +---------------------+-------------------------------+
 *      | length : u32 LE     | ciphertext || tag (length B)   |
 *      +---------------------+-------------------------------+
 *
 *   length  = ciphertext length + 16 (Poly1305 tag). Receivers MUST validate
 *             17 <= length <= MAX_FRAME_CIPHERTEXT (1 MiB + 16) BEFORE allocating.
 *   AAD     = the four length bytes exactly as they appear on the wire.
 *   nonce   = 4-byte direction prefix (nonce_c2s or nonce_s2c) || u64 BIG ENDIAN counter.
 *             The counter starts at 0 for the first frame in each direction and increments by
 *             one per frame. It is never reset, so a nonce is never reused under a key. A
 *             counter that would wrap MUST abort the connection.
 *   Sender uses key_c2s (client) or key_s2c (server); receiver uses the other.
 *
 *   Frame plaintext:
 *      +--------------+------------------+
 *      | type : u8    | body : remainder |
 *      +--------------+------------------+
 *
 *  --------------------------------------------------------------------------------------------
 *  3. MESSAGES
 *  --------------------------------------------------------------------------------------------
 *   0x01 HELLO
 *        u16 LE  version (= 1)
 *        u8      name length N (<= 64)
 *        N bytes device name, UTF-8
 *
 *   0x02 DB_OFFER            announces the sender's database
 *        u64 LE  total size in bytes (<= MAX_DATABASE_BYTES)
 *        32      SHA-256 of the whole database
 *
 *   0x03 DB_DATA             one chunk of whichever transfer is in progress
 *        u32 LE  chunk index, starting at 0 and increasing by one
 *        u32 LE  chunk length L (1..65536)
 *        L bytes payload
 *
 *   0x04 DB_REQUEST          empty body; "I have finished sending, send me the merged database"
 *
 *   0x05 MERGED_DB           same body layout as DB_OFFER; announces the merged database that
 *                            follows as DB_DATA chunks
 *
 *   0x06 ERROR
 *        u16 LE  message length M (<= 1024)
 *        M bytes message, UTF-8. Advisory only: never shown as trusted content, control
 *        characters are stripped before display.
 *
 *  --------------------------------------------------------------------------------------------
 *  4. SESSION FLOW
 *  --------------------------------------------------------------------------------------------
 *      client                                   server
 *      ------                                   ------
 *      ClientHello              ->
 *                               <-              ServerHello
 *      confirm_c                ->
 *      HELLO                    ->
 *                               <-              HELLO
 *      DB_OFFER                 ->
 *      DB_DATA * n              ->
 *      DB_REQUEST               ->
 *                                               server merges the received database into its own
 *                                               with its Merger, saves atomically, then:
 *                               <-              MERGED_DB
 *                               <-              DB_DATA * m
 *      client verifies SHA-256, opens the result with its own composite key, keeps a .bak,
 *      replaces its local database, closes the socket.
 *
 *   Either side may send ERROR and close at any point. Receiving ERROR aborts the sync.
 *
 *   A client that is only verifying a pairing (right after the QR scan) closes the connection
 *   straight after the HELLO exchange; the server treats that as a successful pairing.
 *
 *  --------------------------------------------------------------------------------------------
 *  5. LIMITS AND TIMEOUTS
 *  --------------------------------------------------------------------------------------------
 *   handshake timeout      10 s        read timeout per frame   30 s
 *   chunk size             64 KiB      max frame plaintext      1 MiB
 *   max database           64 MiB      max device name          64 bytes
 *   max error message      1 KiB
 *
 *   A transfer whose declared size exceeds the maximum, whose chunk indexes are out of order, or
 *   whose accumulated bytes exceed the declared size is an error: abort, do not allocate.
 * ============================================================================================
 */

/** Wire constants. Changing any of these breaks compatibility with the desktop. */
object SyncWire {
    val MAGIC: ByteArray = "HISNSYN1".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1

    const val KEY_BYTES = 32
    const val NONCE_PREFIX_BYTES = 4
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val CONFIRM_BYTES = 32
    const val PAIRING_SECRET_BYTES = 32
    const val FINGERPRINT_BYTES = 32

    const val CLIENT_HELLO_BYTES = 42
    const val SERVER_HELLO_BYTES = 106
    const val TRANSCRIPT_BYTES = 106

    const val MAX_FRAME_PLAINTEXT = 1 shl 20            // 1 MiB
    const val MAX_FRAME_CIPHERTEXT = MAX_FRAME_PLAINTEXT + TAG_BYTES
    const val CHUNK_BYTES = 64 * 1024
    const val MAX_DATABASE_BYTES = 64L * 1024 * 1024
    const val MAX_DEVICE_NAME_BYTES = 64
    const val MAX_ERROR_BYTES = 1024

    const val HANDSHAKE_TIMEOUT_MS = 10_000
    const val READ_TIMEOUT_MS = 30_000

    // Message type codes.
    const val MSG_HELLO = 0x01
    const val MSG_DB_OFFER = 0x02
    const val MSG_DB_DATA = 0x03
    const val MSG_DB_REQUEST = 0x04
    const val MSG_MERGED_DB = 0x05
    const val MSG_ERROR = 0x06

    // HKDF labels. ASCII, no NUL terminator.
    const val INFO_KEY_C2S = "hisn-sync/1 key client->server"
    const val INFO_KEY_S2C = "hisn-sync/1 key server->client"
    const val INFO_NONCE_C2S = "hisn-sync/1 nonce client->server"
    const val INFO_NONCE_S2C = "hisn-sync/1 nonce server->client"
    const val INFO_CONFIRM_SERVER = "hisn-sync/1 confirm server"
    const val INFO_CONFIRM_CLIENT = "hisn-sync/1 confirm client"

    fun typeName(type: Int): String = when (type) {
        MSG_HELLO -> "HELLO"
        MSG_DB_OFFER -> "DB_OFFER"
        MSG_DB_DATA -> "DB_DATA"
        MSG_DB_REQUEST -> "DB_REQUEST"
        MSG_MERGED_DB -> "MERGED_DB"
        MSG_ERROR -> "ERROR"
        else -> "0x%02X".format(type)
    }
}

/** Any protocol-level failure: bad framing, bad authentication, hostile input, peer ERROR. */
class SyncProtocolException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The QR payload the desktop displays. [secret] and [fingerprint] are raw bytes, not base64. */
data class PairingPayload(
    val name: String,
    val host: String,
    val port: Int,
    val secret: ByteArray,
    val fingerprint: ByteArray,
) {
    override fun equals(other: Any?): Boolean = other is PairingPayload &&
        name == other.name && host == other.host && port == other.port &&
        secret.contentEquals(other.secret) && fingerprint.contentEquals(other.fingerprint)

    override fun hashCode(): Int =
        ((name.hashCode() * 31 + host.hashCode()) * 31 + port) * 31 + fingerprint.contentHashCode()

    companion object {
        /**
         * Parses and validates a scanned QR payload. Every field is checked before it is used —
         * a scanned code is untrusted input.
         */
        fun parse(payload: String): PairingPayload {
            val json = try {
                JSONObject(payload.trim())
            } catch (e: JSONException) {
                throw SyncProtocolException("This QR code is not a Hisn pairing code", e)
            }
            val version = json.optInt("v", -1)
            if (version != SyncWire.VERSION) {
                throw SyncProtocolException("This pairing code is for a different Hisn version (v$version)")
            }
            val name = json.optString("n").trim().take(64).ifBlank { "KeePassXC" }
            val host = json.optString("h").trim()
            if (host.isEmpty() || host.length > 255 || host.any { it.isWhitespace() }) {
                throw SyncProtocolException("The pairing code does not contain a usable address")
            }
            val port = json.optInt("p", -1)
            if (port !in 1..65535) {
                throw SyncProtocolException("The pairing code does not contain a usable port")
            }
            val secret = decodeFixed(json.optString("k"), SyncWire.PAIRING_SECRET_BYTES, "pairing secret")
            val fingerprint = decodeFixed(json.optString("f"), SyncWire.FINGERPRINT_BYTES, "fingerprint")
            return PairingPayload(name, host, port, secret, fingerprint)
        }

        private fun decodeFixed(value: String, size: Int, what: String): ByteArray {
            val bytes = try {
                Base64.decode(value.trim(), Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                throw SyncProtocolException("The pairing code contains a malformed $what", e)
            }
            if (bytes.size != size) {
                throw SyncProtocolException("The pairing code contains a malformed $what")
            }
            return bytes
        }
    }
}

/** A decoded frame payload. */
data class SyncMessage(val type: Int, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is SyncMessage && type == other.type && body.contentEquals(other.body)

    override fun hashCode(): Int = type * 31 + body.contentHashCode()
}

/** Announcement carried by DB_OFFER and MERGED_DB. */
data class DbOffer(val size: Long, val sha256: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is DbOffer && size == other.size && sha256.contentEquals(other.sha256)

    override fun hashCode(): Int = size.hashCode() * 31 + sha256.contentHashCode()
}

/** Encoders and decoders for every message body. Decoders reject malformed input loudly. */
object SyncMessages {

    fun encodeHello(deviceName: String): ByteArray {
        var name = deviceName.toByteArray(Charsets.UTF_8)
        if (name.size > SyncWire.MAX_DEVICE_NAME_BYTES) {
            name = name.copyOf(SyncWire.MAX_DEVICE_NAME_BYTES)
            // Do not split a multi-byte character in half.
            while (name.isNotEmpty() && (name[name.size - 1].toInt() and 0xC0) == 0x80) {
                name = name.copyOf(name.size - 1)
            }
        }
        val out = ByteArray(3 + name.size)
        putU16(out, 0, SyncWire.VERSION)
        out[2] = name.size.toByte()
        System.arraycopy(name, 0, out, 3, name.size)
        return out
    }

    /** @return the peer's device name. */
    fun decodeHello(body: ByteArray): String {
        if (body.size < 3) throw SyncProtocolException("Malformed HELLO from the other device")
        val version = getU16(body, 0)
        if (version != SyncWire.VERSION) {
            throw SyncProtocolException("The other device speaks sync protocol v$version, this app speaks v${SyncWire.VERSION}")
        }
        val length = body[2].toInt() and 0xFF
        if (length > SyncWire.MAX_DEVICE_NAME_BYTES || body.size < 3 + length) {
            throw SyncProtocolException("Malformed HELLO from the other device")
        }
        return String(body, 3, length, Charsets.UTF_8)
    }

    fun encodeOffer(size: Long, sha256: ByteArray): ByteArray {
        require(sha256.size == 32) { "SHA-256 must be 32 bytes" }
        val out = ByteArray(40)
        putU64(out, 0, size)
        System.arraycopy(sha256, 0, out, 8, 32)
        return out
    }

    fun decodeOffer(body: ByteArray): DbOffer {
        if (body.size < 40) throw SyncProtocolException("Malformed database offer")
        val size = getU64(body, 0)
        if (size <= 0 || size > SyncWire.MAX_DATABASE_BYTES) {
            throw SyncProtocolException("The other device offered a database of an implausible size ($size bytes)")
        }
        return DbOffer(size, body.copyOfRange(8, 40))
    }

    fun encodeChunk(index: Int, data: ByteArray, offset: Int, length: Int): ByteArray {
        require(length in 1..SyncWire.CHUNK_BYTES) { "Chunk length out of range" }
        val out = ByteArray(8 + length)
        putU32(out, 0, index)
        putU32(out, 4, length)
        System.arraycopy(data, offset, out, 8, length)
        return out
    }

    /** @return chunk index to payload. */
    fun decodeChunk(body: ByteArray): Pair<Int, ByteArray> {
        if (body.size < 8) throw SyncProtocolException("Malformed data chunk")
        val index = getU32(body, 0)
        val length = getU32(body, 4)
        if (index < 0) throw SyncProtocolException("Malformed data chunk")
        if (length < 1 || length > SyncWire.CHUNK_BYTES || body.size < 8 + length) {
            throw SyncProtocolException("Malformed data chunk")
        }
        return index to body.copyOfRange(8, 8 + length)
    }

    fun encodeError(message: String): ByteArray {
        var text = message.take(512).toByteArray(Charsets.UTF_8)
        if (text.size > SyncWire.MAX_ERROR_BYTES) text = text.copyOf(SyncWire.MAX_ERROR_BYTES)
        val out = ByteArray(2 + text.size)
        putU16(out, 0, text.size)
        System.arraycopy(text, 0, out, 2, text.size)
        return out
    }

    /** Decodes a peer error message and strips control characters — it is displayed to the user. */
    fun decodeError(body: ByteArray): String {
        if (body.size < 2) return "The other device reported an error"
        val length = getU16(body, 0)
        if (length > SyncWire.MAX_ERROR_BYTES || body.size < 2 + length) {
            return "The other device reported an error"
        }
        val text = String(body, 2, length, Charsets.UTF_8)
        return text.filter { !it.isISOControl() }.trim().ifBlank { "The other device reported an error" }
    }

    fun putU16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    fun putU32(out: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    fun putU64(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) out[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
    }

    fun getU16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    fun getU32(data: ByteArray, offset: Int): Int {
        var value = 0
        for (i in 0 until 4) value = value or ((data[offset + i].toInt() and 0xFF) shl (8 * i))
        return value
    }

    fun getU64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = value or ((data[offset + i].toLong() and 0xFF) shl (8 * i))
        return value
    }
}

/** X25519, HKDF-SHA256 and ChaCha20-Poly1305, kept in one place so the labels cannot drift. */
object SyncCrypto {

    private val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { random.nextBytes(it) }

    fun generateEphemeral(): X25519PrivateKeyParameters = X25519PrivateKeyParameters(random)

    /**
     * X25519 agreement.
     * @throws SyncProtocolException if the peer sent a low-order key (all-zero shared secret).
     */
    fun agree(privateKey: X25519PrivateKeyParameters, peerPublic: ByteArray): ByteArray {
        if (peerPublic.size != SyncWire.KEY_BYTES) {
            throw SyncProtocolException("The other device sent a malformed public key")
        }
        val shared = ByteArray(SyncWire.KEY_BYTES)
        try {
            X25519Agreement().apply {
                init(privateKey)
                calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), shared, 0)
            }
        } catch (e: IllegalArgumentException) {
            throw SyncProtocolException("The other device sent an unusable public key", e)
        } catch (e: IllegalStateException) {
            throw SyncProtocolException("The other device sent an unusable public key", e)
        }
        if (isZero(shared)) throw SyncProtocolException("The other device sent an unusable public key")
        return shared
    }

    /** HKDF-SHA256: extract with [salt] over [ikm], then expand [info] to [length] bytes. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: String, length: Int): ByteArray {
        val out = ByteArray(length)
        HKDFBytesGenerator(SHA256Digest()).apply {
            init(HKDFParameters(ikm, salt, info.toByteArray(Charsets.US_ASCII)))
            generateBytes(out, 0, length)
        }
        return out
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val engine = ChaCha20Poly1305()
        engine.init(true, AEADParameters(KeyParameter(key), SyncWire.TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(engine.getOutputSize(plaintext.size))
        var written = engine.processBytes(plaintext, 0, plaintext.size, out, 0)
        written += engine.doFinal(out, written)
        return if (written == out.size) out else out.copyOf(written)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        val engine = ChaCha20Poly1305()
        engine.init(false, AEADParameters(KeyParameter(key), SyncWire.TAG_BYTES * 8, nonce, aad))
        val out = ByteArray(engine.getOutputSize(ciphertext.size))
        return try {
            var written = engine.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            written += engine.doFinal(out, written)
            if (written == out.size) out else out.copyOf(written)
        } catch (e: Exception) {
            throw SyncProtocolException("A message from the other device failed authentication", e)
        }
    }

    /** nonce = 4-byte direction prefix || u64 big-endian counter. */
    fun nonce(prefix: ByteArray, counter: Long): ByteArray {
        val out = ByteArray(SyncWire.NONCE_BYTES)
        System.arraycopy(prefix, 0, out, 0, SyncWire.NONCE_PREFIX_BYTES)
        for (i in 0 until 8) {
            out[SyncWire.NONCE_PREFIX_BYTES + i] = ((counter ushr ((7 - i) * 8)) and 0xFF).toByte()
        }
        return out
    }

    fun fingerprintOf(staticPublicKey: ByteArray): ByteArray = sha256(staticPublicKey)

    fun encodeBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeBase64(value: String): ByteArray? = try {
        Base64.decode(value, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        null
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun isZero(bytes: ByteArray): Boolean {
        var acc = 0
        for (b in bytes) acc = acc or b.toInt()
        return acc == 0
    }

    fun wipe(bytes: ByteArray?) {
        if (bytes != null) java.util.Arrays.fill(bytes, 0)
    }
}

/**
 * An established, encrypted session: the handshake has completed and both directions have their
 * own key, nonce prefix and frame counter.
 *
 * Not thread safe — one coroutine drives a session from start to finish.
 */
class SyncSession private constructor(
    private val input: DataInputStream,
    private val output: OutputStream,
    private val sendKey: ByteArray,
    private val sendNoncePrefix: ByteArray,
    private val receiveKey: ByteArray,
    private val receiveNoncePrefix: ByteArray,
    /** The peer's static X25519 public key, already checked against the pinned fingerprint. */
    val peerStaticKey: ByteArray,
) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    val peerFingerprint: ByteArray get() = SyncCrypto.fingerprintOf(peerStaticKey)

    fun send(type: Int, body: ByteArray = EMPTY) {
        if (body.size + 1 > SyncWire.MAX_FRAME_PLAINTEXT) {
            throw SyncProtocolException("Refusing to send an oversized frame")
        }
        if (sendCounter == Long.MAX_VALUE) throw SyncProtocolException("Frame counter exhausted")

        val plaintext = ByteArray(body.size + 1)
        plaintext[0] = type.toByte()
        System.arraycopy(body, 0, plaintext, 1, body.size)

        val length = plaintext.size + SyncWire.TAG_BYTES
        val header = ByteArray(4)
        SyncMessages.putU32(header, 0, length)
        val sealed = SyncCrypto.seal(sendKey, SyncCrypto.nonce(sendNoncePrefix, sendCounter), header, plaintext)
        sendCounter++

        output.write(header)
        output.write(sealed)
        output.flush()
        SyncCrypto.wipe(plaintext)
    }

    /**
     * Reads the next frame.
     * @throws SyncProtocolException when the peer sends ERROR, malformed framing, or a frame that
     *         fails authentication.
     */
    fun receive(): SyncMessage {
        val header = ByteArray(4)
        try {
            input.readFully(header)
        } catch (e: EOFException) {
            throw SyncProtocolException("The other device closed the connection", e)
        }
        val length = SyncMessages.getU32(header, 0)
        if (length < SyncWire.TAG_BYTES + 1 || length > SyncWire.MAX_FRAME_CIPHERTEXT) {
            throw SyncProtocolException("The other device sent a frame with an invalid length")
        }
        if (receiveCounter == Long.MAX_VALUE) throw SyncProtocolException("Frame counter exhausted")

        val sealed = ByteArray(length)
        try {
            input.readFully(sealed)
        } catch (e: EOFException) {
            throw SyncProtocolException("The connection dropped in the middle of a message", e)
        }
        val plaintext = SyncCrypto.open(
            receiveKey,
            SyncCrypto.nonce(receiveNoncePrefix, receiveCounter),
            header,
            sealed,
        )
        receiveCounter++

        val type = plaintext[0].toInt() and 0xFF
        val body = plaintext.copyOfRange(1, plaintext.size)
        if (type == SyncWire.MSG_ERROR) {
            throw SyncProtocolException(SyncMessages.decodeError(body))
        }
        return SyncMessage(type, body)
    }

    /** Reads the next frame and insists it is [expected]. */
    fun expect(expected: Int): SyncMessage {
        val message = receive()
        if (message.type != expected) {
            throw SyncProtocolException(
                "The other device sent ${SyncWire.typeName(message.type)} where ${SyncWire.typeName(expected)} was expected"
            )
        }
        return message
    }

    /** Best-effort notification before hanging up; never throws. */
    fun sendError(message: String) {
        runCatching { send(SyncWire.MSG_ERROR, SyncMessages.encodeError(message)) }
    }

    fun close() {
        SyncCrypto.wipe(sendKey)
        SyncCrypto.wipe(receiveKey)
    }

    companion object {
        private val EMPTY = ByteArray(0)

        /**
         * Runs the client half of the handshake over an already connected socket's streams.
         *
         * @param pairingSecret the 32 bytes from the QR code.
         * @param expectedFingerprint SHA-256 of the desktop's static key: from the QR code when
         *        pairing, from the stored device record afterwards. Never null — an unauthenticated
         *        sync is not offered.
         */
        fun clientHandshake(
            rawInput: InputStream,
            rawOutput: OutputStream,
            pairingSecret: ByteArray,
            expectedFingerprint: ByteArray,
        ): SyncSession {
            require(pairingSecret.size == SyncWire.PAIRING_SECRET_BYTES) { "Pairing secret must be 32 bytes" }
            require(expectedFingerprint.size == SyncWire.FINGERPRINT_BYTES) { "Fingerprint must be 32 bytes" }

            val input = DataInputStream(rawInput)
            val ephemeral = SyncCrypto.generateEphemeral()
            val clientPublic = ephemeral.generatePublicKey().encoded

            val clientHello = ByteArray(SyncWire.CLIENT_HELLO_BYTES)
            System.arraycopy(SyncWire.MAGIC, 0, clientHello, 0, SyncWire.MAGIC.size)
            SyncMessages.putU16(clientHello, 8, SyncWire.VERSION)
            System.arraycopy(clientPublic, 0, clientHello, 10, SyncWire.KEY_BYTES)
            rawOutput.write(clientHello)
            rawOutput.flush()

            val serverHello = ByteArray(SyncWire.SERVER_HELLO_BYTES)
            try {
                input.readFully(serverHello)
            } catch (e: EOFException) {
                throw SyncProtocolException("The desktop closed the connection during the handshake", e)
            }
            for (i in SyncWire.MAGIC.indices) {
                if (serverHello[i] != SyncWire.MAGIC[i]) {
                    throw SyncProtocolException("The device on that port is not Hisn sync")
                }
            }
            val peerVersion = SyncMessages.getU16(serverHello, 8)
            if (peerVersion != SyncWire.VERSION) {
                throw SyncProtocolException("The desktop speaks sync protocol v$peerVersion, this app speaks v${SyncWire.VERSION}")
            }
            val serverEphemeral = serverHello.copyOfRange(10, 42)
            val serverStatic = serverHello.copyOfRange(42, 74)
            val serverConfirm = serverHello.copyOfRange(74, 106)

            if (!SyncCrypto.constantTimeEquals(SyncCrypto.fingerprintOf(serverStatic), expectedFingerprint)) {
                throw SyncProtocolException(
                    "The desktop's identity key does not match the one this device paired with"
                )
            }

            val ee = SyncCrypto.agree(ephemeral, serverEphemeral)
            val es = SyncCrypto.agree(ephemeral, serverStatic)

            val transcript = ByteArray(SyncWire.TRANSCRIPT_BYTES)
            System.arraycopy(SyncWire.MAGIC, 0, transcript, 0, 8)
            SyncMessages.putU16(transcript, 8, SyncWire.VERSION)
            System.arraycopy(clientPublic, 0, transcript, 10, 32)
            System.arraycopy(serverEphemeral, 0, transcript, 42, 32)
            System.arraycopy(serverStatic, 0, transcript, 74, 32)

            val salt = SyncCrypto.sha256(transcript)
            val ikm = ByteArray(96)
            System.arraycopy(ee, 0, ikm, 0, 32)
            System.arraycopy(es, 0, ikm, 32, 32)
            System.arraycopy(pairingSecret, 0, ikm, 64, 32)

            try {
                val keyC2S = SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_KEY_C2S, SyncWire.KEY_BYTES)
                val keyS2C = SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_KEY_S2C, SyncWire.KEY_BYTES)
                val nonceC2S = SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_NONCE_C2S, SyncWire.NONCE_PREFIX_BYTES)
                val nonceS2C = SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_NONCE_S2C, SyncWire.NONCE_PREFIX_BYTES)
                val confirmServer = SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_CONFIRM_SERVER, SyncWire.CONFIRM_BYTES)
                val confirmClient = SyncCrypto.hkdf(salt, ikm, SyncWire.INFO_CONFIRM_CLIENT, SyncWire.CONFIRM_BYTES)

                if (!SyncCrypto.constantTimeEquals(serverConfirm, confirmServer)) {
                    // Either the pairing code was not the one this desktop is showing, or someone
                    // is impersonating it.
                    throw SyncProtocolException("The pairing code does not match this desktop — scan the code again")
                }
                rawOutput.write(confirmClient)
                rawOutput.flush()

                return SyncSession(input, rawOutput, keyC2S, nonceC2S, keyS2C, nonceS2C, serverStatic)
            } finally {
                SyncCrypto.wipe(ee)
                SyncCrypto.wipe(es)
                SyncCrypto.wipe(ikm)
            }
        }
    }
}
