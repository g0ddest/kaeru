package app.kaeru.ui.mobile.together

import android.content.Context
import android.content.Intent
import app.kaeru.ui.common.together.ShareRequest
import app.kaeru.ui.common.together.TogetherCopy

/**
 * Hands the invitation to whatever the viewer talks to their friends in.
 *
 * The system sheet rather than a list of apps of our own: the friend is in Telegram, or WhatsApp,
 * or a text message, and the phone already knows which of those the viewer uses. The text carries
 * the show and the episode as well as the link, because the decision the friend makes happens in
 * the messenger, before anything is opened — «Открой в Kaeru: …» on its own asks somebody to agree
 * to an unknown.
 *
 * A new task because the player is a landscape activity and the chooser is not part of it.
 */
fun shareInvitation(context: Context, request: ShareRequest) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, request.text)
        putExtra(Intent.EXTRA_TITLE, TogetherCopy.WATCH_TOGETHER)
    }
    context.startActivity(
        Intent.createChooser(send, TogetherCopy.WATCH_TOGETHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
