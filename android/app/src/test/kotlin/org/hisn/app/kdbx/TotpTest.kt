package org.hisn.app.kdbx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TOTP generation and parsing.
 *
 * The generation vectors are the ones in RFC 6238 appendix B for all three hash functions, plus
 * the Steam Guard pair KeePassXC uses in tests/TestTotp.cpp — those were captured from the real
 * Steam app, and they are the only way to prove the reversed-alphabet encoding is right.
 *
 * The RFC prints its seeds as ASCII ("12345678901234567890" and the 32/64-byte extensions of it);
 * the base32 spellings below encode exactly those bytes.
 */
class TotpTest {

    /** base32("12345678901234567890") — the RFC's 20-byte SHA-1 seed. */
    private val sha1Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    /** base32("12345678901234567890123456789012") — the 32-byte SHA-256 seed. */
    private val sha256Secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZA===="

    /** base32 of the RFC's 64-byte SHA-512 seed. */
    private val sha512Secret =
        "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ" +
            "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQGEZDGNA="

    /** From KeePassXC's test suite: a throw-away Steam account's shared secret. */
    private val steamSecret = "63BEDWCQZKTQWPESARIERL5DTTQFCJTK"

    private fun settingsFor(
        secret: String,
        digits: Int,
        algorithm: TotpAlgorithm,
        period: Int = 30,
        encoder: TotpEncoder = TotpEncoder.Base10,
    ): TotpSettings {
        val decoded = Totp.decodeBase32(secret)
        assertNotNull("the fixture secret is not decodable base32", decoded)
        return TotpSettings(
            secret = decoded!!,
            rawSecret = Totp.sanitizeBase32(secret),
            digits = digits,
            period = period,
            algorithm = algorithm,
            encoder = encoder,
        )
    }

    // ------------------------------------------------------------- generation

    @Test
    fun rfc6238Sha1Vectors() {
        val settings = settingsFor(sha1Secret, digits = 8, algorithm = TotpAlgorithm.Sha1)
        assertEquals("94287082", Totp.generate(settings, 59L).value)
        assertEquals("07081804", Totp.generate(settings, 1_111_111_109L).value)
        assertEquals("14050471", Totp.generate(settings, 1_111_111_111L).value)
        assertEquals("89005924", Totp.generate(settings, 1_234_567_890L).value)
        assertEquals("69279037", Totp.generate(settings, 2_000_000_000L).value)
        assertEquals("65353130", Totp.generate(settings, 20_000_000_000L).value)
    }

    @Test
    fun rfc6238Sha256Vectors() {
        val settings = settingsFor(sha256Secret, digits = 8, algorithm = TotpAlgorithm.Sha256)
        assertEquals("46119246", Totp.generate(settings, 59L).value)
        assertEquals("68084774", Totp.generate(settings, 1_111_111_109L).value)
        assertEquals("67062674", Totp.generate(settings, 1_111_111_111L).value)
        assertEquals("91819424", Totp.generate(settings, 1_234_567_890L).value)
        assertEquals("90698825", Totp.generate(settings, 2_000_000_000L).value)
        assertEquals("77737706", Totp.generate(settings, 20_000_000_000L).value)
    }

    @Test
    fun rfc6238Sha512Vectors() {
        val settings = settingsFor(sha512Secret, digits = 8, algorithm = TotpAlgorithm.Sha512)
        assertEquals("90693936", Totp.generate(settings, 59L).value)
        assertEquals("25091201", Totp.generate(settings, 1_111_111_109L).value)
        assertEquals("99943326", Totp.generate(settings, 1_111_111_111L).value)
        assertEquals("93441116", Totp.generate(settings, 1_234_567_890L).value)
        assertEquals("38618901", Totp.generate(settings, 2_000_000_000L).value)
        assertEquals("47863826", Totp.generate(settings, 20_000_000_000L).value)
    }

