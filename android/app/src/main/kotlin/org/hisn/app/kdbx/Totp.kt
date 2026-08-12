package org.hisn.app.kdbx

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 6238 time-based one-time passwords, bit-compatible with KeePassXC's desktop
 * implementation (src/core/Totp.cpp, src/core/Base32.cpp).
 *
 * Every storage format the desktop app can write is accepted:
 *  - a bare base32 secret                      -> "JBSWY3DPEHPK3PXP"
 *  - an otpauth:// URI                         -> "otpauth://totp/Issuer:acc?secret=...&digits=8&period=60&algorithm=SHA256&encoder=steam"
 *  - the KeeOtp key/value form                 -> "key=JBSWY3DPEHPK3PXP&size=6&step=30&otpHashMode=SHA256"
 *  - the legacy "TOTP Seed" + "TOTP Settings"  -> settings are "[step];[digits]" or "[step];S" for Steam
 *  - the KeePass 2 attributes                  -> "TimeOtp-Secret-Base32" and friends
 */
enum class TotpAlgorithm(val macName: String, val label: String) {
    Sha1("HmacSHA1", "SHA1"),
    Sha256("HmacSHA256", "SHA256"),
    Sha512("HmacSHA512", "SHA512");

    companion object {
        /** Accepts every spelling the desktop and Google Authenticator emit. */
        fun fromName(name: String?): TotpAlgorithm = when (name?.trim()?.uppercase()) {
            "SHA512", "HMAC-SHA-512", "HMACSHA512" -> Sha512
            "SHA256", "HMAC-SHA-256", "HMACSHA256" -> Sha256
            else -> Sha1
        }
    }
}

enum class TotpEncoder(val alphabet: String, val shortName: String) {
    /** Ordinary decimal HOTP truncation. */
    Base10("0123456789", ""),

    /** Steam guard: five characters, emitted least-significant first. */
    Steam("23456789BCDFGHJKMNPQRTVWXY", "S"),
}

/**
 * A parsed, ready-to-use TOTP configuration. [secret] is the decoded shared key;
 * [rawSecret] keeps the sanitised base32 text so settings can be written back unchanged.
 */
