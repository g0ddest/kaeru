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
     */
    @Provides
    @Singleton
    fun titleScreenIntent(@ApplicationContext context: Context): TitleScreenIntent = TitleScreenIntent { animeId ->
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Routes.EXTRA_ROUTE, Routes.details(animeId))
    }

    /**
     * How «Смотреть» starts the episode: the very same intent the home screen's card is built on,
     * so a notification is not a second-class way into playback. It starts a task of its own,
     * because there may well be no task of this app's running at all.
     */
    @Provides
    @Singleton
    fun watchEpisodeIntent(@ApplicationContext context: Context): WatchEpisodeIntent = WatchEpisodeIntent { animeId, episode ->
        PlayerActivity.intent(context, animeId, episode).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
