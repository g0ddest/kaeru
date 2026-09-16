package app.kaeru.domain.download

import app.kaeru.domain.model.Quality

/**
 * The height a viewer asked one download to take, where «no answer» is a third thing.
 *
 * A bare `Quality?` cannot say this. Null there has to stand for two different sentences — «я не
 * выбирал, решай по настройкам» and «как при просмотре» — and a download sheet whose first chip is
 * «Как при просмотре» sends exactly one of them. It used to send null and the engine read it as the
 * other one, so a viewer who deliberately asked for the height they watch at got the height in the
 * download settings instead, silently, whenever the two differed.
 *
 * So the ambiguity is spelled out instead: a null [DownloadQualityChoice] is «нечего выбирать,
 * возьми из настроек загрузок», and these two are the answers a viewer can actually give.
 */
sealed interface DownloadQualityChoice {

    /**
     * The height this device plays at, read when the download starts rather than when the chip was
     * pressed.
     *
     * That is the playback setting, not the download one — «как при просмотре» is a promise about
     * what the picture will look like, and the download settings are about storage. A playback
     * setting of «лучшее» makes this the best rung the source offers, which is what watching would
     * have given too.
     */
    data object FollowPlayback : DownloadQualityChoice

    /** This height for this download, whatever either setting says. */
    data class Fixed(val quality: Quality) : DownloadQualityChoice
}
