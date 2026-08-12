package org.hisn.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import org.hisn.app.R
import org.hisn.app.ui.theme.SecretTextStyle

/**
 * A text field for a secret: monospaced, masked until revealed, and kept away from the
 * keyboard's suggestion and learning pipeline (which is what [KeyboardType.Password] buys).
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    revealed: Boolean,
    onRevealedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
    extraActions: @Composable (() -> Unit)? = null,
) {
    val supporting: @Composable (() -> Unit)? = supportingText?.let { text ->
        { Text(text) }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = true,
        textStyle = SecretTextStyle,
        label = { Text(label) },
        isError = isError,
        supportingText = supporting,
        leadingIcon = { Icon(HisnIcons.Key, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                extraActions?.invoke()
                IconButton(onClick = { onRevealedChange(!revealed) }) {
                    Icon(
                        imageVector = if (revealed) HisnIcons.Hide else HisnIcons.Reveal,
                        contentDescription = stringResource(
                            if (revealed) R.string.action_hide_password else R.string.action_reveal_password
                        ),
                    )
                }
            }
        },
        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(
            onDone = { onImeAction() },
            onGo = { onImeAction() },
            onNext = { onImeAction() },
        ),
    )
}

/** The plain text field used everywhere else, so every form shares one look. */
@Composable
fun HisnTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    textStyle: TextStyle = LocalTextStyle.current,
    trailing: @Composable (() -> Unit)? = null,
) {
    val supporting: @Composable (() -> Unit)? = supportingText?.let { text ->
        { Text(text) }
    }
    val leading: @Composable (() -> Unit)? = leadingIcon?.let { icon ->
        { Icon(icon, contentDescription = null) }
    }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        singleLine = singleLine,
        minLines = minLines,
        textStyle = textStyle,
        label = { Text(label) },
        isError = isError,
        supportingText = supporting,
        leadingIcon = leading,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
    )
}
