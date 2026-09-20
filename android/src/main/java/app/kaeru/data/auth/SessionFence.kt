package app.kaeru.data.auth

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Shared by token storage and account transitions; never holds a monitor across their IO. */
@Singleton
class SessionFence @Inject constructor() {
    private val monitor = Any()
    private val version = MutableStateFlow(0L)
    // Token CAS can overlap account transitions; parity alone cannot represent an open fence.
    private var changes = 0
    internal val revision: StateFlow<Long> = version.asStateFlow()

    internal suspend fun <T> change(block: suspend () -> T): T {
        boundary(1)
        try {
            return block()
        } finally {
            boundary(-1)
        }
    }

    private fun boundary(delta: Int) = synchronized(monitor) {
        changes += delta
        version.value += 1
    }

    /**
     * The revision check and caller entry share the mutation monitor, including across OS threads.
     * Start on the collecting thread/context and release the monitor at the first suspension or
     * return. A suspended collector retains only its continuation, never the mutation monitor.
     * Like any direct Flow callback, synchronous caller code must not block its thread indefinitely.
     */
    internal suspend fun deliver(expected: Long, emit: suspend () -> Unit): Unit =
        suspendCoroutineUninterceptedOrReturn { continuation ->
            synchronized(monitor) {
                continuation.context.ensureActive()
                if (changes == 0 && version.value == expected) {
                    emit.startCoroutineUninterceptedOrReturn(continuation)
                } else Unit
            }
        }
}
