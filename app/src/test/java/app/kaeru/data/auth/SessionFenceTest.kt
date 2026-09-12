package app.kaeru.data.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SessionFenceTest {
    @Test
    fun `mutation cannot overtake handoff but can finish while entered collector is suspended`() {
        val fence = SessionFence()
        val handoffPaused = CountDownLatch(1)
        val releaseHandoff = CountDownLatch(1)
        val enteredCollector = CountDownLatch(1)
        val releaseCollector = CompletableDeferred<Unit>()
        val changeStarted = CountDownLatch(1)
        val changeThread = AtomicReference<Thread>()
        val deliveryThread = AtomicReference<Thread>()
        val deliveryWorker = Executors.newSingleThreadExecutor()
        val mutationWorker = Executors.newSingleThreadExecutor()
        try {
            val delivery = deliveryWorker.submit {
                deliveryThread.set(Thread.currentThread())
                runBlocking {
                    fence.deliver(fence.revision.value) {
                        // Pause within the handoff before invoking the actual consumer body.
                        handoffPaused.countDown()
                        check(releaseHandoff.await(10, TimeUnit.SECONDS))
                        enteredCollector.countDown()
                        releaseCollector.await()
                    }
                }
            }
            assertTrue(handoffPaused.await(10, TimeUnit.SECONDS))
            val mutation = mutationWorker.submit {
                changeThread.set(Thread.currentThread())
                changeStarted.countDown()
                runBlocking { fence.change {} }
            }
            assertTrue(changeStarted.await(10, TimeUnit.SECONDS))
            // Wait for actual monitor contention or an illegally completed mutation, not a delay.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            // Android's compile SDK omits java.lang.management; it is available on the test JVM.
            val threads = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean").invoke(null)
            val getInfo = Class.forName("java.lang.management.ThreadMXBean")
                .getMethod("getThreadInfo", java.lang.Long.TYPE)
            val infoType = Class.forName("java.lang.management.ThreadInfo")
            fun blockedOnDelivery(): Boolean {
                val info = getInfo.invoke(threads, changeThread.get().threadId()) ?: return false
                return infoType.getMethod("getThreadState").invoke(info) == Thread.State.BLOCKED &&
                    infoType.getMethod("getLockOwnerId").invoke(info) == deliveryThread.get().threadId()
            }
            while (!mutation.isDone && !blockedOnDelivery()) {
                check(System.nanoTime() < deadline) { "Mutation did not reach the handoff monitor" }
                Thread.yield()
            }
            assertFalse("Mutation overtook the paused handoff", mutation.isDone)
            assertEquals(1L, enteredCollector.count)
            releaseHandoff.countDown()
            assertTrue(enteredCollector.await(10, TimeUnit.SECONDS))
            mutation.get(10, TimeUnit.SECONDS)
            assertFalse("The already-entered collector is still suspended", delivery.isDone)
            releaseCollector.complete(Unit)
            delivery.get(10, TimeUnit.SECONDS)
        } finally {
            releaseHandoff.countDown()
            releaseCollector.complete(Unit)
            deliveryWorker.shutdownNow()
            mutationWorker.shutdownNow()
        }
    }

    @Test
    fun `overlapping changes keep delivery closed until every change has finished`() = runTest {
        val fence = SessionFence()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val first = async { fence.change { firstEntered.complete(Unit); releaseFirst.await() } }
        firstEntered.await()
        val second = async { fence.change { secondEntered.complete(Unit); releaseSecond.await() } }
        secondEntered.await()
        releaseFirst.complete(Unit)
        first.await()
        var delivered = false
        fence.deliver(fence.revision.value) { delivered = true }
        assertFalse(delivered)
        releaseSecond.complete(Unit)
        second.await()
        fence.deliver(fence.revision.value) { delivered = true }
        assertTrue(delivered)
    }

    @Test
    fun `collector exception propagates and releases the handoff monitor`() = runTest {
        val fence = SessionFence()
        val failure = IllegalStateException("collector failed")
        try {
            fence.deliver(fence.revision.value) { throw failure }
            fail("Collector exception must propagate")
        } catch (caught: IllegalStateException) {
            assertSame(failure, caught)
        }
        fence.change {}
        var delivered = false
        fence.deliver(fence.revision.value) { delivered = true }
        assertTrue(delivered)
    }

    @Test
    fun `cancelled suspended collector leaves mutations and later delivery usable`() = runTest {
        val fence = SessionFence()
        val entered = CompletableDeferred<Unit>()
        val collector = async {
            fence.deliver(fence.revision.value) { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        }
        entered.await()
        collector.cancelAndJoin()
        assertTrue(collector.isCancelled)
        fence.change {}
        var delivered = false
        fence.deliver(fence.revision.value) { delivered = true }
        assertTrue(delivered)
    }
}
