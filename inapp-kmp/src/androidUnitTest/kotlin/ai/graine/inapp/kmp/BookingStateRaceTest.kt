package ai.graine.inapp.kmp

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 0.14.4 review: bookingState's read of the platform SDK and its write are
 * one step. The session arriving refreshes it on one thread while the app
 * signs the user out on another: a refresh that read the platform BEFORE the
 * sign-out must never write its answer AFTER the sign-out's own refresh — or
 * the state says "available" for a user who is gone, until the next change.
 */
class BookingStateRaceTest {
    /** The platform SDK, whose next booking read can be held between reading the answer and returning it. */
    private class HeldEngine(val inner: FakeEngine) : GraineEngine by inner {
        private val armed = AtomicBoolean(false)
        private val reading = CountDownLatch(1)
        private val go = CountDownLatch(1)

        fun holdNextRead() = armed.set(true)
        fun awaitHeld() = assertTrue(reading.await(5, TimeUnit.SECONDS), "the refresh reached the platform")
        fun release() = go.countDown()

        override val bookingAvailable: Boolean
            get() {
                val answer = inner.bookingAvailable
                if (armed.compareAndSet(true, false)) {
                    reading.countDown()
                    go.await(5, TimeUnit.SECONDS)
                }
                return answer
            }
    }

    @AfterTest
    fun tearDown() = Graine.reset()

    @Test
    fun aRefreshThatReadBeforeASignOutNeverWritesAfterIt() {
        Graine.reset()
        val inner = FakeEngine()
        val engine = HeldEngine(inner)
        Graine.install(engine)
        inner.bookingAvailable = true
        inner.bookingUnavailableReason = null
        // The session arrives: its refresh reads "available" for the user signed in, and is held right there.
        engine.holdNextRead()
        val arriving = thread { inner.sink.session(GraineSession(agentId = "agent-1", memoryEnabled = true)) }
        engine.awaitHeld()
        // Meanwhile the user signs out, on another thread: the platform says so, and identify reads it again.
        inner.bookingAvailable = false
        inner.bookingUnavailableReason = "not_identified"
        val signingOut = thread { Graine.identify(null) }
        signingOut.join(500)
        engine.release()
        arriving.join(5_000)
        signingOut.join(5_000)
        assertEquals(GraineBookingState(available = false, reason = "not_identified"), Graine.bookingState.value,
            "the last word is the platform's answer after the sign-out, never one read before it")
    }
}
