package org.hisn.app.kdbx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Reads a database produced by the Hisn *desktop* app (the KeePassXC-derived C++ build) and
 * checks that this Kotlin implementation sees exactly what the desktop wrote.
 *
 * This is the test that guards the promise the product makes: the same .kdbx file opens on the
 * laptop and on the phone. A round-trip test cannot catch a shared misreading of the format —
 * only a fixture written by the other implementation can.
 *
 * Fixture: app/src/test/resources/desktop-kdbx31.kdbx, created with
 *   hisn-cli db-create test.kdbx -p            (password "testpass123")
 *   hisn-cli mkdir  test.kdbx "البنك"
 *   hisn-cli add    test.kdbx -u ahmad@example.com --url https://bank.example "البنك/حسابي"
 * The Arabic group and entry names are deliberate: they prove UTF-8 survives the XML layer in
 * both directions, which is the whole point of an Arabic-first password manager.
 */
class DesktopCompatTest {

    private val password = "testpass123"

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader?.getResourceAsStream(name)?.use { it.readBytes() }
            ?: fail("missing test fixture: $name").let { ByteArray(0) }

    @Test
    fun readsDatabaseWrittenByDesktopApp() {
        val db = KdbxReader.read(fixture("desktop-kdbx31.kdbx"), CompositeKey.build(password, null as ByteArray?))

        val group = db.root.groupsRecursive().firstOrNull { it.name == "البنك" }
        assertNotNull("the Arabic group written by the desktop app should be present", group)

        val entry = db.allEntries().firstOrNull { it.title == "حسابي" }
        assertNotNull("the Arabic entry written by the desktop app should be present", entry)
        entry!!

        assertEquals("ahmad@example.com", entry.username)
        assertEquals("SecretPass!42", entry.password)
        assertEquals("https://bank.example", entry.url)
        assertEquals("the entry should live under the Arabic group", "البنك", entry.parent?.name)
        assertTrue("the password field must be marked protected", entry.fields[Entry.PASSWORD]!!.protected)
    }

    @Test
    fun rejectsWrongPassword() {
        try {
            KdbxReader.read(fixture("desktop-kdbx31.kdbx"), CompositeKey.build("not-the-password", null as ByteArray?))
            fail("a wrong password must not decrypt the database")
        } catch (e: Exception) {
            // Either a dedicated wrong-credentials error or a generic format failure is acceptable;
            // what matters is that no database is produced.
            assertTrue(
                "expected a credentials/format failure, got ${e::class.simpleName}: ${e.message}",
                e is KdbxWrongCredentialsException || e is KdbxException
            )
        }
    }

    /**
     * Re-encrypts the desktop's database with this implementation and reads it back, which is the
     * exact path a sync takes: the phone receives desktop bytes, merges, and writes a new file that
     * the desktop must then be able to open.
     */
    @Test
    fun rewritesDesktopDatabaseWithoutLoss() {
        val key = CompositeKey.build(password, null as ByteArray?)
        val original = KdbxReader.read(fixture("desktop-kdbx31.kdbx"), key)

        val rewritten = KdbxReader.read(KdbxWriter.write(original, key), key)

        assertEquals(original.allEntries().size, rewritten.allEntries().size)
        assertEquals(
            original.root.groupsRecursive().map { it.name }.sorted(),
            rewritten.root.groupsRecursive().map { it.name }.sorted()
        )
        val before = original.allEntries().first { it.title == "حسابي" }
        val after = rewritten.allEntries().first { it.title == "حسابي" }
        assertEquals(before.username, after.username)
        assertEquals(before.password, after.password)
        assertEquals(before.url, after.url)
        assertEquals(before.uuid, after.uuid)
    }
}
