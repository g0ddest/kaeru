package app.kaeru.domain.repository

import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    fun observeLibrary(): Flow<List<LibraryEntry>>
    fun observeAnime(id: Int): Flow<LibraryEntry?>
    /** All user rates with their anime; expensive, call at launch and pull-to-refresh. */
    suspend fun refresh(): Result<Unit>
    /** Details for one anime: description, screenshots, and nextEpisodeAt. */
    suspend fun refreshAnime(id: Int): Result<Unit>
    suspend fun search(query: String): Result<List<Anime>>
    suspend fun setStatus(animeId: Int, status: ListStatus): Result<Unit>
    suspend fun setEpisodes(animeId: Int, episodes: Int): Result<Unit>
}
