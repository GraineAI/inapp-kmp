package ai.graine.inapp.kmp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Graine.calls.startCall` and `callBack` from a thread that is not the main
 * one wait for the main thread at most two seconds, and answer null after
 * that. Whatever they asked for must then never happen behind the caller's
 * back: a call placed after startCall said none was would ring the agent while
 * the app shows that nothing happened.
 */
class MainSyncTest {
    @Test fun aBlockTheMainThreadReachesTooLateNeverRuns() {
        val mainQueue = ArrayDeque<Runnable>()
        var placed = 0
        val id = MainSync.run({ mainQueue.addLast(it) }, timeoutMs = 20) { placed++; "call_1" }
        assertNull("the caller was told no call", id)
        // The main thread, busy until now, gets to it.
        mainQueue.removeFirst().run()
        assertEquals("so no call is placed", 0, placed)
    }

    @Test fun aBlockRunInTimeAnswers() {
        val id = MainSync.run({ Thread(it).start() }, timeoutMs = 2_000) { "call_1" }
        assertEquals("call_1", id)
    }
}
