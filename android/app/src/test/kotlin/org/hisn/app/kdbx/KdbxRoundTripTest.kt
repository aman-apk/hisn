package org.hisn.app.kdbx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

/**
 * Writes a database with every field the model can hold, reads it back, and compares the two.
 *
 * This is the test that matters most in the project: a password manager that loses a field on
 * save destroys data the user cannot recover. Both file formats the writer supports are covered,
 * because KDBX 3.1 stores attachments, timestamps and protection in places KDBX 4 does not.
 *
 * The KDF settings are deliberately tiny (1 MiB / 2 passes of Argon2, 1000 AES rounds). They have
 * no bearing on what the format writes — only on how long the test takes — and the real defaults
 * would add tens of seconds per case.
 */
class KdbxRoundTripTest {

    private val password = "قلعةٌ حصينة · correct-horse-42"
    private val keyFileBytes = ByteArray(32) { (it * 7 + 3).toByte() }

    private fun credentials() = CompositeKey.build(password, keyFileBytes)

    private fun kdbx41Params() = KdbxParams(
        version = Kdbx.FILE_VERSION_4_1,
        cipherUuid = Kdbx.CIPHER_AES256,
        compression = Kdbx.COMPRESSION_GZIP,
        kdfUuid = Kdbx.KDF_ARGON2D,
        kdfRounds = 2,
        kdfMemory = 1L shl 20,
        kdfParallelism = 1,
        kdfVersion = Kdbx.ARGON2_VERSION_13,
    )