    @Test
    fun sixDigitCodesArePaddedOnTheLeft() {
        val settings = settingsFor(sha1Secret, digits = 6, algorithm = TotpAlgorithm.Sha1)
        // 89005924 truncated to six digits: the leading zero must survive.
        assertEquals("005924", Totp.generate(settings, 1_234_567_890L).value)
        assertEquals("081804", Totp.generate(settings, 1_111_111_109L).value)
    }

    @Test
    fun steamCodesUseTheReversedAlphabet() {
        val settings = settingsFor(
            steamSecret,
            digits = Totp.STEAM_DIGITS,
            algorithm = TotpAlgorithm.Sha1,
            encoder = TotpEncoder.Steam,
        )
        assertEquals("FR8RV", Totp.generate(settings, 1_511_200_518L).value)
        assertEquals("9P3VP", Totp.generate(settings, 1_511_200_714L).value)
    }

    @Test
    fun secondsRemainingCountsDownWithinTheStep() {
        val settings = settingsFor(sha1Secret, digits = 6, algorithm = TotpAlgorithm.Sha1)
        assertEquals(30, Totp.generate(settings, 60L).secondsRemaining)
        assertEquals(1, Totp.generate(settings, 59L).secondsRemaining)
        assertEquals(15, Totp.generate(settings, 45L).secondsRemaining)

        val code = Totp.generate(settings, 45L)
        assertEquals(30, code.period)
        assertEquals(0.5f, code.fractionRemaining, 0.0001f)
    }

    @Test
    fun theCodeOnlyChangesOnAStepBoundary() {
        val settings = settingsFor(sha1Secret, digits = 6, algorithm = TotpAlgorithm.Sha1)
        assertEquals(Totp.generate(settings, 30L).value, Totp.generate(settings, 59L).value)
        assertTrue(Totp.generate(settings, 59L).value != Totp.generate(settings, 60L).value)
    }

    // ---------------------------------------------------------------- parsing

    @Test
    fun otpAuthUriIsParsed() {
        val settings = Totp.parse(
            "otpauth://totp/ACME%20Co:john@example.com" +
                "?secret=HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ&issuer=ACME%20Co&algorithm=SHA1&digits=6&period=30"
        )

        assertNotNull(settings)
        assertEquals("HXDMVJECJJWSRB3HWIZR4IFUGFTMXBOZ", settings!!.rawSecret)
        assertEquals(6, settings.digits)
        assertEquals(30, settings.period)
        assertEquals(TotpAlgorithm.Sha1, settings.algorithm)
        assertEquals(TotpEncoder.Base10, settings.encoder)
        assertEquals("ACME Co", settings.issuer)
        assertEquals("john@example.com", settings.account)
        assertFalse(settings.isSteam)
    }

    @Test
    fun otpAuthUriCarriesTheHashAndDigitOverrides() {
        val settings = Totp.parse(
            "otpauth://totp/Vault:sara?secret=$sha1Secret&algorithm=SHA512&digits=8&period=60"
        )

        assertNotNull(settings)
        assertEquals(TotpAlgorithm.Sha512, settings!!.algorithm)
        assertEquals(8, settings.digits)
        assertEquals(60, settings.period)
    }

    @Test
    fun otpAuthPeriodIsClampedToOneDay() {
        val settings = Totp.parse("otpauth://totp/Vault:sara?secret=$sha1Secret&period=90000")

        assertNotNull(settings)
        assertEquals(Totp.MAX_PERIOD, settings!!.period)
    }

    @Test
    fun otpAuthDigitsAreClampedToTheSupportedRange() {
        val tooMany = Totp.parse("otpauth://totp/Vault:sara?secret=$sha1Secret&digits=99")
        assertNotNull(tooMany)
        assertEquals(Totp.MAX_DIGITS, tooMany!!.digits)

        val tooFew = Totp.parse("otpauth://totp/Vault:sara?secret=$sha1Secret&digits=0")
        assertNotNull(tooFew)
        assertEquals(Totp.MIN_DIGITS, tooFew!!.digits)
    }

