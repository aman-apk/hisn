package org.hisn.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.hisn.app.R
import org.hisn.app.kdbx.Totp
import org.hisn.app.kdbx.TotpSettings
import org.hisn.app.ui.theme.CodeTextStyle
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.SecretTextStyle

/**
 * Wall-clock ticker aligned to the second boundary, so the countdown flips at the same
 * instant the code rolls over instead of drifting by up to a second.
 */
@Composable
fun rememberEpochSeconds(): Long {
    var seconds by remember { mutableStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            val millis = System.currentTimeMillis()
            delay(1000L - (millis % 1000L))
            seconds = System.currentTimeMillis() / 1000L
        }
    }
    return seconds
}

/** The current code, or null when the stored seed cannot be decoded. */
@Composable
fun rememberTotpCode(settings: TotpSettings, epochSeconds: Long): String? {
    // Recomputed once per step, not once per second: the code is constant within a step.
    val window = epochSeconds / settings.period.coerceAtLeast(1)
    return remember(settings, window) {
        runCatching { Totp.generate(settings, epochSeconds).value }.getOrNull()
    }
}

/** Groups a code in halves ("123456" → "123 456") so it can be read aloud and typed. */
fun formatTotpCode(code: String): String =
    if (code.length >= 6 && code.length % 2 == 0) {
        val half = code.length / 2
        code.substring(0, half) + " " + code.substring(half)
    } else {
        code
    }

/** Compact one-time-code pill for a list row. Tapping it copies the code. */
@Composable
fun TotpChip(
    settings: TotpSettings,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = HisnTheme.palette
    val now = rememberEpochSeconds()
    val code = rememberTotpCode(settings, now)
    val remaining = remember(settings, now) { Totp.secondsRemaining(settings, now) }
    val step = settings.period.coerceAtLeast(1)

    Surface(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        color = palette.tintedFill,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TotpRing(
                fraction = remaining.toFloat() / step.toFloat(),
                ringSize = 16.dp,
                strokeWidth = 2.5.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = code?.let(::formatTotpCode) ?: stringResource(R.string.totp_unreadable),
                style = MaterialTheme.typography.labelLarge.copy(
                    fontFamily = SecretTextStyle.fontFamily,
                    textDirection = SecretTextStyle.textDirection,
                ),
                color = if (code != null) MaterialTheme.colorScheme.onSurface else palette.weak,
            )
        }
    }
}

/**
 * Countdown ring. Drains clockwise as the step expires and turns rust in the last five
 * seconds, which is the cue that the code is about to roll over.
 */
@Composable
fun TotpRing(
    fraction: Float,
    modifier: Modifier = Modifier,
    ringSize: Dp = 44.dp,
    strokeWidth: Dp = 4.dp,
    urgent: Boolean = fraction <= 0.17f,
) {
    val palette = HisnTheme.palette
    val arcColor = if (urgent) palette.weak else MaterialTheme.colorScheme.primary
    Canvas(modifier = modifier.size(ringSize)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        val diameter = size.minDimension - stroke
        drawArc(
            color = palette.outline,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke),
        )
        drawArc(
            color = arcColor,
            startAngle = -90f,
            sweepAngle = 360f * fraction.coerceIn(0f, 1f),
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}

/** Ring with the seconds remaining printed inside it — used on the detail screen. */
@Composable
fun TotpCountdown(
    settings: TotpSettings,
    epochSeconds: Long,
    modifier: Modifier = Modifier,
    ringSize: Dp = 52.dp,
) {
    val palette = HisnTheme.palette
    val remaining = remember(settings, epochSeconds) { Totp.secondsRemaining(settings, epochSeconds) }
    val step = settings.period.coerceAtLeast(1)
    Box(modifier = modifier.size(ringSize), contentAlignment = Alignment.Center) {
        TotpRing(
            fraction = remaining.toFloat() / step.toFloat(),
            ringSize = ringSize,
            strokeWidth = 4.dp,
        )
        Text(
            text = remaining.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = if (remaining <= 5) palette.weak else palette.muted,
        )
    }
}

/** Full-size code readout, shown from a list row's overflow menu. */
@Composable
fun TotpDialog(
    entryTitle: String,
    settings: TotpSettings,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = HisnTheme.palette
    val now = rememberEpochSeconds()
    val code = rememberTotpCode(settings, now)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(HisnIcons.Timer, contentDescription = null) },
        title = { Text(entryTitle) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = code?.let(::formatTotpCode) ?: stringResource(R.string.totp_unreadable),
                    style = CodeTextStyle,
                    color = if (code != null) MaterialTheme.colorScheme.primary else palette.weak,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TotpCountdown(settings = settings, epochSeconds = now, ringSize = 44.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.totp_refreshes_in),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.muted,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { code?.let(onCopy) },
                enabled = code != null,
            ) {
                Text(stringResource(R.string.action_copy_totp))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
