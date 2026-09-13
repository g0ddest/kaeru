package app.kaeru.ui.tv

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.core.os.bundleOf
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** The argument the title screen's view model reads itself out of. */
internal const val TV_ANIME_ID = "animeId"

/**
 * Gives everything inside a view-model store that lives exactly as long as one destination, with
 * [TV_ANIME_ID] in its saved-state arguments.
 *
 * The television navigates by plain state rather than by Navigation Compose, so nothing hands a
 * screen the back-stack entry that would normally carry a route argument into a [SavedStateHandle].
 * Two things have to be true for the title screen to share the phone's view model rather than grow
 * a second copy of it:
 *
 * - the id has to reach `SavedStateHandle`, which is what the default arguments below do;
 * - and the view model has to *end* when the title card closes. Keyed on the activity's own store,
 *   one would survive per anime the viewer ever opened, each holding an open database query for a
 *   title nobody is looking at any more. So the store is this scope's own and is cleared with it.
 *
 * Everything else — the factory, the saved-state registry — is the activity's, so a view model
 * built here is built exactly as one built anywhere else in the app.
 */
@Composable
fun TvAnimeScope(animeId: Int, content: @Composable () -> Unit) {
    val host = LocalViewModelStoreOwner.current
    check(host is HasDefaultViewModelProviderFactory) {
        "The television needs an activity-backed ViewModelStoreOwner to scope a title screen to"
    }
    val owner = remember(animeId, host) { TvDestinationOwner(host, bundleOf(TV_ANIME_ID to animeId)) }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/**
 * One destination's store, with the host's factory and the host's saved-state registry behind it.
 *
 * [CreationExtras] is rebuilt from the host's every time rather than cached, because the host's own
 * extras carry its lifecycle-bound keys and a copy taken once could outlive them.
 */
private class TvDestinationOwner(
    private val host: HasDefaultViewModelProviderFactory,
    private val args: Bundle,
) : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {

    override val viewModelStore = ViewModelStore()

    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = host.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() = MutableCreationExtras(host.defaultViewModelCreationExtras).apply {
            set(androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY, this@TvDestinationOwner)
            set(androidx.lifecycle.DEFAULT_ARGS_KEY, args)
        }
}