    @Test
    fun otpAuthSteamEncoderForcesFiveDigits() {
        val settings = Totp.parse(
            "otpauth://totp/test:test@example.com" +
                "?secret=$steamSecret&issuer=Valve&algorithm=SHA1&digits=8&period=30&encoder=steam"
        )

        assertNotNull(settings)
        assertTrue(settings!!.isSteam)
        assertEquals(Totp.STEAM_DIGITS, settings.digits)
        assertEquals("FR8RV", Totp.generate(settings, 1_511_200_518L).value)
    }

    @Test
    fun counterBasedHotpIsRejected() {
        assertNull(Totp.parse("otpauth://hotp/Vault:sara?secret=$sha1Secret&counter=1"))
    }

    @Test
    fun keeOtpKeyValueFormIsParsed() {
        val settings = Totp.parse("key=HXDMVJECJJWSRBY%3d&step=25&size=8&otpHashMode=Sha256")

        assertNotNull(settings)
        assertEquals(8, settings!!.digits)
        assertEquals(25, settings.period)
        assertEquals(TotpAlgorithm.Sha256, settings.algorithm)
        // The percent-encoded '=' is base32 padding; sanitising drops it but the bytes must match.
        assertArrayEquals(Totp.decodeBase32("HXDMVJECJJWSRBY"), settings.secret)
    }

    @Test
    fun legacySettingsPairIsParsed() {
        val seed = "gezdgnbvgy3tqojqgezdgnbvgy3tqojq"
        val settings = Totp.parse("30;8", seed)

        assertNotNull(settings)
        assertEquals(sha1Secret, settings!!.rawSecret)
        assertEquals(8, settings.digits)
        assertEquals(30, settings.period)
        assertEquals(TotpAlgorithm.Sha1, settings.algorithm)
        assertEquals("14050471", Totp.generate(settings, 1_111_111_111L).value)
    }

    @Test
    fun legacySteadySettingsDefaultWhenAbsent() {
        val settings = Totp.parse("", "gezdgnbvgy3tqojqgezdgnbvgy3tqojq")

        assertNotNull(settings)
        assertEquals(Totp.DEFAULT_DIGITS, settings!!.digits)
        assertEquals(Totp.DEFAULT_PERIOD, settings.period)
    }

    @Test
    fun legacySteamSettingsAreParsed() {
        val settings = Totp.parse("30;S", steamSecret)

        assertNotNull(settings)
        assertTrue(settings!!.isSteam)
        assertEquals(Totp.STEAM_DIGITS, settings.digits)
        assertEquals(30, settings.period)
        assertEquals("9P3VP", Totp.generate(settings, 1_511_200_714L).value)
    }

    @Test
    fun bareSecretIsAccepted() {
        val settings = Totp.parse(sha1Secret)

        assertNotNull(settings)
        assertEquals(sha1Secret, settings!!.rawSecret)
        assertEquals(Totp.DEFAULT_DIGITS, settings.digits)
        assertEquals(Totp.DEFAULT_PERIOD, settings.period)
    }

    @Test
    fun emptyInputYieldsNoSettings() {
        assertNull(Totp.parse(null))
        assertNull(Totp.parse(""))
        assertNull(Totp.parse("", ""))
        assertNull(Totp.parse("!!!"))
    }

    // ---------------------------------------------------------------- base 32

    @Test
    fun base32SanitisingMatchesTheDesktop() {
        // Spaces and hyphens are noise; the three digits that cannot appear in base32 are folded
        // onto the letters users mistype them for.
        assertEquals("GEZDGNBVGY3TQOJQ", Totp.sanitizeBase32("gezd gnbv-gy3t qojq"))
        assertEquals("OLB", Totp.sanitizeBase32("018"))
        assertEquals("", Totp.sanitizeBase32("!@#\$%^&*()"))
    }

