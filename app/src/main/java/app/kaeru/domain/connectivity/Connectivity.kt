package app.kaeru.domain.connectivity

import kotlinx.coroutines.flow.Flow

/**
 * Whether the device has a network that actually reaches the internet.
 *
 * Distinct values only, starting with the state at the moment of collection, so a collector never
 * waits for the next change to learn where it stands.
 */
interface Connectivity {
    val online: Flow<Boolean>
}
