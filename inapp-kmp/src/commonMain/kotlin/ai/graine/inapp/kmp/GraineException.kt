package ai.graine.inapp.kmp

/**
 * Why a request to Graine did not do what was asked: one stable word a program
 * branches on. A code this build does not know arrives as [Unknown], with the
 * server's word in it.
 */
sealed class GraineErrorCode(val wire: String) {
    /** The publishable key was refused. Check it against Embed & Widgets. */
    data object InvalidKey : GraineErrorCode("invalid_key")
    /** The agent does not accept this app: add `app://` to its Allowed domains. */
    data object OriginRefused : GraineErrorCode("origin_refused")
    /** Too many requests; [GraineException.retryAfterSeconds] says when to try again, when the server said. */
    data object RateLimited : GraineErrorCode("rate_limited")
    /** A field was missing or malformed. */
    data object InvalidRequest : GraineErrorCode("invalid_request")
    /** Nobody is signed in: `identify()` the user with the trait the agent's Identity setting names. */
    data object IdentityRequired : GraineErrorCode("identity_required")
    /** This user is already using the agent on as many devices as it allows. */
    data object DeviceLimit : GraineErrorCode("device_limit")
    /** The agent does not let users book calls (Calls & scheduling). */
    data object SchedulingOff : GraineErrorCode("scheduling_off")
    /** Sooner than the agent's minimum lead time. */
    data object TooSoon : GraineErrorCode("too_soon")
    /** Further ahead than the agent allows. */
    data object TooFar : GraineErrorCode("too_far")
    /** Outside the agent's calling hours, in the user's time zone. */
    data object OutsideWindow : GraineErrorCode("outside_window")
    /** The user already has as many calls booked as the agent allows. */
    data object TooManyPending : GraineErrorCode("too_many_pending")
    data object NotFound : GraineErrorCode("not_found")
    /** The service could not answer just now. Worth trying again later. */
    data object Unavailable : GraineErrorCode("unavailable")
    /** No answer: no network, or Graine could not be reached. Worth trying again. */
    data object Network : GraineErrorCode("network")
    data class Unknown(val raw: String) : GraineErrorCode(raw)

    companion object {
        private val known by lazy {
            listOf(
                InvalidKey, OriginRefused, RateLimited, InvalidRequest, IdentityRequired, DeviceLimit, SchedulingOff,
                TooSoon, TooFar, OutsideWindow, TooManyPending, NotFound, Unavailable, Network,
            ).associateBy { it.wire }
        }

        /** The code for a word on the wire (or a platform SDK's own); blank is [Unknown] with "unknown". */
        fun from(wire: String?): GraineErrorCode {
            val word = wire?.trim().orEmpty()
            if (word.isEmpty()) return Unknown("unknown")
            return known[word.lowercase()] ?: Unknown(word)
        }
    }
}

/**
 * A refusal from Graine, or a request that never got an answer. [code] is the
 * stable part — branch on it, never on the message. The message is the
 * server's sentence when it sent one, written to be shown to the user.
 */
class GraineException(
    val code: GraineErrorCode,
    message: String,
    /** When the server asked to wait before trying again (`Retry-After`), in seconds; null when it did not say. */
    val retryAfterSeconds: Int? = null,
) : Exception(message) {
    override fun toString(): String = "GraineException(${code.wire}): $message"
}
