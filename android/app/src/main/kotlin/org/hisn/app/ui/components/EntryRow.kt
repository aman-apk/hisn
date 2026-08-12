package org.hisn.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.hisn.app.R
import org.hisn.app.ui.EntryCard
import org.hisn.app.ui.theme.Brass
import org.hisn.app.ui.theme.BrassStrong
import org.hisn.app.ui.theme.Copper
import org.hisn.app.ui.theme.CopperDeep
import org.hisn.app.ui.theme.CopperLight
import org.hisn.app.ui.theme.CopperStrong
import org.hisn.app.ui.theme.HisnTheme
import org.hisn.app.ui.theme.Rust
import org.hisn.app.ui.theme.SecretTextStyle
import org.hisn.app.ui.theme.WarmOnLight

/** Avatar tints: copper gradations only, so no entry ever introduces an off-brand hue. */
private val AvatarTints = listOf(Copper, CopperStrong, CopperDeep, CopperLight, BrassStrong, Rust, Brass)

/** Stable per-entry tint: the same entry keeps its colour across sessions and devices. */
private fun avatarTint(seed: String): Color {
    if (seed.isEmpty()) return Copper
    var hash = 0
    for (c in seed) hash = hash * 31 + c.code
    return AvatarTints[hash.mod(AvatarTints.size)]
}

@Composable
fun EntryRow(
    entry: EntryCard,
    onClick: () -> Unit,
    onCopyUsername: () -> Unit,
    onCopyPassword: () -> Unit,
    onShowTotp: () -> Unit,
    onCopyTotp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = HisnTheme.palette
    var menuOpen by remember { mutableStateOf(false) }
    val tint = remember(entry.uuid) { avatarTint(entry.uuid.toString()) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(tint.copy(alpha = if (palette.isDark) 0.24f else 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.initial,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                // The pale tints (brass) would vanish on a light background, so darken there.
                color = if (palette.isDark) tint else lerp(tint, WarmOnLight, 0.45f),
            )
        }

        Spacer(Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.title.ifBlank { stringResource(R.string.entry_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.expired) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.entry_expired),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.weak,
                    )
                }
            }
            if (entry.username.isNotBlank()) {
                Text(
                    text = entry.username,
                    style = MaterialTheme.typography.bodySmall.copy(
                        textDirection = SecretTextStyle.textDirection,
                    ),
                    color = palette.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.totp != null) {
                Spacer(Modifier.height(6.dp))
                TotpChip(settings = entry.totp, onClick = onCopyTotp)
            }
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = HisnIcons.More,
                    contentDescription = stringResource(R.string.action_entry_menu),
                    tint = palette.muted,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (entry.username.isNotBlank()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_copy_username)) },
                        leadingIcon = { Icon(HisnIcons.Person, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onCopyUsername()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_copy_password)) },
                    leadingIcon = { Icon(HisnIcons.Key, contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onCopyPassword()
                    },
                )
                if (entry.totp != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_show_totp)) },
                        leadingIcon = { Icon(HisnIcons.Timer, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onShowTotp()
                        },
                    )
                }
            }
        }
    }
}
