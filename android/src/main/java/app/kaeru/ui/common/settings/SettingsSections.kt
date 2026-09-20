package app.kaeru.ui.common.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kaeru.domain.model.Account
import app.kaeru.ui.common.design.Avatar
import app.kaeru.ui.common.design.AvatarSize
import app.kaeru.ui.common.design.IconAction
import app.kaeru.ui.common.design.KaeruSwitch
import app.kaeru.ui.common.design.KaeruTokens
import app.kaeru.ui.common.design.kaeruFocus
import app.kaeru.ui.common.design.RowHeader
import app.kaeru.ui.common.design.Skeleton
import app.kaeru.ui.common.design.SkeletonGroup
import app.kaeru.ui.common.design.StatusPill
import app.kaeru.ui.common.theme.KaeruSecondary
import app.kaeru.ui.common.theme.KaeruText

private const val SHIKIMORI = "Shikimori"
private const val NO_NAME = "Имя не загрузилось"
private const val MOVE_UP = "Поднять"
private const val MOVE_DOWN = "Опустить"
private const val REMOVE = "Убрать"

/** The two lines of the skeleton account, at the width a nickname and a source name come out. */
private val SkeletonNameWidth = 140.dp
private val SkeletonSourceWidth = 84.dp
private val SkeletonNameHeight = 18.dp
private val SkeletonSourceHeight = 14.dp

/**
 * One part of the screen: a quiet name, then the controls under it.
 *
 * There is no rule between sections and no card around one. Thirty-two device-independent pixels
 * of nothing is what separates them, which is the same device the rest of the app uses to separate
 * a row of artwork from the next — a screen of settings should not be the one place that grows
 * borders.
 */
@Composable
fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    gutter: Dp = KaeruTokens.GutterPhone,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3)) {
        RowHeader(title, gutter = gutter)
        Column(
            Modifier.padding(horizontal = gutter),
            verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space3),
            content = content,
        )
    }
}

/**
 * The sentence that says what a setting will do.
 *
 * Every control on this screen changes something that happens later and out of sight, during
 * playback. A switch beside a label says what it is called; only a sentence says what it does,
 * which is why this screen reads more like a page than a control panel.
 */
@Composable
fun SettingNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
}

/** What a control is called. A step louder than [SettingNote] and a step quieter than the section. */
@Composable
fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = KaeruText)
}

/**
 * A setting that is on or off, with the whole row as the target.
 *
 * The switch itself takes no click: the row carries `toggleable`, so the label and the switch are
 * one control that announces itself once and can be hit anywhere along its width — and, on a
 * television, one focus stop rather than two.
 */
@Composable
fun SettingSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = KaeruTokens.MinTouchTarget)
            // A full-width row has nowhere to grow into, so the ring carries the whole focus
            // signal. Inert under a finger; on a television it is the only thing that says the
            // remote is here.
            .kaeruFocus(KaeruTokens.CardShape, focusedScale = 1f)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = KaeruText,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(KaeruTokens.Space3))
        KaeruSwitch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A row of chips where exactly one is chosen.
 *
 * The pills carry no chevron: that affordance means «a menu opens here», and nothing opens — the
 * choices are all already on screen. They wrap rather than scroll, because a choice a viewer
 * cannot see is a choice they do not have.
 */
@Composable
fun <T> SettingChoiceRow(
    options: List<T>,
    label: (T) -> String,
    selected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
        verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2),
    ) {
        options.forEach { option ->
            StatusPill(
                text = label(option),
                selected = selected(option),
                onClick = { onSelect(option) },
                role = Role.RadioButton,
                affordance = false,
            )
        }
    }
}

/**
 * One studio in the priority list.
 *
 * The three controls are described with the studio's own name — «Поднять AniLibria», not «Выше».
 * Nine rows of identical verbs is a list a screen reader cannot navigate, and this is the one
 * screen where a viewer moves things around rather than reads them.
 */
@Composable
fun StudioRow(
    name: String,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canRemove: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = KaeruTokens.MinTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.titleSmall,
            color = KaeruText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconAction(Icons.Default.KeyboardArrowUp, "$MOVE_UP $name", onMoveUp, enabled = canMoveUp)
        IconAction(Icons.Default.KeyboardArrowDown, "$MOVE_DOWN $name", onMoveDown, enabled = canMoveDown)
        IconAction(Icons.Default.Close, "$REMOVE $name", onRemove, enabled = canRemove)
    }
}

/**
 * Whoever is signed in: a face, a name, and where the name came from.
 *
 * The avatar is the only circle in the app, so the block reads as a person rather than as another
 * card — and there is no container around it, because the two card shapes this app has are a
 * poster and a hero, and an account is neither.
 */
@Composable
fun AccountBlock(account: Account?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(url = account?.avatarUrl, name = account?.nickname ?: NO_NAME)
        Spacer(Modifier.width(KaeruTokens.Space4))
        Column {
            Text(
                account?.nickname ?: NO_NAME,
                style = MaterialTheme.typography.titleMedium,
                color = if (account == null) KaeruSecondary else KaeruText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(SHIKIMORI, style = MaterialTheme.typography.bodyMedium, color = KaeruSecondary)
        }
    }
}

/** The same block while the name is on its way, at the same height, so nothing jumps when it lands. */
@Composable
fun AccountSkeleton() = SkeletonGroup {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Skeleton(Modifier.size(AvatarSize), shape = CircleShape)
        Spacer(Modifier.width(KaeruTokens.Space4))
        Column(verticalArrangement = Arrangement.spacedBy(KaeruTokens.Space2)) {
            Skeleton(Modifier.width(SkeletonNameWidth).height(SkeletonNameHeight), KaeruTokens.ChipShape)
            Skeleton(Modifier.width(SkeletonSourceWidth).height(SkeletonSourceHeight), KaeruTokens.ChipShape)
        }
    }
}
