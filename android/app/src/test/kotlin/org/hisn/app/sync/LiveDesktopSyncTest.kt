package org.hisn.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/**
 * Runs this client's real handshake against a **running desktop server**, over a real socket.
 *
 * The vector test next door proves both implementations compute the same key schedule. This one
 * proves the two actually talk: framing, message codes, nonce direction and the confirmation
 * exchange all have to line up or the connection dies.
 *
 * It is skipped unless a pairing payload is supplied, so ordinary test runs stay hermetic:
 *
 *   1. open Hisn on the desktop, unlock a database, choose «المزامنة المحلية»
 *   2. decode the QR code (its JSON is also printed under the code) into a file
 *   3. run with `-Dhisn.sync.payload=/path/to/payload.json`, or put the JSON in
 *      the `HISN_SYNC_PAYLOAD` environment variable
 *
 * The desktop treats a client that disconnects after HELLO as a successful pairing, so this test
 * never modifies the database on the other end.
 */
class LiveDesktopSyncTest {

    private fun payloadJson(): String? {
        System.getProperty("hisn.sync.payload")?.let { path ->
            val f = File(path)
            if (f.isFile) return f.readText().trim()
        }
        return System.getenv("HISN_SYNC_PAYLOAD")?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Minimal field extraction: the payload is one flat JSON object with no nesting. */
    private fun field(json: String, key: String): String =
        Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
            ?: Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)
            ?: error("pairing payload has no field \"$key\": $json")

    @Test
    fun handshakesWithRunningDesktopServer() {
        val json = payloadJson()
        assumeTrue("no pairing payload supplied; skipping the live desktop test", json != null)
        json!!

        assertEquals("unexpected protocol version in the pairing payload", "1", field(json, "v"))
        val host = field(json, "h")
        val port = field(json, "p").toInt()
        val secret = Base64.getDecoder().decode(field(json, "k"))
        val fingerprint = Base64.getDecoder().decode(field(json, "f"))
        assertEquals(SyncWire.PAIRING_SECRET_BYTES, secret.size)
        assertEquals(SyncWire.FINGERPRINT_BYTES, fingerprint.size)

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 30_000

            // Fails loudly if the server's magic, version, static-key fingerprint or confirmation
            // tag disagree with ours — i.e. if the two implementations have drifted apart.
            val session = SyncSession.clientHandshake(
                socket.getInputStream(),
                socket.getOutputStream(),
                secret,
                fingerprint,
            )

            session.send(SyncWire.MSG_HELLO, SyncMessages.encodeHello("Hisn test client"))
            val reply = session.receive()
            assertEquals(
                "the desktop should answer HELLO with HELLO, got ${SyncWire.typeName(reply.type)}",
                SyncWire.MSG_HELLO,
                reply.type,
            )

            val desktopName = SyncMessages.decodeHello(reply.body)
            assertTrue("the desktop should identify itself", desktopName.isNotEmpty())
            println("live sync handshake OK — paired with \"$desktopName\" at $host:$port")

            session.close()
        }
    }

    /** A wrong pairing secret must be rejected: this is what stops a stranger on the WiFi. */
    @Test
    fun rejectsWrongPairingSecret() {
        val json = payloadJson()
        assumeTrue("no pairing payload supplied; skipping the live desktop test", json != null)
        json!!

        val host = field(json!!, "h")
        val port = field(json, "p").toInt()
        val fingerprint = Base64.getDecoder().decode(field(json, "f"))
        val wrongSecret = MessageDigest.getInstance("SHA-256").digest("not-the-secret".toByteArray())

        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 30_000
            val failed = runCatching {
                SyncSession.clientHandshake(
                    socket.getInputStream(), socket.getOutputStream(), wrongSecret, fingerprint,
                )
            }.isFailure
            assertTrue("a client without the QR secret must not complete the handshake", failed)
        }
    }
}
