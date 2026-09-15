package app.kaeru.ui.tv

import androidx.activity.ComponentActivity
import androidx.core.os.bundleOf
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    private val activity: ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun owner(animeId: Int) = TvDestinationOwner(activity, bundleOf(TV_ANIME_ID to animeId))

    /** Stands in for `DetailsViewModel`: all this test needs to see is when it is let go of. */
    private class Closable : ViewModel() {
        var cleared = false
        public override fun onCleared() { cleared = true }
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

    /**
     * Both scopes are built against the *same* activity here, which is the only way this says
     * anything: two owners built from two activities would have separate stores whatever
     * `TvDestinationOwner` did with them.
     */
    @Test
    fun `two titles opened under one activity get two stores`() {
        assertNotSame(owner(7).viewModelStore, owner(8).viewModelStore)
    }

    /**
     * The half of the contract that matters when a title card closes.
     *
     * `TvAnimeScope` clears this store from an `onDispose`, and without that a view model would
     * survive per anime the viewer ever opened — each holding an open database query for a title
     * nobody is looking at. The composition that calls it cannot be driven from a unit test with no
     * Compose test artifact on the classpath, so what is pinned here is the clearing itself: put a
     * view model in a destination's store, let the store go, and the view model is let go with it.
     */
    @Test
    fun `letting a destination's store go ends the view models inside it`() {
        val scope = owner(7)
        val extras = MutableCreationExtras(scope.defaultViewModelCreationExtras)
        extras[ViewModelProvider.VIEW_MODEL_KEY] = "tv-title"
        val model = ViewModelProvider.create(
            store = scope.viewModelStore,
            factory = viewModelFactory { initializer { Closable() } },
        )[Closable::class]

        scope.viewModelStore.clear()

        assertTrue(model.cleared)
    }
}
