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
        fun refused(code: GraineErrorCode) = GraineException(code, line(code, rules))
        if (!rules.usersCanBook) return refused(GraineErrorCode.SchedulingOff)
        if (at < rules.earliest(now)) return refused(GraineErrorCode.TooSoon)
        if (at > rules.latest(now)) return refused(GraineErrorCode.TooFar)
        return null
    }

    /**
     * A refusal in the booking sheet's words — the line iOS, Android, Flutter,
     * React Native and the website show for the same code; the counts are the
     * agent's own rules, so the line says what would work.
     */
    fun line(code: GraineErrorCode, rules: GraineSchedulingRules): String = when (code) {
        GraineErrorCode.TooSoon -> "Pick a time at least ${count(rules.minLeadMinutes, "minute")} from now"
        GraineErrorCode.TooFar -> "Pick a time within ${count(rules.maxDaysAhead, "day")}"
        GraineErrorCode.OutsideWindow -> "That time is outside the hours we can call — pick another"
        GraineErrorCode.TooManyPending -> "You already have ${count(rules.maxPendingPerUser, "call")} booked — cancel one first"
        GraineErrorCode.SchedulingOff -> "Booking a call isn't available right now"
        GraineErrorCode.IdentityRequired -> "Sign in to book a call"
        else -> "Couldn't book just now — try again"
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
}
