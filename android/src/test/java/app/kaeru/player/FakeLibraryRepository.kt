package app.kaeru.player

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

/**
 * The library as a map. What matters to playback is what it records: which episode was counted
 * and which status was written, both of which tests read back.
 */
class FakeLibraryRepository : LibraryRepository {
    private val entries = MutableStateFlow<Map<Int, LibraryEntry>>(emptyMap())
    val episodeWrites = mutableListOf<Pair<Int, Int>>()
    val statusWrites = mutableListOf<Pair<Int, ListStatus>>()

    fun put(entry: LibraryEntry) = entries.update { it + (entry.anime.id to entry) }

    override fun observeLibrary(): Flow<List<LibraryEntry>> = entries.map { it.values.toList() }
    override fun observeAnime(id: Int): Flow<LibraryEntry?> = entries.map { it[id] }
    override fun observeAnimeDetails(id: Int): Flow<Anime?> = entries.map { it[id]?.anime }
    override suspend fun refresh() = Result.success(Unit)
    override suspend fun refreshAnime(id: Int) = Result.success(Unit)
    override suspend fun search(query: String) = Result.success(emptyList<Anime>())

    /**
     * A status write creates the rate when there is none, the way the real repository does: it
     * resolves the card, posts the rate (or queues it) and leaves a row behind. A fake that
     * silently did nothing would let a caller ask for the same title twice for ever.
     */
    override suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit> {
        statusWrites += animeId to status
        entries.update { rows ->
            val row = rows[animeId]
                ?: LibraryEntry(placeholder(animeId), UserRate(animeId.toLong(), animeId, status, 0, Instant.EPOCH), null)
            rows + (animeId to row.copy(rate = row.rate.copy(status = status)))
        }
        return Result.success(Unit)
    }

    /** The card Shikimori answers a create with, which is what the real path caches alongside it. */
    private fun placeholder(animeId: Int) = Anime(
        id = animeId, nameRu = "Имя $animeId", nameRomaji = "Name $animeId", posterUrl = null,
        screenshotUrls = emptyList(), status = AnimeStatus.RELEASED, episodes = 12, episodesAired = 12,
        nextEpisodeAt = null, score = null, year = null, studio = null, description = null,
    )

    override suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit> {
        episodeWrites += animeId to episodes
        entries.update { rows ->
            val row = rows[animeId] ?: return@update rows
            rows + (animeId to row.copy(rate = row.rate.copy(episodes = episodes)))
        }
        return Result.success(Unit)
    }
}
