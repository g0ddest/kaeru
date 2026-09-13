package app.kaeru.ui.tv

import androidx.activity.ComponentActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * The one piece of plumbing the television needs because it navigates by plain state: a view model
 * that reads a route argument out of a `SavedStateHandle` has to get one from somewhere.
 *
 * Worth a test with an activity behind it rather than a reading of the documentation — if the id
 * never reaches the handle, `DetailsViewModel` throws on construction and the title card is a
 * crash rather than a screen.
 */
@RunWith(RobolectricTestRunner::class)
class TvViewModelsTest {

    private fun owner(animeId: Int): TvDestinationOwner {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        return TvDestinationOwner(activity, bundleOf(TV_ANIME_ID to animeId))
    }

    @Test
    fun `a destination scope carries the anime id into a saved state handle`() {
        val extras = MutableCreationExtras(owner(7).defaultViewModelCreationExtras)
        extras[ViewModelProvider.VIEW_MODEL_KEY] = "tv-title"
        assertEquals(7, extras.createSavedStateHandle().get<Int>(TV_ANIME_ID))
    }

    /** The store has to be the scope's own, or the view model outlives the card that opened it. */
    @Test
    fun `the view models built in a destination scope live in its own store`() {
        val scope = owner(7)
        assertSame(scope, scope.defaultViewModelCreationExtras[VIEW_MODEL_STORE_OWNER_KEY])
    }

    @Test
    fun `two titles get two stores`() {
        assertEquals(false, owner(7).viewModelStore === owner(8).viewModelStore)
    }
}
