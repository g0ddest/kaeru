package app.kaeru.di

import android.content.Context
import app.kaeru.data.image.CoilPosterBitmaps
import app.kaeru.data.image.CoilPosterFetcher
import app.kaeru.data.image.KaeruImages
import app.kaeru.data.image.PosterBitmaps
import app.kaeru.data.image.PosterFetcher
import coil3.ImageLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * One image loader for the whole app.
 *
 * A singleton because the disk cache underneath it is one directory, and two loaders over one
 * directory is two caches disagreeing about what is in it. The same instance is what Compose gets
 * — `KaeruApp` hands this one to Coil's singleton — so a poster fetched ahead of an offline
 * evening by `PosterWarmer` is the poster a screen finds later.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageModule {

    @Provides
    @Singleton
    fun imageLoader(@ApplicationContext context: Context): ImageLoader = KaeruImages.loader(context)

    @Provides
    @Singleton
    fun posterFetcher(fetcher: CoilPosterFetcher): PosterFetcher = fetcher

    /** The same loader again, for the one caller that needs the picture rather than the file. */
    @Provides
    @Singleton
    fun posterBitmaps(bitmaps: CoilPosterBitmaps): PosterBitmaps = bitmaps
}
