package org.hisn.app.kdbx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Opens databases that the **KeePassXC project itself** ships as its test corpus, with the same
 * passwords and the same expected contents its own C++ tests assert on.
 *
 * This answers the question a user actually cares about: *will the vault I already have open in
 * this app?* A round-trip test cannot answer it — only files produced by the other implementation
 * can. The fixtures under `resources/keepassxc/` are copied verbatim from `tests/data/` of the
 * upstream project, and the expectations below are lifted from `tests/TestKdbx3.cpp` and
 * `tests/TestKdbx4.cpp`, so a divergence shows up here as a failure rather than as a support ticket.
 *
 * Coverage spans the formats a real user is likely to hold: KDBX 3.0, 3.1 and 4.0; AES and
 * ChaCha20; compressed and uncompressed; protected (in-memory-encrypted) fields; and a non-ASCII
 * master password.
 */
class KeePassXCVaultsTest {

    private fun open(name: String, password: String): KdbxDatabase {
        val bytes = javaClass.classLoader?.getResourceAsStream("keepassxc/$name")?.use { it.readBytes() }
            ?: fail("missing fixture keepassxc/$name").let { ByteArray(0) }
        return KdbxReader.read(bytes, CompositeKey.build(password, null as ByteArray?))
    }

    /** KDBX 3.0 — the format KeePassXC wrote before 2.3. Old vaults really are still out there. */
    @Test
    fun opensKdbx30() {
        val db = open("Format300.kdbx", "a")
        assertEquals("Test Database Format 0x00030000", db.meta.databaseName)
        assertTrue("the database should contain entries", db.allEntries().isNotEmpty())
    }

    /** KDBX 4.0 with ChaCha20 and Argon2 — what a current KeePassXC creates by default. */
    @Test
    fun opensKdbx40() {
        val db = open("Format400.kdbx", "t")
        assertEquals("Format400", db.meta.databaseName)
        assertTrue(db.params.isKdbx4)
        val entry = db.allEntries().firstOrNull()
        assertNotNull("the database should contain an entry", entry)
    }

    /** GZip-compressed payload, and an empty master password — both legal and both easy to break. */
    @Test
    fun opensCompressedDatabase() {
        val db = open("Compressed.kdbx", "")
        assertEquals("Compressed", db.meta.databaseName)
        assertEquals(Kdbx.COMPRESSION_GZIP, db.params.compression)
    }

    /**
     * A master password outside ASCII (Greek delta, o-umlaut, Arabic dad). The composite key is
     * built from UTF-8 bytes; encoding it any other way silently produces a different key, and the
     * user simply cannot get in.
     */
    @Test
    fun opensDatabaseWithNonAsciiPassword() {
        val db = open("NonAscii.kdbx", "Δöض")
        assertEquals("NonAsciiTest", db.meta.databaseName)
        assertEquals(Kdbx.COMPRESSION_NONE, db.params.compression)
    }

    /**
     * Fields marked Protected are encrypted with the inner random stream, which has to be consumed
     * in document order. Read them out of order and you get plausible-looking rubbish rather than
     * an error, so the assertion is on the actual plaintext.
     */
    @Test
    fun readsProtectedFields() {
        val db = open("ProtectedStrings.kdbx", "masterpw")
        assertEquals("Protected Strings Test", db.meta.databaseName)

        val entry = db.allEntries().first()
        assertEquals("Sample Entry", entry.title)
        assertEquals("Protected User Name", entry.username)
        assertEquals("ProtectedPassword", entry.password)
        // Upstream TestKdbx3::testProtectedStrings pins these two: one protected custom field and
        // one plain, so a reader that mixes up the inner-stream ordering fails on the pair.
        assertEquals("ABC", entry.fields["TestProtected"]?.value)
        assertEquals("DEF", entry.fields["TestUnprotected"]?.value)
        // The protection flag itself has to survive too: it decides whether the value is encrypted
        // again on save, so losing it would quietly downgrade a protected field to plaintext.
        assertTrue(entry.fields["TestProtected"]!!.protected)
        assertFalse(entry.fields["TestUnprotected"]!!.protected)

        assertTrue("the password field must be marked protected", entry.fields[Entry.PASSWORD]!!.protected)
    }

    /** The database the upstream GUI tests drive; a broad, ordinary vault. */
    @Test
    fun opensNewDatabaseFixture() {
        val db = open("NewDatabase.kdbx", "a")
        assertTrue("expected a populated group tree", db.root.groupsRecursive().size > 1)
        assertTrue("expected entries", db.allEntries().isNotEmpty())
    }

    /** A wrong password must fail on every one of them, not just the modern format. */
    @Test
    fun rejectsWrongPasswordOnEveryFormat() {
        for (name in listOf("Format300.kdbx", "Format400.kdbx", "ProtectedStrings.kdbx")) {
            val failed = runCatching { open(name, "definitely-not-the-password") }.isFailure
            assertTrue("$name decrypted with a wrong password", failed)
        }
    }

    /**
     * The round trip that matters for sync: read a KeePassXC vault, write it back with this
     * implementation, and read it again with everything intact. The desktop app has to be able to
     * open what the phone saves, and this is the closest a JVM test can get to proving it.
     */
    @Test
    fun rewritesKeePassXCVaultWithoutLoss() {
        val key = CompositeKey.build("masterpw", null as ByteArray?)
        val original = open("ProtectedStrings.kdbx", "masterpw")

        val rewritten = KdbxReader.read(KdbxWriter.write(original, key), key)

        assertEquals(original.meta.databaseName, rewritten.meta.databaseName)
        assertEquals(original.allEntries().size, rewritten.allEntries().size)
        val before = original.allEntries().first()
        val after = rewritten.allEntries().first()
        assertEquals(before.title, after.title)
        assertEquals(before.username, after.username)
        assertEquals(before.password, after.password)
        assertEquals(before.uuid, after.uuid)
        assertEquals(before.fields["TestProtected"]?.value, after.fields["TestProtected"]?.value)
        assertTrue(after.fields[Entry.PASSWORD]!!.protected)
    }
}
