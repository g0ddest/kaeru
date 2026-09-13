package app.kaeru.ui.common.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText

private val LeadingIconSize = 20.dp

/**
 * The one way text is typed into this app.
 *
 * A single rounded field on the app's own surface, with nothing sitting on its edge. That is the
 * whole reason it is not a Material `OutlinedTextField`: anything put in that component's trailing
 * slot lands on the outline rather than inside the field, which is the seam a viewer notices
 * first. Here the icons are inside, on the same ground as the text.
 *
 * Focus rings but does not grow — see [kaeruFocus]'s `focusedScale`. A full-width field that jumped
 * six per cent when the keyboard opened would read as a glitch.
 *
 * The end of the field is one slot, always the width of a tap target whether or not anything is in
 * it, so a name never reflows on the keystroke that makes an icon appear. [trailing] takes that
 * slot when given; otherwise [clearLabel] puts a clear icon there as soon as there is something to
 * clear, and names it — the only control here whose meaning is nowhere else on the row.
 *
 * [onFocusLost] fires when the field gives focus up, which is how a setting that has no button
 * saves what was typed into it.
 */
@Composable
fun KaeruTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    clearLabel: String? = null,
    imeAction: ImeAction = ImeAction.Done,
    onSubmit: () -> Unit = {},
    onFocusLost: () -> Unit = {},
    trailing: @Composable (() -> Unit)? = null,
) {
    val submit by rememberUpdatedState(onSubmit)
    val focusLost by rememberUpdatedState(onFocusLost)
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = KaeruText),
        // Amber is the colour of the thing to press; a caret is neither that nor progress.
        cursorBrush = SolidColor(KaeruText),
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onDone = { submit() },
            onGo = { submit() },
            onSearch = { submit() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (focused && !state.isFocused) focusLost()
                focused = state.isFocused
            }
            .kaeruFocus(shape = KaeruTokens.ChipShape, focusedScale = 1f)
            .clip(KaeruTokens.ChipShape)
            .background(KaeruSurface)
            .heightIn(min = KaeruTokens.ButtonHeight),
        decorationBox = { field ->
            Row(
                Modifier.padding(
                    start = if (leadingIcon == null) KaeruTokens.Space4 else KaeruTokens.Space3,
                    end = KaeruTokens.Space1,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = KaeruSecondary,
                        modifier = Modifier.size(LeadingIconSize),
                    )
                    Spacer(Modifier.width(KaeruTokens.Space3))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = KaeruSecondary,
                            maxLines = 1,
                        )
                    }
                    field()
                }
                when {
                    trailing != null -> trailing()
                    clearLabel != null && value.isNotEmpty() ->
                        IconAction(Icons.Default.Close, clearLabel, { onValueChange("") }, enabled = enabled)
                    // Holds the slot open so the text column keeps its width either way.
                    else -> Spacer(Modifier.width(KaeruTokens.MinTouchTarget))
                }
            }
        },
    )
}
