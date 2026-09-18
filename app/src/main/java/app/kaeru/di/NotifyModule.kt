package app.kaeru.di

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.kaeru.MainActivity
import app.kaeru.data.notify.NewEpisodeNotifications
import app.kaeru.data.notify.NewEpisodesSchedule
import app.kaeru.data.notify.RoomNotifiedEpisodes
import app.kaeru.data.notify.Television
import app.kaeru.data.notify.TitleScreenIntent
import app.kaeru.data.notify.WatchEpisodeIntent
import app.kaeru.data.notify.WorkManagerNewEpisodesSchedule
import app.kaeru.domain.notify.NewEpisodeNotifier
import app.kaeru.domain.notify.NotifiedEpisodes
import app.kaeru.ui.mobile.Routes
import app.kaeru.ui.mobile.player.PlayerActivity
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** What the background check needs that only this module can see: an activity, a route, a device. */
@Module
@InstallIn(SingletonComponent::class)
object NotifyModule {

    /**
     * Where a new-episode notification leads. Built here rather than in `data`, which is the one
     * place that knows both the activity and the route, and keeps the data layer from naming a
     * screen — the same arrangement the downloads notification uses.
     *
     * The intent itself is the player's own way back to a title, not a second copy of it: which
     * extra carries a route and which activity reads it are facts with one home, and two of them
     * would drift the first time either changed. Only the flags are this caller's, because it
     * starts from no task of this app's at all.
     */
    @Provides
    @Singleton
    fun titleScreenIntent(@ApplicationContext context: Context): TitleScreenIntent = TitleScreenIntent { animeId ->
        MainActivity.titleIntent(context, animeId)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    /**
     * How «Смотреть» starts the episode: the very same intent the home screen's card is built on,
     * so a notification is not a second-class way into playback. It starts a task of its own,
     * because there may well be no task of this app's running at all — and it names the title it
     * came from, so the player can take the card down: an action button never auto-cancels one.
     */
    @Provides
    @Singleton
    fun watchEpisodeIntent(@ApplicationContext context: Context): WatchEpisodeIntent = WatchEpisodeIntent { animeId, episode ->
        PlayerActivity.notificationIntent(context, animeId, episode).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Leanback is the platform's own word for «this is a television», and the manifest declares it. */
    @Provides
    @Singleton
    fun television(@ApplicationContext context: Context): Television = Television {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }
}

/**
 * The three seams of the feature behind their interfaces.
 *
 * Unscoped bindings on purpose: each implementation is already a `@Singleton`, so these hand out
 * the one instance rather than making a second.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class NotifyBindings {
    @Binds
    abstract fun notifiedEpisodes(impl: RoomNotifiedEpisodes): NotifiedEpisodes

    @Binds
    abstract fun newEpisodeNotifier(impl: NewEpisodeNotifications): NewEpisodeNotifier

    @Binds
    abstract fun newEpisodesSchedule(impl: WorkManagerNewEpisodesSchedule): NewEpisodesSchedule
}
