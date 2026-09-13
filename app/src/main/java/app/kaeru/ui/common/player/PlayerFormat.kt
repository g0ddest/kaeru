package app.kaeru.ui.common.player

/** `1:02:34` for anything an hour or longer, `12:34` otherwise; hidden entirely while unknown. */
fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val seconds = total % 60
    val minutes = (total / 60) % 60
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
