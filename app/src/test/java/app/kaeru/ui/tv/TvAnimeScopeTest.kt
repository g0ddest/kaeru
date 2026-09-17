package app.kaeru.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.DEFAULT_ARGS_KEY
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The store a title card's view model lives in, and the promise that it ends with the card.
 *
 * The television navigates by plain state rather than by Navigation Compose, so nothing tidies a
 * destination's view models away for it. Keyed on the activity's own store, one would survive per
 * anime the viewer ever opened, each holding an open database query for a title nobody is looking
 * at — which is what this scope exists to prevent, and what these check.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w960dp-h540dp-television-notnight-mdpi")
class TvAnimeScopeTest {
    @get:Rule val compose = createComposeRule()

    class Scoped(handle: SavedStateHandle) : ViewModel() {
        val animeId: Int? = handle[TV_ANIME_ID]
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
            val args = extras[DEFAULT_ARGS_KEY]
            @Suppress("UNCHECKED_CAST")
            return Scoped(SavedStateHandle.createHandle(args, null)) as T
        }
    }

    @Composable
    private fun Scoped(onModel: (Scoped) -> Unit) {
        onModel(viewModel(modelClass = Scoped::class, factory = factory))
    }

    @Test
    fun `the title card's view model is cleared when the card closes`() {
        var model: Scoped? = null
        val open = mutableStateOf(true)
        compose.setContent {
            if (open.value) TvAnimeScope(animeId = 42) { Scoped { model = it } }
        }

        val opened = requireNonNull(model)
        assertEquals(42, opened.animeId)
        assertFalse(opened.cleared)

        open.value = false
        compose.waitForIdle()

        assertTrue("the store should have been cleared with the scope", opened.cleared)
    }

    @Test
    fun `each anime gets its own store, and the previous one is cleared`() {
        var model: Scoped? = null
        val animeId = mutableStateOf(1)
        compose.setContent { TvAnimeScope(animeId = animeId.value) { Scoped { model = it } } }

        val first = requireNonNull(model)
        assertEquals(1, first.animeId)

        animeId.value = 2
        compose.waitForIdle()

        val second = requireNonNull(model)
        assertNotSame(first, second)
        assertEquals(2, second.animeId)
        assertTrue("the store of the anime left behind should be cleared", first.cleared)
    }

    /**
     * «Обновления» is the screen this matters most for, and it carries no argument.
     *
     * Without a scope of its own its view model was the activity's, so a back press mid-transfer
     * left the download running and the system installer opened, minutes later, over whatever the
     * viewer had moved on to. The phone never behaved that way — a popped back-stack entry clears
     * its own store — and this is what makes the two devices agree.
     */
    @Test
    fun `a screen's view model is cleared when the screen closes`() {
        var model: Scoped? = null
        val open = mutableStateOf(true)
        compose.setContent {
            if (open.value) TvScreenScope("updates") { Scoped { model = it } }
        }

        val opened = requireNonNull(model)
        assertFalse(opened.cleared)

        open.value = false
        compose.waitForIdle()

        assertTrue("leaving the screen must cancel what it had in flight", opened.cleared)
    }

    /** And reopening it is a new one, which is what makes the screen check again. */
    @Test
    fun `reopening a screen builds a view model that has not run yet`() {
        var model: Scoped? = null
        val open = mutableStateOf(true)
        compose.setContent {
            if (open.value) TvScreenScope("updates") { Scoped { model = it } }
        }
        val first = requireNonNull(model)

        open.value = false
        compose.waitForIdle()
        open.value = true
        compose.waitForIdle()

        assertNotSame("reopening must not hand back the view model that was cleared", first, model)
    }

    private fun requireNonNull(model: Scoped?): Scoped =
        requireNotNull(model) { "the scope never handed out a view model" }
}
