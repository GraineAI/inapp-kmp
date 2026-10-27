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
    /** [GraineAgent.startWorkflow]: no such workflow, "Your app can start this" is off for it, or it isn't published. */
    data object NotAllowed : GraineErrorCode("not_allowed")
    /** [GraineAgent.startWorkflow]: this device isn't one of the user's signed-in devices, or the agent requires a verified user. */
    data object NotVerified : GraineErrorCode("not_verified")
    /** No answer: no network, or Graine could not be reached. Worth trying again. */
    data object Network : GraineErrorCode("network")
    /**
     * This app is not one of the agent's apps ("Only my apps" in the agent's
     * Your apps settings), or it did not say which app it is. Final: never
     * retried. New in 0.14.4.
     */
    data object AppNotAllowed : GraineErrorCode("app_not_allowed")
    /**
     * The agent requires a verified app and this install holds no valid app
     * pass. The SDK proves the app and sends the request once more before it
     * says this. New in 0.14.4.
     */
    data object AppNotVerified : GraineErrorCode("app_not_verified")
    /**
     * The agent requires verified users and the identity sent is not
     * verified: give `identify` an identity token, or set
     * [Graine.identityTokenRefresher]. The SDK asks the refresher and sends
     * the request once more before it says this. New in 0.14.4.
     */
    data object IdentityUnverified : GraineErrorCode("identity_unverified")
    /** The agent requires a server token and none was sent: set [GraineConfig.tokenProvider]. New in 0.14.4. */
    data object TokenRequired : GraineErrorCode("token_required")
    /** The server token could not be read or checked; [GraineException.reason] says why. New in 0.14.4. */
    data object TokenInvalid : GraineErrorCode("token_invalid")
    /** The server token expired, and a fresh one did not come in time. New in 0.14.4. */
    data object TokenExpired : GraineErrorCode("token_expired")
    /**
     * The server token is for another agent (`agent`) or another user (`user`), or lacks the scope this
     * needs (`scope`) — [GraineException.reason]. Never retried. New in 0.14.4.
     */
    data object TokenScope : GraineErrorCode("token_scope")
    /** This publishable key was revoked: ship the agent's new key (Embed & Widgets → Keys). New in 0.14.4. */
    data object KeyRevoked : GraineErrorCode("key_revoked")
    /** A website bot check did not pass. Only a website sees it. New in 0.14.4. */
    data object BotCheckFailed : GraineErrorCode("bot_check_failed")
    data class Unknown(val raw: String) : GraineErrorCode(raw)

    companion object {
        private val known by lazy {
            listOf(
                InvalidKey, OriginRefused, RateLimited, InvalidRequest, IdentityRequired, DeviceLimit, SchedulingOff,
                TooSoon, TooFar, OutsideWindow, TooManyPending, NotFound, Unavailable, NotAllowed, NotVerified, Network,
                AppNotAllowed, AppNotVerified, IdentityUnverified,
                TokenRequired, TokenInvalid, TokenExpired, TokenScope, KeyRevoked, BotCheckFailed,
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
    /** The server's one-word reason beside the code (`scope`, `signature`, …), when it gave one. New in 0.14.4. */
    val reason: String? = null,
) : Exception(message) {
    override fun toString(): String = "GraineException(${code.wire}): $message"
}
