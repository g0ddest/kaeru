package app.kaeru.ui.common.design

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction

/** «Найти» as a button inside the field is what this replaces; the keyboard already has that key. */
private const val CLEAR = "Очистить"

/**
 * The field a viewer searches from: [KaeruTextField] with a magnifier in it and the keyboard's
 * search key for a submit, so nothing has to be squeezed in beside the text.
 *
 * Submitting drops focus, because the answer appears below the field and the keyboard is in the
 * way of it. That is the one thing this does that the plain field does not.
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
    KaeruTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        enabled = enabled,
        placeholder = placeholder,
        leadingIcon = Icons.Default.Search,
        clearLabel = CLEAR,
        imeAction = ImeAction.Search,
        onSubmit = {
            focus.clearFocus()
            onSubmit()
        },
    )
}
