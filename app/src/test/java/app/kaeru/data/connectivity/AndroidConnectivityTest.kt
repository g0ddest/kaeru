package app.kaeru.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetworkCapabilities
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy

/** Whether the device can reach anything, as the banners and the write queue read it. */
@RunWith(RobolectricTestRunner::class)
class AndroidConnectivityTest {
    private lateinit var context: Context
    private lateinit var manager: ConnectivityManager

    private val shadow: ShadowConnectivityManager get() = shadowOf(manager)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = context.getSystemService(ConnectivityManager::class.java)
    }

    /**
     * Shared on the test's own scheduler, with no tail: in the app the sharing keeps the callback
     * for a few seconds past the last collector, which is time a virtual clock would have to be
     * told to pass before anything could be asserted about unregistration.
     */
    private fun TestScope.connectivity() = AndroidConnectivity(context, backgroundScope, stopTimeoutMs = 0)

    private fun capabilities(vararg wanted: Int): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        wanted.forEach { shadowOf(caps).addCapability(it) }
        return caps
    }

    private val validated get() = capabilities(
        NetworkCapabilities.NET_CAPABILITY_INTERNET,
        NetworkCapabilities.NET_CAPABILITY_VALIDATED,
    )

    /** The network the device is on, with capabilities of our choosing. */
    private fun connect(caps: NetworkCapabilities = validated): Network {
        shadow.setDefaultNetworkActive(true)
        val network = requireNotNull(manager.activeNetwork)
        shadow.setNetworkCapabilities(network, caps)
        return network
    }

    private fun fire(block: ConnectivityManager.NetworkCallback.() -> Unit) =
        shadow.networkCallbacks.toList().forEach(block)

    @Test
    fun `the state at the moment of collection comes first`() = runTest {
        connect()

        connectivity().online.test {
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a device with no network at all is offline`() = runTest {
        shadow.setDefaultNetworkActive(false)

        connectivity().online.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a network that has not been validated reaches nothing`() = runTest {
        connect(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        connectivity().online.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `losing and finding a network is one change each way`() = runTest {
        val network = connect()

        connectivity().online.test {
            assertTrue(awaitItem())

            fire { onLost(network) }
            assertEquals(false, awaitItem())

            fire { onCapabilitiesChanged(network, validated) }
            assertTrue(awaitItem())

            // The same network saying the same thing again is not news.
            fire { onCapabilitiesChanged(network, validated) }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `a captive portal that never validates never reads as online`() = runTest {
        val network = connect(capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET))

        connectivity().online.test {
            assertEquals(false, awaitItem())

            fire { onCapabilitiesChanged(network, capabilities(NetworkCapabilities.NET_CAPABILITY_INTERNET)) }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the last collector to leave takes the callback with it`() = runTest {
        connect()
        // Measured as a difference rather than a count: a callback another test left behind in the
        // shadow would otherwise be read as one of ours.
        val before = shadow.networkCallbacks.toSet()

        connectivity().online.test {
            assertTrue(awaitItem())
            assertEquals(1, (shadow.networkCallbacks - before).size)
            cancelAndIgnoreRemainingEvents()
        }
        // A tick of virtual time, not `advanceUntilIdle`: the sharing that lets several screens
        // share one callback lets go of it a moment after the last of them does.
        advanceTimeBy(1)

        assertEquals(before, shadow.networkCallbacks.toSet())
    }

    @Test
    fun `several screens watching at once are one registration`() = runTest {
        connect()
        val before = shadow.networkCallbacks.toSet()
        val watched = connectivity()

        watched.online.test {
            assertTrue(awaitItem())
            watched.online.test {
                assertTrue(awaitItem())

                assertEquals(1, (shadow.networkCallbacks - before).size)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }
}
