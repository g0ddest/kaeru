package app.kaeru.domain.download

import app.kaeru.domain.model.Quality

/**
 * The rules a viewer sets once and every download then obeys.
 *
 * There is deliberately no eviction to go with [limitBytes]: a cache that quietly deletes an
 * episode somebody took on a flight is worse than one that refuses a new one. The limit is a
 * gate at the front, checked before anything is enqueued, and never a reason to remove
 * something already on the device.
 */
data class DownloadPolicy(
    /** Null means «без лимита»: the viewer has said the phone's own free space is the limit. */
    val limitBytes: Long?,
    val wifiOnly: Boolean,
    val deleteWatched: Boolean,
    /** Null means «как при просмотре»: take the best height the source offers. */
    val quality: Quality?,
) {
    /**
     * Whether one more download of about [estimateBytes] still sits inside the limit.
     *
     * Inclusive at the boundary: a download that lands exactly on the limit has not exceeded it,
     * and refusing it would make «5 ГБ» mean «чуть меньше 5 ГБ».
     */
    fun fits(usedBytes: Long, estimateBytes: Long): Boolean =
        limitBytes == null || usedBytes + estimateBytes <= limitBytes

    companion object {
        val DEFAULT = DownloadPolicy(
            limitBytes = 5L * 1024 * 1024 * 1024,
            wifiOnly = true,
            deleteWatched = false,
            quality = Quality.P720,
        )

        /**
         * What an episode is assumed to weigh before this device has downloaded one. A 720p
         * Kodik episode runs a few hundred megabytes; 400 MB is deliberately on the high side,
         * because the cost of guessing low is a phone that fills past the limit its owner set.
         */
        const val FALLBACK_ESTIMATE = 400L * 1024 * 1024
    }
}
