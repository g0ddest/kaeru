package app.kaeru.ui.mobile.home

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.kaeru.domain.model.Anime
import app.kaeru.domain.model.AnimeStatus
import app.kaeru.domain.model.FeedItem
import app.kaeru.domain.model.FeedKind
import app.kaeru.domain.model.HomeFeed
import app.kaeru.domain.model.LibraryEntry
import app.kaeru.domain.model.ListStatus
import app.kaeru.domain.model.UserRate
import app.kaeru.ui.common.home.HomeUiState
import app.kaeru.ui.common.theme.KaeruTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * The hero's button, against the other half of what the watch control decides.
 *
 * The feed raises only playable titles to the top today, so the disabled case is reached here by
 * handing the screen a feed that does — which is the shape of the fault this guards against: one
 * feed rule away, a hero offering an episode that does not exist.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-notnight-xhdpi")
class HomeScreenHeroTest {
    @get:Rule val compose = createComposeRule()

    private val now: Instant = Instant.parse("2026-09-13T10:00:00Z")

    private fun anime(status: AnimeStatus = AnimeStatus.ONGOING, episodes: Int = 12, aired: Int = 8) = Anime(
        id = 21,
        nameRu = "Магическая битва",
        nameRomaji = "Jujutsu Kaisen",
        posterUrl = null,
        screenshotUrls = emptyList(),
        status = status,
        episodes = episodes,
        episodesAired = aired,
        nextEpisodeAt = null,
        score = 8.6,
        year = 2026,
        studio = "MAPPA",
        description = null,
    )

    private fun feed(watched: Int, episode: Int, kind: FeedKind, anime: Anime = anime()): HomeFeed {
        val entry = LibraryEntry(anime, UserRate(1L, anime.id, ListStatus.WATCHING, watched, now), null)
        val item = FeedItem(entry, episode, kind)
        return HomeFeed(item, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }

    private fun show(feed: HomeFeed) = compose.setContent {
        KaeruTheme {
            HomeScreen(
                state = HomeUiState(feed = feed, isLoading = false),
                onRefresh = {},
                onPlay = { _, _ -> },
                onAnime = {},
                onSettings = {},
                onSearch = {},
                onSeason = {},
                onRetrySeason = {},
            )
        }
    }

    @Test
    fun `a hero about an episode nobody can start renders its button disabled`() {
        show(feed(watched = 8, episode = 9, kind = FeedKind.UPCOMING))

        compose.onNodeWithText("Ждём 9 серию").assertIsNotEnabled()
    }

    @Test
    fun `a hero about an episode that has aired stays pressable`() {
        show(feed(watched = 6, episode = 7, kind = FeedKind.NEXT_UP))

        compose.onNodeWithText("Продолжить 7 серию").assertIsEnabled()
    }
}
