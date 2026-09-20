package app.kaeru.domain.playback

import app.kaeru.domain.model.EpisodeStream
import app.kaeru.domain.model.Translation

/**
 * What a resolve came back with: the stream, and — when the voice asked for did not carry the
 * episode — which voice that was.
 *
 * A wrapper rather than a field on [EpisodeStream], because a stream is a fact about the source
 * and this is a fact about the ask: the same links are the same stream whoever wanted them. It is
 * what lets the screen say «В озвучке X серии N нет — включена Y», the memory keep X, and a link
 * prepared ahead of time still answer the press that asks for X.
 */
data class Resolution(val stream: EpisodeStream, val insteadOf: Translation? = null)
