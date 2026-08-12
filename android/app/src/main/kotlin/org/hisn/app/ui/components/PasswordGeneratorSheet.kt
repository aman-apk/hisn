package org.hisn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.SecretTextStyle
import java.security.SecureRandom

private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
private const val DIGITS = "0123456789"
private const val SYMBOLS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

/** Glyphs that are indistinguishable in most fonts when a password is read off a screen. */
private const val LOOK_ALIKE = "0O1lI|`'\""

const val MIN_PASSWORD_LENGTH = 8
const val MAX_PASSWORD_LENGTH = 64

data class GeneratorOptions(
    val length: Int = 20,
    val lower: Boolean = true,
    val upper: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    val excludeLookAlike: Boolean = true,
) {
    val hasAnyClass: Boolean get() = lower || upper || digits || symbols
}

/**
 * Generates a password with [SecureRandom], guaranteeing at least one character from every
 * enabled class — the same rule the desktop generator applies by default, so a password
 * generated here always satisfies the site rules the user picked the classes for.
 */
fun generatePassword(options: GeneratorOptions, random: SecureRandom = SecureRandom()): String {
    val groups = buildList {
        if (options.lower) add(LOWER)
        if (options.upper) add(UPPER)
        if (options.digits) add(DIGITS)
        if (options.symbols) add(SYMBOLS)
    }.map { group ->
        if (options.excludeLookAlike) group.filterNot { it in LOOK_ALIKE } else group
    }.filter { it.isNotEmpty() }

    if (groups.isEmpty()) return ""
    val length = options.length.coerceIn(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH)
    val pool = groups.joinToString("")

    val chars = CharArray(length)
    var index = 0
    for (group in groups) {
        if (index >= length) break
        chars[index++] = group[random.nextInt(group.length)]
    }
    while (index < length) {
        chars[index++] = pool[random.nextInt(pool.length)]
    }
    // Fisher-Yates, so the guaranteed characters are not pinned to the first positions.
    for (i in length - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
    }
    return String(chars)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordGeneratorSheet(
    onDismiss: () -> Unit,
    onAccept: (String) -> Unit,
) {
    val palette = HisnTheme.palette
    val sheetState = rememberModalBottomSheetState()
    val random = remember { SecureRandom() }
    var options by remember { mutableStateOf(GeneratorOptions()) }
    var candidate by remember { mutableStateOf("") }
    var nonce by remember { mutableStateOf(0) }

    LaunchedEffect(options, nonce) {
        candidate = generatePassword(options, random)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.generator_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.tintedFill, RoundedCornerShape(14.dp))
                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = candidate.ifEmpty { stringResource(R.string.generator_pick_a_class) },
                    style = SecretTextStyle,
                    color = if (candidate.isEmpty()) palette.weak else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { nonce++ }) {
                    Icon(
                        imageVector = HisnIcons.Regenerate,
                        contentDescription = stringResource(R.string.action_regenerate),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            StrengthBar(password = candidate)

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.generator_length),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = options.length.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LengthSlider(
                length = options.length,
                onLengthChange = { options = options.copy(length = it) },
            )

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = stringResource(R.string.generator_lowercase),
                checked = options.lower,
                onCheckedChange = { options = options.copy(lower = it) },
            )
            ToggleRow(
                label = stringResource(R.string.generator_uppercase),
                checked = options.upper,
                onCheckedChange = { options = options.copy(upper = it) },
            )
            ToggleRow(
                label = stringResource(R.string.generator_digits),
                checked = options.digits,
                onCheckedChange = { options = options.copy(digits = it) },
            )
            ToggleRow(
                label = stringResource(R.string.generator_symbols),
                checked = options.symbols,
                onCheckedChange = { options = options.copy(symbols = it) },
            )
            ToggleRow(
                label = stringResource(R.string.generator_exclude_lookalike),
                checked = options.excludeLookAlike,
                onCheckedChange = { options = options.copy(excludeLookAlike = it) },
            )

            if (!options.hasAnyClass) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.generator_pick_a_class),
                    style = MaterialTheme.typography.bodySmall,
                    color = palette.weak,
                    textAlign = TextAlign.Start,
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { onAccept(candidate) },
                    enabled = candidate.isNotEmpty(),
                ) {
                    Text(stringResource(R.string.generator_use))
                }
            }
        }
    }
}

@Composable
private fun LengthSlider(length: Int, onLengthChange: (Int) -> Unit) {
    Slider(
        value = length.toFloat(),
        onValueChange = { onLengthChange(it.toInt()) },
        valueRange = MIN_PASSWORD_LENGTH.toFloat()..MAX_PASSWORD_LENGTH.toFloat(),
        steps = MAX_PASSWORD_LENGTH - MIN_PASSWORD_LENGTH - 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
