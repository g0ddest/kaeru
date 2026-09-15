package app.kaeru.domain.playback

import app.kaeru.domain.model.Translation

/**
 * A track as the chooser should show it: the track itself, plus what the ranking knows about it
 * that the track cannot say on its own.
 *
 * A wrapper rather than a field on `Translation`, because a translation is a fact the source
 * reported and this is a fact about the viewer. It rides along with the list so the screens never
 * have to ask a second question — a caption that needed its own call would be a caption computed
 * on every recomposition.
 */
data class RankedTranslation(
    val translation: Translation,
    /**
     * This viewer has settled on this track for several anime, and this one is not among them.
     * False once the anime remembers a track of its own: that one is already marked as chosen,
     * and two marks on one list say less than one.
     */
    val oftenChosen: Boolean,
)
