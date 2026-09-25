package org.hisn.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.hisn.app.R
import org.hisn.app.data.ThemeMode
import org.hisn.app.ui.VaultViewModel
import org.hisn.app.ui.components.ShieldLogo
import org.hisn.app.ui.theme.HisnTheme

/**
 * The one-time gate shown before anything else: who makes the app, the three family
 * principles, and the two choices worth settling before first use — language and appearance.
 *
 * Both rows write straight through the same preferences the rest of the app already reads
 * ([VaultViewModel.setLanguage], [VaultViewModel.setThemeMode]); the screen owns no state of
 * its own, so a choice made here is exactly a choice made in settings. Static by design —
 * no animations, one action.
 */
@Composable
fun WelcomeScreen(
    viewModel: VaultViewModel,
    onEnter: () -> Unit,
) {
    val palette = HisnTheme.palette
    val settings by viewModel.settings.collectAsState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            ShieldLogo(
                modifier = Modifier.size(120.dp),
                contentDescription = stringResource(R.string.app_name),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.gate_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = palette.muted,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(30.dp))
            PrincipleRow(stringResource(R.string.gate_principle_privacy))
            Spacer(Modifier.height(14.dp))
            PrincipleRow(stringResource(R.string.gate_principle_format))
            Spacer(Modifier.height(14.dp))
            PrincipleRow(stringResource(R.string.gate_principle_offline))

            Spacer(Modifier.height(30.dp))
            RowLabel(stringResource(R.string.gate_row_language))
            // The row that switches the UI language must not itself flip when the language
            // does: its order is pinned to RTL so each pill stays where the finger left it.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                PillRow(
                    options = listOf(
                        "system" to stringResource(R.string.gate_lang_system),
                        "ar" to stringResource(R.string.gate_lang_ar),
                        "en" to stringResource(R.string.gate_lang_en),
                    ),
                    selected = settings.language,
                    onSelect = viewModel::setLanguage,
                )
            }

            Spacer(Modifier.height(18.dp))
            RowLabel(stringResource(R.string.gate_row_appearance))
            PillRow(
                options = listOf(
                    ThemeMode.System to stringResource(R.string.gate_theme_system),
                    ThemeMode.Light to stringResource(R.string.gate_theme_light),
                    ThemeMode.Dark to stringResource(R.string.gate_theme_dark),
                ),
                selected = settings.theme,
                onSelect = viewModel::setThemeMode,
            )

            Spacer(Modifier.height(34.dp))
            Button(
                onClick = {
                    viewModel.completeOnboarding()
                    onEnter()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.gate_enter),
                    style = MaterialTheme.typography.titleSmall,
                )
            }

            // ختم «مختبرات أمان» — بصمة العائلة في ذيل البوابة.
            Spacer(Modifier.height(20.dp))
            Image(
                painter = painterResource(R.drawable.amanlabs_rosette),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.aman_labs_name),
                fontSize = 12.sp,
                color = palette.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** One principle: a small copper dot, then the sentence, the dot aligned to the first line. */
@Composable
private fun PrincipleRow(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .size(8.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RowLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    )
}

/** The same pill idiom as the settings screen's theme picker, generalised over the value. */
@Composable
private fun <T> PillRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val palette = HisnTheme.palette
    Row(modifier = Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .height(46.dp)
                    .background(
                        color = if (isSelected) MaterialTheme.colorScheme.primary else palette.tintedFill,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
