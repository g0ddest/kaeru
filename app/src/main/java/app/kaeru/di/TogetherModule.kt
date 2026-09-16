package app.kaeru.di

import app.kaeru.data.together.NoopTogetherSession
import app.kaeru.domain.together.TogetherSessionApi
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The one shared viewing this app can be in.
 *
 * A singleton because it is a room, not a screen: the join screen lives in one activity and the
 * overlay in another, and both have to be looking at the same session or the phone is in two
 * different states at once.
 *
 * Bound to the session that does nothing until the engine that drives the player lands. Replacing
 * the binding is the whole of that change as far as the screens are concerned.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class TogetherModule {
    // Unscoped: the implementation is already @Singleton, so this hands out the one instance.
    @Binds
    abstract fun togetherSession(impl: NoopTogetherSession): TogetherSessionApi
}