    @Test
    fun base32DecodesRegardlessOfCaseSpacingAndPadding() {
        val canonical = Totp.decodeBase32(sha1Secret)
        assertNotNull(canonical)
        assertArrayEquals(canonical, Totp.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"))
        assertArrayEquals(canonical, Totp.decodeBase32("$sha1Secret===="))
        assertArrayEquals("12345678901234567890".toByteArray(Charsets.US_ASCII), canonical)
    }

    @Test
    fun secretValidityIsReportedForManualEntry() {
        assertTrue(Totp.isValidSecret(sha1Secret))
        assertTrue(Totp.isValidSecret("gezd gnbv"))
        assertFalse(Totp.isValidSecret(""))
        assertFalse(Totp.isValidSecret("!!!"))
    }

    // ------------------------------------------------------------- entry glue

    @Test
    fun entryOtpAttributeIsPreferred() {
        val entry = Entry()
        entry.set(Entry.OTP, "otpauth://totp/Vault:sara?secret=$sha1Secret&digits=8&period=30", protected = true)

        val settings = Totp.forEntry(entry)

        assertNotNull(settings)
        assertEquals(8, settings!!.digits)
        assertEquals("14050471", Totp.generate(settings, 1_111_111_111L).value)
        assertTrue(entry.hasTotp)
    }

    @Test
    fun entryLegacySeedAndSettingsPairIsRead() {
        val entry = Entry()
        entry.set("TOTP Seed", sha1Secret, protected = true)
        entry.set("TOTP Settings", "30;8")

        val settings = Totp.forEntry(entry)

        assertNotNull(settings)
        assertEquals(8, settings!!.digits)
        assertEquals(30, settings.period)
        assertEquals("14050471", Totp.generate(settings, 1_111_111_111L).value)
    }

    @Test
    fun entryKeePass2TimeOtpAttributesAreRead() {
        val entry = Entry()
        entry.set("TimeOtp-Secret-Base32", sha1Secret, protected = true)
        entry.set("TimeOtp-Length", "8")
        entry.set("TimeOtp-Period", "30")
        entry.set("TimeOtp-Algorithm", "HMAC-SHA-256")

        val settings = Totp.forEntry(entry)

        assertNotNull(settings)
        assertEquals(8, settings!!.digits)
        assertEquals(30, settings.period)
        assertEquals(TotpAlgorithm.Sha256, settings.algorithm)
    }

    @Test
    fun entryWithoutAnyTotpAttributeYieldsNull() {
        val entry = Entry()
        entry.set(Entry.TITLE, "بريد")
        entry.set(Entry.PASSWORD, "secret", protected = true)

        assertNull(Totp.forEntry(entry))
        assertFalse(entry.hasTotp)
    }

    // --------------------------------------------------------------- writeback

    @Test
    fun otpAuthUriIsRegeneratedLosslessly() {
        val original = Totp.parse(
            "otpauth://totp/ACME%20Co:john@example.com?secret=$sha1Secret&algorithm=SHA256&digits=8&period=45"
        )
        assertNotNull(original)

        val reparsed = Totp.parse(original!!.toOtpAuthUri("ignored title", "ignored user"))

        assertNotNull(reparsed)
        assertArrayEquals(original.secret, reparsed!!.secret)
        assertEquals(original.digits, reparsed.digits)
        assertEquals(original.period, reparsed.period)
        assertEquals(original.algorithm, reparsed.algorithm)
        assertEquals(original.encoder, reparsed.encoder)
        assertEquals("ACME Co", reparsed.issuer)
        assertEquals("john@example.com", reparsed.account)
    }

    @Test
    fun steamSettingsSurviveTheUriRoundTrip() {
        val original = Totp.parse("30;S", steamSecret)
        assertNotNull(original)

        val reparsed = Totp.parse(original!!.toOtpAuthUri("Steam", "player"))

        assertNotNull(reparsed)
        assertTrue(reparsed!!.isSteam)
        assertEquals(Totp.STEAM_DIGITS, reparsed.digits)
        assertEquals("FR8RV", Totp.generate(reparsed, 1_511_200_518L).value)
    }

    @Test
    fun titleAndUsernameFillInAMissingLabel() {
        val original = Totp.parse(sha1Secret)
        assertNotNull(original)

        val reparsed = Totp.parse(original!!.toOtpAuthUri("مصرف", "زينب"))

        assertNotNull(reparsed)
        assertEquals("مصرف", reparsed!!.issuer)
        assertEquals("زينب", reparsed.account)
    }
}