class TotpSettings(
    val secret: ByteArray,
    val rawSecret: String,
    val digits: Int,
    val period: Int,
    val algorithm: TotpAlgorithm,
    val encoder: TotpEncoder,
    val issuer: String? = null,
    val account: String? = null,
) {
    val isSteam: Boolean get() = encoder == TotpEncoder.Steam

    /**
     * Serialises back to an otpauth:// URI, the format KeePassXC prefers for new entries,
     * so a TOTP configured on the phone shows up correctly on the desktop.
     */
    fun toOtpAuthUri(title: String, username: String): String {
        val label = "${encode(issuer ?: title.ifBlank { "Hisn" })}:${encode(account ?: username.ifBlank { "none" })}"
        val sb = StringBuilder("otpauth://totp/")
        sb.append(label)
        sb.append("?secret=").append(encode(rawSecret))
        sb.append("&period=").append(period)
        sb.append("&digits=").append(digits)
        sb.append("&issuer=").append(encode(issuer ?: title.ifBlank { "Hisn" }))
        if (encoder == TotpEncoder.Steam) sb.append("&encoder=steam")
        if (algorithm != TotpAlgorithm.Sha1) sb.append("&algorithm=").append(algorithm.label)
        return sb.toString()
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** One generated code plus the life left on it, so the UI can draw a countdown without recomputing. */
data class TotpCode(val value: String, val secondsRemaining: Int, val period: Int) {
    /** 1.0 right after a step boundary, falling to 0.0 as the code expires. */
    val fractionRemaining: Float get() = if (period <= 0) 0f else secondsRemaining.toFloat() / period
}

object Totp {

    const val DEFAULT_DIGITS = 6
    const val DEFAULT_PERIOD = 30
    const val STEAM_DIGITS = 5
    const val MIN_DIGITS = 1
    const val MAX_DIGITS = 10
    const val MAX_PERIOD = 86400

    private const val BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    // ---------------------------------------------------------------- parsing

    /**
     * Reads whichever TOTP attributes [entry] carries. Returns null when the entry has no
     * usable TOTP configuration (an unreadable secret counts as "no configuration").
     */
    fun forEntry(entry: Entry): TotpSettings? {
        entry.fields[Entry.OTP]?.value?.takeIf { it.isNotBlank() }?.let { raw ->
            parse(raw)?.let { return it }
        }
        val seed = entry.fields["TOTP Seed"]?.value?.takeIf { it.isNotBlank() }
        if (seed != null) {
            val settings = entry.fields["TOTP Settings"]?.value.orEmpty()
            parse(settings, seed)?.let { return it }
        }
        val kp2Secret = entry.fields["TimeOtp-Secret-Base32"]?.value?.takeIf { it.isNotBlank() }
        if (kp2Secret != null) {
            return build(
                rawSecret = kp2Secret,
                digits = entry.fields["TimeOtp-Length"]?.value?.trim()?.toIntOrNull() ?: DEFAULT_DIGITS,
                period = entry.fields["TimeOtp-Period"]?.value?.trim()?.toIntOrNull() ?: DEFAULT_PERIOD,
                algorithm = TotpAlgorithm.fromName(entry.fields["TimeOtp-Algorithm"]?.value),
                encoder = TotpEncoder.Base10,
            )
        }
        return null
    }

    /**
     * Parses any of the supported representations.
     *
     * @param raw    the "otp" attribute, an otpauth URI, a KeeOtp query string, the legacy
     *               "[step];[digits]" settings string, or a bare base32 secret.
     * @param seed   the separately stored secret, used when [raw] only carries settings.
     * @return null when no valid secret could be recovered.
     */
    fun parse(raw: String?, seed: String? = null): TotpSettings? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() && seed.isNullOrBlank()) return null

        if (text.startsWith("otpauth://", ignoreCase = true)) return parseOtpAuth(text)

        val query = parseQuery(text)
        if (query.containsKey("key")) {
            // KeeOtp plugin format.
            return build(
                rawSecret = query["key"].orEmpty(),
                digits = query["size"]?.toIntOrNull() ?: DEFAULT_DIGITS,
                period = query["step"]?.toIntOrNull() ?: DEFAULT_PERIOD,
                algorithm = TotpAlgorithm.fromName(query["otpHashMode"]),
                encoder = TotpEncoder.Base10,
            )
        }

        if (!seed.isNullOrBlank()) {
            // Legacy pair: the secret lives in "TOTP Seed", the tuning in "TOTP Settings".
            var digits = DEFAULT_DIGITS
            var period = DEFAULT_PERIOD
            var encoder = TotpEncoder.Base10
            val parts = text.split(';')
            if (parts.size >= 2) {
                if (parts[1].trim().equals(TotpEncoder.Steam.shortName, ignoreCase = true)) {
                    encoder = TotpEncoder.Steam
                    digits = STEAM_DIGITS
                    parts[0].trim().toIntOrNull()?.let { period = it }
                } else {
                    parts[0].trim().toIntOrNull()?.let { period = it }
                    parts[1].trim().toIntOrNull()?.let { digits = it }
                }
            }
            return build(seed, digits, period, TotpAlgorithm.Sha1, encoder)
        }

        // Everything else is treated as a bare secret.
        return build(text, DEFAULT_DIGITS, DEFAULT_PERIOD, TotpAlgorithm.Sha1, TotpEncoder.Base10)
    }

    /** True when [secret] contains enough base32 to derive a key; used to validate manual entry. */
    fun isValidSecret(secret: String): Boolean = decodeBase32(secret)?.isNotEmpty() == true

    private fun parseOtpAuth(uri: String): TotpSettings? {
        val withoutScheme = uri.substring("otpauth://".length)
        val kind = withoutScheme.substringBefore('/').substringBefore('?').lowercase()
        // Counter-based HOTP cannot be rendered as a timed code, so it is rejected rather than
        // silently generating wrong values.
        if (kind != "totp") return null

        val afterHost = withoutScheme.substringAfter('/', "")
        val label = decodeComponent(afterHost.substringBefore('?'))
        val query = parseQuery(afterHost.substringAfter('?', ""))

        val issuer = query["issuer"] ?: label.substringBefore(':', "").ifBlank { null }
        val account = label.substringAfter(':', "").ifBlank { null } ?: label.ifBlank { null }

        return build(
            rawSecret = query["secret"].orEmpty(),
            digits = query["digits"]?.toIntOrNull() ?: DEFAULT_DIGITS,
            period = query["period"]?.toIntOrNull() ?: DEFAULT_PERIOD,
            algorithm = TotpAlgorithm.fromName(query["algorithm"]),
            encoder = if (query["encoder"].equals("steam", ignoreCase = true)) TotpEncoder.Steam else TotpEncoder.Base10,
            issuer = issuer,
            account = account,
        )
    }

    private fun build(
        rawSecret: String,
        digits: Int,
        period: Int,
        algorithm: TotpAlgorithm,
        encoder: TotpEncoder,
        issuer: String? = null,
        account: String? = null,
    ): TotpSettings? {
        val sanitised = sanitizeBase32(rawSecret)
        val secret = decodeBase32(rawSecret) ?: return null
        if (secret.isEmpty()) return null
        val effectiveDigits = if (encoder == TotpEncoder.Steam) STEAM_DIGITS else digits.coerceIn(MIN_DIGITS, MAX_DIGITS)
        return TotpSettings(
            secret = secret,
            rawSecret = sanitised,
            digits = effectiveDigits,
            period = period.coerceIn(1, MAX_PERIOD),
            algorithm = algorithm,
            encoder = encoder,
            issuer = issuer,
            account = account,
        )
    }

    /** Splits "a=1&b=2", percent-decoding both halves. Values without '=' are ignored. */
    private fun parseQuery(text: String): Map<String, String> {
        if (text.isEmpty() || !text.contains('=')) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in text.split('&')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = decodeComponent(pair.substring(0, idx)).trim()
            val value = decodeComponent(pair.substring(idx + 1)).trim()
            if (key.isNotEmpty()) out[key] = value
        }
        return out
    }

    private fun decodeComponent(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        // A stray '%' is not worth failing the whole parse over; the raw text is still usable.
        value
    }

    // ---------------------------------------------------------------- base32

    /**
     * KeePassXC's Base32::sanitizeInput: the digits that cannot appear in base32 are folded onto
     * the letters they are usually mistyped for, everything else outside the alphabet is dropped.
     */
    fun sanitizeBase32(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            when (ch) {
                '0' -> sb.append('O')
                '1' -> sb.append('L')
                '8' -> sb.append('B')
                else -> if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '2'..'7') sb.append(ch.uppercaseChar())
            }
        }
        return sb.toString()
    }

    /**
     * Decodes base32 tolerating spaces, hyphens, lower case and missing '=' padding.
     * Returns null only when nothing decodable is left.
     */
    fun decodeBase32(input: String): ByteArray? {
        val cleaned = sanitizeBase32(input)
        if (cleaned.isEmpty()) return null
        val out = ByteArrayOutputStream(cleaned.length * 5 / 8 + 1)
        var buffer = 0L
        var bits = 0
        for (ch in cleaned) {
            val value = BASE32_ALPHABET.indexOf(ch)
            if (value < 0) return null
            buffer = (buffer shl 5) or value.toLong()
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write(((buffer shr bits) and 0xFF).toInt())
            }
        }
        val bytes = out.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }

    // ------------------------------------------------------------- generation

    /** Seconds left before the current code rolls over. */
    fun secondsRemaining(settings: TotpSettings, timeSeconds: Long = nowSeconds()): Int =
        (settings.period - Math.floorMod(timeSeconds, settings.period.toLong())).toInt()

    /**
     * Generates the code for [timeSeconds] (defaults to now).
     *
     * @throws KdbxException if the platform lacks the requested HMAC, which would otherwise
     *         surface as an unexplained blank code in the UI.
     */
    fun generate(settings: TotpSettings, timeSeconds: Long = nowSeconds()): TotpCode {
        val counter = timeSeconds / settings.period
        val message = ByteArray(8)
        for (i in 7 downTo 0) {
            message[i] = ((counter ushr ((7 - i) * 8)) and 0xFF).toByte()
        }

        val hmac = try {
            Mac.getInstance(settings.algorithm.macName).run {
                init(SecretKeySpec(settings.secret, settings.algorithm.macName))
                doFinal(message)
            }
        } catch (e: Exception) {
            throw KdbxException("Cannot generate a TOTP code with ${settings.algorithm.label}", e)
        }

        val offset = (hmac[hmac.size - 1].toInt() and 0x0F)
        val binary = ((hmac[offset].toInt() and 0x7F) shl 24) or
            ((hmac[offset + 1].toInt() and 0xFF) shl 16) or
            ((hmac[offset + 2].toInt() and 0xFF) shl 8) or
            (hmac[offset + 3].toInt() and 0xFF)

        val alphabet = settings.encoder.alphabet
        val base = alphabet.length.toLong()
        // Computed in Long because 10^10 overflows 32 bits; the desktop's 32-bit variant only
        // differs for the (never used) 10-digit case.
        var space = 1L
        repeat(settings.digits) { space *= base }
        var remainder = binary.toLong() % space

        val chars = CharArray(settings.digits) { alphabet[0] }
        // Steam writes its alphabet least-significant character first; decimal codes are the usual
        // right-aligned big-endian form.
        val reverse = settings.encoder == TotpEncoder.Steam
        var pos = if (reverse) 0 else settings.digits - 1
        val step = if (reverse) 1 else -1
        while (remainder > 0 && pos in chars.indices) {
            chars[pos] = alphabet[(remainder % base).toInt()]
            remainder /= base
            pos += step
        }

        return TotpCode(String(chars), secondsRemaining(settings, timeSeconds), settings.period)
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
}