    private fun kdbx31Params() = KdbxParams(
        version = Kdbx.FILE_VERSION_3_1,
        cipherUuid = Kdbx.CIPHER_AES256,
        compression = Kdbx.COMPRESSION_GZIP,
        kdfUuid = Kdbx.KDF_AES_KDBX3,
        kdfRounds = 1_000,
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun kdbx41WithArgon2dAndAes256RoundTrips() {
        val original = sampleDatabase(kdbx41Params())
        val bytes = KdbxWriter.write(original, credentials())

        assertSignature(bytes, Kdbx.FILE_VERSION_4_1)

        val reloaded = KdbxReader.read(bytes, credentials())
        assertDatabasesMatch(original, reloaded)
    }

    @Test
    fun kdbx31RoundTrips() {
        val original = sampleDatabase(kdbx31Params())
        val bytes = KdbxWriter.write(original, credentials())

        assertSignature(bytes, Kdbx.FILE_VERSION_3_1)

        val reloaded = KdbxReader.read(bytes, credentials())
        assertDatabasesMatch(original, reloaded)
    }

    @Test
    fun uncompressedChaCha20Argon2idRoundTrips() {
        val params = kdbx41Params().copy(
            cipherUuid = Kdbx.CIPHER_CHACHA20,
            compression = Kdbx.COMPRESSION_NONE,
            kdfUuid = Kdbx.KDF_ARGON2ID,
        )
        val original = sampleDatabase(params)

        val reloaded = KdbxReader.read(KdbxWriter.write(original, credentials()), credentials())

        assertEquals(Kdbx.CIPHER_CHACHA20, reloaded.params.cipherUuid)
        assertEquals(Kdbx.COMPRESSION_NONE, reloaded.params.compression)
        assertEquals(Kdbx.KDF_ARGON2ID, reloaded.params.kdfUuid)
        assertDatabasesMatch(original, reloaded)
    }

    @Test
    fun writingTwiceProducesDifferentBytesButTheSameDatabase() {
        val original = sampleDatabase(kdbx41Params())
        val first = KdbxWriter.write(original, credentials())
        val second = KdbxWriter.write(original, credentials())

        // Fresh master seed, IV and KDF salt on every save, as the desktop does.
        assertTrue(
            "Two saves produced byte-identical files; the per-save randomness is not being regenerated",
            !first.contentEquals(second),
        )
        assertDatabasesMatch(
            KdbxReader.read(first, credentials()),
            KdbxReader.read(second, credentials()),
        )
    }

    @Test
    fun wrongPasswordIsRejectedForKdbx4() {
        val bytes = KdbxWriter.write(sampleDatabase(kdbx41Params()), credentials())
        assertRejects(bytes, CompositeKey.build("${password}x", keyFileBytes))
    }

    @Test
    fun wrongPasswordIsRejectedForKdbx31() {
        val bytes = KdbxWriter.write(sampleDatabase(kdbx31Params()), credentials())
        assertRejects(bytes, CompositeKey.build("${password}x", keyFileBytes))
    }

    @Test
    fun missingKeyFileIsRejected() {
        val bytes = KdbxWriter.write(sampleDatabase(kdbx41Params()), credentials())
        assertRejects(bytes, CompositeKey.build(password, null as ByteArray?))
    }

    @Test
    fun wrongKeyFileIsRejected() {
        val bytes = KdbxWriter.write(sampleDatabase(kdbx41Params()), credentials())
        val otherKeyFile = ByteArray(32) { (it * 11 + 1).toByte() }
        assertRejects(bytes, CompositeKey.build(password, otherKeyFile))
    }

    @Test
    fun headerCanBeInspectedWithoutCredentials() {
        val bytes = KdbxWriter.write(sampleDatabase(kdbx41Params()), credentials())

        val header = KdbxReader.readHeaderOnly(bytes)

        assertEquals(Kdbx.FILE_VERSION_4_1, header.params.version)
        assertEquals(Kdbx.CIPHER_AES256, header.params.cipherUuid)
        assertEquals(Kdbx.KDF_ARGON2D, header.params.kdfUuid)
        assertEquals(2L, header.params.kdfRounds)
        assertEquals(1L shl 20, header.params.kdfMemory)
        assertEquals(1, header.params.kdfParallelism)
    }

    @Test
    fun truncatedFileFailsAsCorruptNotAsWrongPassword() {
        val bytes = KdbxWriter.write(sampleDatabase(kdbx41Params()), credentials())
        val truncated = bytes.copyOf(bytes.size / 2)

        try {
            KdbxReader.read(truncated, credentials())
            fail("A truncated database was accepted")
        } catch (e: KdbxWrongCredentialsException) {
            fail("A truncated database was reported as wrong credentials: ${e.message}")
        } catch (e: KdbxException) {
            assertNotNull(e.message)
        }
    }

    // ------------------------------------------------------------- assertions

    private fun assertRejects(bytes: ByteArray, wrongKey: CompositeKey) {
        try {
            KdbxReader.read(bytes, wrongKey)
            fail("The database opened with the wrong credentials")
        } catch (e: KdbxWrongCredentialsException) {
            assertNotNull(e.message)
        }
    }

    private fun assertSignature(bytes: ByteArray, expectedVersion: Int) {
        assertEquals(Kdbx.SIGNATURE_1, le32(bytes, 0))
        assertEquals(Kdbx.SIGNATURE_2, le32(bytes, 4))
        assertEquals(expectedVersion, le32(bytes, 8))
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun assertDatabasesMatch(expected: KdbxDatabase, actual: KdbxDatabase) {
        val kdbx4 = expected.params.isKdbx4
        val kdbx41 = expected.params.version >= Kdbx.FILE_VERSION_4_1

        // -- meta
        val em = expected.meta
        val am = actual.meta
        assertEquals(em.databaseName, am.databaseName)
        assertEquals(em.databaseNameChanged, am.databaseNameChanged)
        assertEquals(em.databaseDescription, am.databaseDescription)
        assertEquals(em.databaseDescriptionChanged, am.databaseDescriptionChanged)
        assertEquals(em.defaultUserName, am.defaultUserName)
        assertEquals(em.defaultUserNameChanged, am.defaultUserNameChanged)
        assertEquals(em.maintenanceHistoryDays, am.maintenanceHistoryDays)
        assertEquals(em.color, am.color)
        assertEquals(em.masterKeyChanged, am.masterKeyChanged)
        assertEquals(em.masterKeyChangeRec, am.masterKeyChangeRec)
        assertEquals(em.masterKeyChangeForce, am.masterKeyChangeForce)
        assertEquals(em.recycleBinEnabled, am.recycleBinEnabled)
        assertEquals(em.recycleBinUuid, am.recycleBinUuid)
        assertEquals(em.recycleBinChanged, am.recycleBinChanged)
        assertEquals(em.entryTemplatesGroup, am.entryTemplatesGroup)
        assertEquals(em.historyMaxItems, am.historyMaxItems)
        assertEquals(em.historyMaxSize, am.historyMaxSize)
        assertEquals(em.lastSelectedGroup, am.lastSelectedGroup)
        assertEquals(em.lastTopVisibleGroup, am.lastTopVisibleGroup)
        assertEquals(em.protectTitle, am.protectTitle)
        assertEquals(em.protectUserName, am.protectUserName)
        assertEquals(em.protectPassword, am.protectPassword)
        assertEquals(em.protectUrl, am.protectUrl)
        assertEquals(em.protectNotes, am.protectNotes)
        assertEquals(em.customData, am.customData)
        // SettingsChanged has no home in the KDBX 3.1 schema.
        if (kdbx4) assertEquals(em.settingsChanged, am.settingsChanged)

        assertEquals(em.customIcons.size, am.customIcons.size)
        em.customIcons.zip(am.customIcons).forEach { (expectedIcon, actualIcon) ->
            assertEquals(expectedIcon.uuid, actualIcon.uuid)
            assertArrayEquals(expectedIcon.data, actualIcon.data)
            if (kdbx41) {
                assertEquals(expectedIcon.name, actualIcon.name)
                assertEquals(expectedIcon.lastModified, actualIcon.lastModified)
            }
        }

        // -- tombstones
        assertEquals(
            expected.deletedObjects.map { it.uuid to it.deletionTime }.toSet(),
            actual.deletedObjects.map { it.uuid to it.deletionTime }.toSet(),
        )

        // -- tree shape
        val expectedGroups = expected.root.groupsRecursive()
        val actualGroups = actual.root.groupsRecursive()
        assertEquals(
            "group tree shape changed",
            expectedGroups.map { it.path() },
            actualGroups.map { it.path() },
        )

        expectedGroups.zip(actualGroups).forEach { (e, a) ->
            assertEquals(e.uuid, a.uuid)
            assertEquals(e.name, a.name)
            assertEquals(e.notes, a.notes)
            assertEquals(e.iconId, a.iconId)
            assertEquals(e.customIconUuid, a.customIconUuid)
            assertEquals(e.isExpanded, a.isExpanded)
            assertEquals(e.defaultAutoTypeSequence, a.defaultAutoTypeSequence)
            assertEquals(e.enableAutoType, a.enableAutoType)
            assertEquals(e.enableSearching, a.enableSearching)
            assertEquals(e.lastTopVisibleEntry, a.lastTopVisibleEntry)
            assertEquals(e.tags, a.tags)
            assertTimesMatch(e.times, a.times)
            // Per-group custom data only exists in the KDBX 4 schema.
            if (kdbx4) assertEquals(e.customData, a.customData)
            if (kdbx41) assertEquals(e.previousParentGroup, a.previousParentGroup)
        }

        // -- entries
        val expectedEntries = expected.root.entriesRecursive()
        val actualEntries = actual.root.entriesRecursive()
        assertEquals(expectedEntries.size, actualEntries.size)

        expectedEntries.zip(actualEntries).forEach { (e, a) ->
            assertEntriesMatch(expected, actual, e, a, kdbx4, kdbx41)
            assertEquals("history length for ${e.title}", e.history.size, a.history.size)
            e.history.zip(a.history).forEach { (eh, ah) ->
                assertEntriesMatch(expected, actual, eh, ah, kdbx4, kdbx41)
            }
        }
    }

    private fun assertEntriesMatch(
        expectedDb: KdbxDatabase,
        actualDb: KdbxDatabase,
        e: Entry,
        a: Entry,
        kdbx4: Boolean,
        kdbx41: Boolean,
    ) {
        val where = "entry ${e.uuid}"
        assertEquals(where, e.uuid, a.uuid)
        assertEquals(where, e.iconId, a.iconId)
        assertEquals(where, e.customIconUuid, a.customIconUuid)
        assertEquals(where, e.foregroundColor, a.foregroundColor)
        assertEquals(where, e.backgroundColor, a.backgroundColor)
        assertEquals(where, e.overrideUrl, a.overrideUrl)
        assertEquals(where, e.tags, a.tags)
        assertEquals(where, e.fields, a.fields)
        assertTimesMatch(e.times, a.times)

        assertEquals(where, e.attachments.map { it.name }, a.attachments.map { it.name })
        e.attachments.zip(a.attachments).forEach { (expectedAttachment, actualAttachment) ->
            assertArrayEquals(
                "payload of ${expectedAttachment.name}",
                expectedDb.binaryFor(expectedAttachment.ref),
                actualDb.binaryFor(actualAttachment.ref),
            )
        }

        val expectedAutoType = e.autoType
        val actualAutoType = a.autoType
        assertNotNull("$where lost its AutoType block", actualAutoType)
        if (expectedAutoType != null && actualAutoType != null) {
            assertEquals(where, expectedAutoType.enabled, actualAutoType.enabled)
            assertEquals(where, expectedAutoType.dataTransferObfuscation, actualAutoType.dataTransferObfuscation)
            assertEquals(where, expectedAutoType.defaultSequence, actualAutoType.defaultSequence)
            assertEquals(
                where,
                expectedAutoType.associations.map { it.window to it.sequence },
                actualAutoType.associations.map { it.window to it.sequence },
            )
        }

        if (kdbx4) assertEquals(where, e.customData, a.customData)
        if (kdbx41) {
            assertEquals(where, e.qualityCheck, a.qualityCheck)
            assertEquals(where, e.previousParentGroup, a.previousParentGroup)
        }
    }

    private fun assertTimesMatch(expected: Times, actual: Times) {
        assertEquals(expected.creationTime, actual.creationTime)
        assertEquals(expected.lastModificationTime, actual.lastModificationTime)
        assertEquals(expected.lastAccessTime, actual.lastAccessTime)
        assertEquals(expected.locationChanged, actual.locationChanged)
        assertEquals(expected.usageCount, actual.usageCount)
        assertEquals(expected.expires, actual.expires)
        // The format has no way to say "no expiry date"; the writer falls back to the creation
        // time, so that is what must come back for entries that never expire.
        assertEquals(expected.expiryTime ?: expected.creationTime, actual.expiryTime)
    }

    // ---------------------------------------------------------------- fixture

    private val base = 1_700_000_000L

    private fun times(offset: Long, expires: Boolean = false, expiry: Long? = null) = Times(
        lastModificationTime = base + offset + 1,
        creationTime = base + offset,
        lastAccessTime = base + offset + 2,
        expiryTime = expiry,
        expires = expires,
        usageCount = offset.toInt() % 17,
        locationChanged = base + offset + 3,
    )

    private fun id(seed: Int): UUID = UUID(0x4869_736E_0000_0000L or seed.toLong(), seed.toLong())

    /**
     * A database that exercises every branch of the writer: nested groups, a recycle bin, custom
     * icons, protected and plain fields, non-ASCII text, an attachment shared between an entry and
     * its own history, tombstones, and tri-state group flags.
     */
    private fun sampleDatabase(params: KdbxParams): KdbxDatabase {
        val db = KdbxDatabase(params = params)

        val certificate = ByteArray(600) { (it * 31 + 7).toByte() }
        val note = "ملف نصي مرفق\n".toByteArray(Charsets.UTF_8)
        val certificateRef = db.addBinary(certificate)
        val noteRef = db.addBinary(note)

        db.meta.apply {
            generator = "Hisn round-trip test"
            databaseName = "خزنة الاختبار"
            databaseNameChanged = base
            databaseDescription = "A fixture with every field filled in — بما في ذلك العربية"
            databaseDescriptionChanged = base + 1
            defaultUserName = "زينب"
            defaultUserNameChanged = base + 2
            maintenanceHistoryDays = 180
            color = "#C2622B"
            masterKeyChanged = base + 3
            masterKeyChangeRec = 90
            masterKeyChangeForce = 365
            recycleBinEnabled = true
            recycleBinUuid = id(3)
            recycleBinChanged = base + 4
            entryTemplatesGroup = id(2)
            entryTemplatesGroupChanged = base + 5
            historyMaxItems = 7
            historyMaxSize = 2L * 1024 * 1024
            lastSelectedGroup = id(2)
            lastTopVisibleGroup = id(1)
            settingsChanged = base + 6
            // Only URL is switched on beyond the default: the writer promotes such fields to
            // protected on save, so the fixture marks them protected too and the maps compare equal.
            protectUrl = true
            customData["hisn.test"] = "قيمة مخصّصة"
            customData["hisn.build"] = "0.1.0"
            customIcons.add(
                CustomIcon(
                    uuid = id(90),
                    data = ByteArray(64) { (it * 3).toByte() },
                    name = "درع",
                    lastModified = base + 7,
                )
            )
        }

        db.root.apply {
            uuid = id(1)
            name = "الجذر"
            notes = "ملاحظات المجموعة الجذر"
            iconId = 49
            customIconUuid = id(90)
            times = times(10)
            isExpanded = true
            defaultAutoTypeSequence = "{USERNAME}{TAB}{PASSWORD}{ENTER}"
            enableAutoType = true
            enableSearching = null
            lastTopVisibleEntry = id(20)
            tags = "root"
            customData["group.origin"] = "test"
        }

        val banking = Group(
            uuid = id(2),
            name = "المصارف",
            notes = "حسابات بنكية",
            iconId = 12,
            times = times(20),
            isExpanded = false,
            defaultAutoTypeSequence = null,
            enableAutoType = false,
            enableSearching = true,
        )
        banking.customData["group.sensitive"] = "true"
        banking.previousParentGroup = id(1)
        db.root.addGroup(banking)

        val recycleBin = Group(
            uuid = id(3),
            name = "سلّة المهملات",
            iconId = 43,
            times = times(30),
        )
        db.root.addGroup(recycleBin)

        val bank = Entry(uuid = id(20), iconId = 12).apply {
            customIconUuid = id(90)
            foregroundColor = "#F0E5D8"
            backgroundColor = "#211711"
            overrideUrl = "cmd://open {URL}"
            tags = "مصرف;مهم"
            times = times(40, expires = true, expiry = base + 500_000)
            set(Entry.TITLE, "مصرف الراجحي")
            set(Entry.USERNAME, "zaynab.alharbi")
            set(Entry.PASSWORD, "سِرٌّ طويلٌ جدًا 9#!", protected = true)
            set(Entry.URL, "https://bank.example/login", protected = true)
            set(Entry.NOTES, "سطر أول\nسطر ثانٍ\tمع مسافة جدولة")
            set(Entry.OTP, "otpauth://totp/Bank:zaynab?secret=GEZDGNBVGY3TQOJQ&period=30&digits=6", protected = true)
            set("رقم الحساب", "SA03 8000 0000 6080 1016 7519", protected = true)
            set("Plain custom", "visible value")
            customData["entry.origin"] = "test"
            qualityCheck = false
            previousParentGroup = id(1)
            autoType = AutoType(
                enabled = true,
                dataTransferObfuscation = 1,
                defaultSequence = "{USERNAME}{TAB}{PASSWORD}",
                associations = mutableListOf(
                    AutoTypeAssociation("Firefox", "{USERNAME}{TAB}{PASSWORD}{ENTER}"),
                    AutoTypeAssociation("* — متصفح", "{PASSWORD}{ENTER}"),
                ),
            )
            attachments.add(Attachment("شهادة.pem", certificateRef))
            attachments.add(Attachment("notes.txt", noteRef))
        }

        // Two previous versions, oldest first, sharing the attachment pool with the live entry.
        val olderBank = bank.deepCopy().apply {
            times = times(38, expires = true, expiry = base + 500_000)
            set(Entry.PASSWORD, "كلمة سرّ قديمة", protected = true)
            history.clear()
        }
        val oldestBank = bank.deepCopy().apply {
            times = times(36, expires = true, expiry = base + 500_000)
            set(Entry.PASSWORD, "أقدم كلمة سرّ", protected = true)
            attachments.clear()
            history.clear()
        }
        bank.history.add(oldestBank)
        bank.history.add(olderBank)
        banking.addEntry(bank)

        val minimal = Entry(uuid = id(21)).apply {
            times = times(50)
            set(Entry.TITLE, "بريد")
            set(Entry.USERNAME, "")
            set(Entry.PASSWORD, "p", protected = true)
            set(Entry.URL, "", protected = true)
            set(Entry.NOTES, "")
        }
        db.root.addEntry(minimal)

        val discarded = Entry(uuid = id(22)).apply {
            times = times(60)
            set(Entry.TITLE, "حساب ملغى")
            set(Entry.PASSWORD, "obsolete", protected = true)
            set(Entry.URL, "https://old.example", protected = true)
        }
        recycleBin.addEntry(discarded)

        db.deletedObjects.add(DeletedObject(id(70), base + 100))
        db.deletedObjects.add(DeletedObject(id(71), base + 200))

        db.root.relink()
        return db
    }

    @Test
    fun recycleBinSurvivesTheRoundTrip() {
        val original = sampleDatabase(kdbx41Params())
        val reloaded = KdbxReader.read(KdbxWriter.write(original, credentials()), credentials())

        val bin = reloaded.recycleBin
        assertNotNull("the recycle bin group was lost", bin)
        assertEquals(1, bin!!.entries.size)
        assertTrue(reloaded.isInRecycleBin(bin.entries.first()))
        assertEquals(2, reloaded.visibleEntries().size)
        assertNull(reloaded.findEntry(id(99)))
    }
}
