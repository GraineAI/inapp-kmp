package ai.graine.inapp.kmp

import kotlinx.datetime.Instant

/**
 * WHAT THE AGENT'S OWN RULES SAY ABOUT A BOOKING, before anything is sent
 * (WIRE.md, SDK types: `scheduleCall`): booking switched off, a time sooner
 * than the lead time, a time further ahead than allowed. The server checks
 * again, and checks what only it knows — the calling hours, the calls already
 * pending — so passing here is not a promise.
 */
internal object Scheduling {
    /** The longest reason kept with a call. */
    const val REASON_MAX = 120

    fun check(at: Instant, now: Instant, rules: GraineSchedulingRules): GraineException? {
        if (!rules.usersCanBook) {
            return GraineException(GraineErrorCode.SchedulingOff, "Calls can't be booked from the app.")
        }
        if (at < rules.earliest(now)) {
            return GraineException(GraineErrorCode.TooSoon, "Pick a time at least ${count(rules.minLeadMinutes, "minute")} from now.")
        }
        if (at > rules.latest(now)) {
            return GraineException(GraineErrorCode.TooFar, "Pick a time within the next ${count(rules.maxDaysAhead, "day")}.")
        }
        return null
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
