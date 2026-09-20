package app.kaeru.ui.common.player

import app.kaeru.domain.playback.SkipKind

/**
 * What the one skip button says, in one place, because three screens draw it.
 *
 * The ending's button does not say «пропустить»: what it does is start the next episode, and
 * naming the thing that happens is worth more than naming the thing avoided.
 */
fun skipLabel(kind: SkipKind): String = when (kind) {
    SkipKind.OPENING -> "Пропустить опенинг"
    SkipKind.ENDING -> "Следующая серия"
}
