package app.kaeru.shared.data.kodik

/**
 * Where the public Kodik token scraped off `add-players.min.js` survives between runs.
 *
 * The client remembers the token it scraped for a day in memory; a process that restarts inside
 * that day would scrape it again, which is a thirty-kilobyte script for a value that has not
 * changed. Android keeps it in its preference store and hands that store in here. iOS keeps
 * nothing and gets [None], which is exactly the behaviour it had.
 *
 * The moment stored beside the token is wall-clock epoch milliseconds, because it has to mean
 * the same thing after a restart; the client reads it against its own clock and re-scrapes
 * whenever the age is negative or a day or more.
 */
interface KodikTokenCache {
    class Entry(val token: String, val storedAtMillis: Long)

    suspend fun load(): Entry?
    suspend fun store(token: String, storedAtMillis: Long)

    /** After Kodik rejected the stored token: the next look must scrape, not reuse it. */
    suspend fun clear()

    object None : KodikTokenCache {
        override suspend fun load(): Entry? = null
        override suspend fun store(token: String, storedAtMillis: Long) = Unit
        override suspend fun clear() = Unit
    }
}
