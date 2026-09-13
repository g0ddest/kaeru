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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruSurface
import app.kaeru.ui.common.theme.KaeruText

private val FieldHeight = 52.dp
private val LeadingIconSize = 20.dp

/** «Найти» as a button inside the field is what this replaces; the keyboard already has that key. */
private const val CLEAR = "Очистить"

/**
 * The one place in the app where the viewer types.
 *
 * It is a single rounded field on the app's own surface with the magnifier inside it, and no
 * button: the search key on the keyboard is the submit, so nothing has to be squeezed in beside
 * the text. That is the whole reason this is not a Material `OutlinedTextField` — a control put in
 * its trailing slot sits on the outline rather than inside the field, which is exactly the seam a
 * viewer notices first.
 *
 * The clear icon appears only once there is something to clear, so an empty field is a field and
 * not a field with a dead control in it. It is the one thing here with a description of its own:
 * the magnifier repeats what the placeholder already says, and the field announces its own text.
 *
 * Focus rings but does not grow — see [kaeruFocus]'s `focusedScale`. A full-width field that
 * jumped six per cent when the keyboard opened would read as a glitch.
 */
@Composable
fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String = "Название аниме",
) {
    val focus = LocalFocusManager.current
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = KaeruText),
        // Amber is the colour of the thing to press; a caret is neither that nor progress.
        cursorBrush = SolidColor(KaeruText),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                focus.clearFocus()
                onSubmit()
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .kaeruFocus(shape = KaeruTokens.ChipShape, focusedScale = 1f)
            .clip(KaeruTokens.ChipShape)
            .background(KaeruSurface)
            .heightIn(min = FieldHeight),
        decorationBox = { field ->
            Row(
                Modifier.padding(start = KaeruTokens.Space4, end = KaeruTokens.Space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = KaeruSecondary,
                    modifier = Modifier.size(LeadingIconSize),
                )
                Spacer(Modifier.width(KaeruTokens.Space3))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = KaeruSecondary,
                            maxLines = 1,
                        )
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    IconAction(Icons.Default.Close, CLEAR, { onQueryChange("") }, enabled = enabled)
                } else {
                    // Keeps the text column the same width whether or not the clear icon is there,
                    // so a name does not reflow on the keystroke that makes the icon appear.
                    Spacer(Modifier.width(KaeruTokens.MinTouchTarget))
                }
            }
        },
    )
}
