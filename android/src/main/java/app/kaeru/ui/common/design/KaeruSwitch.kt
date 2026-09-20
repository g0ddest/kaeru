package app.kaeru.ui.common.design

import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.kaeru.ui.common.theme.KaeruAccent
import app.kaeru.ui.common.theme.KaeruDivider
import app.kaeru.ui.common.theme.KaeruElevated
import app.kaeru.ui.common.theme.KaeruOnAccent
import app.kaeru.ui.common.theme.KaeruSecondary

/**
 * A setting that is either on or off.
 *
 * On, it takes the accent — the same rule that lights the active tab and the chosen chip: amber
 * marks the state that is in force, never decoration. Off, it is the app's own greys and reads as
 * a control nobody has touched.
 *
 * [onCheckedChange] is normally null here, because the whole row is the tap target: a 48dp label
 * beside a switch is much easier to hit than the switch, and a row that toggles from one end to
 * the other is announced once rather than twice.
 */
@Composable
fun KaeruSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = KaeruOnAccent,
            checkedTrackColor = KaeruAccent,
            checkedBorderColor = KaeruAccent,
            uncheckedThumbColor = KaeruSecondary,
            uncheckedTrackColor = KaeruElevated,
            uncheckedBorderColor = KaeruDivider,
            disabledCheckedThumbColor = KaeruOnAccent,
            disabledCheckedTrackColor = KaeruElevated,
            disabledUncheckedThumbColor = KaeruSecondary,
            disabledUncheckedTrackColor = KaeruElevated,
        ),
    )
}
