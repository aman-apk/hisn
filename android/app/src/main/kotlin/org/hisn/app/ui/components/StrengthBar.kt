package org.hisn.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.theme.HisnTheme
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Password quality, scored the way the desktop app scores it.
 *
 * The desktop links zxcvbn; shipping a dictionary-backed estimator on the phone would cost
 * megabytes for a hint, so this is a structural estimate instead: pool size from the
 * character classes present, times an *effective* length that discounts repeated characters
 * and runs (`aaaa`, `abcd`, `1234`) which carry far less than a full character of entropy.
 * The band thresholds are the desktop's, so a password rated "قوية" here is rated the same
 * on the laptop.
 */
object PasswordStrength {

    enum class Quality { Bad, Poor, Weak, Good, Excellent }

    fun entropyBits(password: String): Double {
        if (password.isEmpty()) return 0.0

        var lower = false
        var upper = false
        var digit = false
        var symbol = false
        var other = false
        for (c in password) {
            when {
                c in 'a'..'z' -> lower = true
                c in 'A'..'Z' -> upper = true
                c in '0'..'9' -> digit = true
                c.code < 128 -> symbol = true
                else -> other = true
            }
        }
        var pool = 0
        if (lower) pool += 26
        if (upper) pool += 26
        if (digit) pool += 10
        if (symbol) pool += 33
        // Non-ASCII (Arabic, emoji): a deliberately conservative allowance.
        if (other) pool += 128
        if (pool < 2) return 0.0

        val seen = HashSet<Char>()
        var effectiveLength = 0.0
        for (i in password.indices) {
            val c = password[i]
            val delta = if (i > 0) c.code - password[i - 1].code else Int.MAX_VALUE
            effectiveLength += when {
                delta == 0 -> 0.20            // "aaaa"
                delta == 1 || delta == -1 -> 0.35 // "abcd" / "4321"
                !seen.contains(c) -> 1.0
                else -> 0.60                  // character already used earlier
            }
            seen.add(c)
        }
        return effectiveLength * (ln(pool.toDouble()) / LN2)
    }

    /** Bands taken from the desktop's PasswordHealth: <40 poor, <75 weak, <100 good. */
    fun quality(bits: Double): Quality = when {
        bits <= 0.0 -> Quality.Bad
        bits < 40 -> Quality.Poor
        bits < 75 -> Quality.Weak
        bits < 100 -> Quality.Good
        else -> Quality.Excellent
    }

    private val LN2 = ln(2.0)
}

/** Full-width strength readout: a copper-to-brass bar plus the band name and bit count. */
@Composable
fun StrengthBar(
    password: String,
    modifier: Modifier = Modifier,
    showDetails: Boolean = true,
) {
    val palette = HisnTheme.palette
    val bits = remember(password) { PasswordStrength.entropyBits(password) }
    val quality = remember(bits) { PasswordStrength.quality(bits) }
    val score = quality.ordinal

    // 128 bits is a full bar: beyond that the difference stops being meaningful.
    val target = (bits / 128.0).coerceIn(0.0, 1.0).toFloat()
    val fraction by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(durationMillis = 260),
        label = "strengthFraction",
    )
    val color by animateColorAsState(
        targetValue = palette.strengthColor(score),
        animationSpec = tween(durationMillis = 260),
        label = "strengthColor",
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = palette.outline, size = size, cornerRadius = radius)
            if (fraction > 0f) {
                drawRoundRect(
                    color = color,
                    size = Size(size.width * fraction, size.height),
                    cornerRadius = radius,
                )
            }
        }
        if (showDetails) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(qualityLabel(quality)),
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                )
                Text(
                    text = stringResource(R.string.strength_bits, bits.roundToInt()),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.muted,
                )
            }
        }
    }
}

private fun qualityLabel(quality: PasswordStrength.Quality): Int = when (quality) {
    PasswordStrength.Quality.Bad -> R.string.strength_bad
    PasswordStrength.Quality.Poor -> R.string.strength_poor
    PasswordStrength.Quality.Weak -> R.string.strength_weak
    PasswordStrength.Quality.Good -> R.string.strength_good
    PasswordStrength.Quality.Excellent -> R.string.strength_excellent
}
