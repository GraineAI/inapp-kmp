package ai.graine.inapp.kmp

import kotlin.math.abs
import kotlin.math.floor

/**
 * HOW JAVASCRIPT READS A VALUE — for the component rules this library ports
 * from the SDK contract (protocol.ts), so a value from a component's payload
 * means here exactly what it means there: `String(v ?? "")`, `s.trim()`,
 * `Math.round`, `Number.isFinite`, a number's own words, and the order
 * `Object.entries` walks an object in. Pure; never throws.
 */
internal object JsRead {
    /**
     * `String(v ?? "")` for the values a payload holds: a number in
     * JavaScript's own words (`2`, `19.5`, `1e+21`), a list its items joined
     * by commas (`["cards"]` reads as `cards`), a map `[object Object]`,
     * nothing the empty string.
     */
    fun string(v: Any?): String = string(v, 0)

    private fun string(v: Any?, depth: Int): String = when (v) {
        null -> ""
        is String -> v
        is Boolean -> v.toString()
        is Double -> number(v)
        is Float -> number(v.toDouble())
        is Number -> v.toLong().toString()
        // An array inside itself reads as nothing there; this stops long before a stack would.
        is List<*> -> if (depth > 32) "" else v.joinToString(",") { string(it, depth + 1) }
        is Map<*, *> -> "[object Object]"
        else -> v.toString()
    }

    /** `s.trim()`: JavaScript's white space and line terminators, from both ends. */
    fun trim(s: String): String = s.trim { ChatPins.isJsSpace(it) }

    /** `typeof v === "number" && Number.isFinite(v)`, as the number. */
    fun finite(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

    /** `Math.round`: the nearest integer, a half toward +∞ — exactly, even for 0.49999999999999994. */
    fun round(d: Double): Double {
        val down = floor(d)
        return if (d - down >= 0.5) down + 1 else down
    }

    /**
     * `String(n)` for a number (Number::toString): the shortest digits that
     * read back as it — Kotlin prints those — with the point and the exponent
     * where JavaScript puts them: `2`, not `2.0`; `0.000001`; `1e+21`; `1e-7`.
     */
    fun number(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        val text = abs(d).toString()
        val e = text.indexOfFirst { it == 'E' || it == 'e' }
        val mantissa = if (e < 0) text else text.substring(0, e)
        val exponent = if (e < 0) 0 else text.substring(e + 1).toIntOrNull() ?: return text
        val dot = mantissa.indexOf('.')
        val all = if (dot < 0) mantissa else mantissa.substring(0, dot) + mantissa.substring(dot + 1)
        val lead = all.indexOfFirst { it != '0' }
        if (lead < 0) return "0"
        val digits = all.substring(lead).trimEnd('0')
        // The value is 0.<digits> × 10^n; k digits.
        val n = (if (dot < 0) mantissa.length else dot) + exponent - lead
        val k = digits.length
        val words = when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + (if (n - 1 >= 0) "+" else "-") + abs(n - 1)
        }
        return if (d < 0) "-$words" else words
    }

    /**
     * `Object.entries(o)`'s order: the keys that are array indices first,
     * smallest first, then the others in the order they were written — which
     * decides what is kept when a list stops at its most.
     */
    fun entries(o: Map<*, *>): List<Pair<String, Any?>> {
        val all = o.entries.map { (k, v) -> k.toString() to v }
        val (indices, names) = all.partition { isArrayIndex(it.first) }
        return indices.sortedBy { it.first.toLong() } + names
    }

    /** A canonical whole number from 0 to 2³² − 2, as JavaScript tells an index from a name. */
    private fun isArrayIndex(k: String): Boolean =
        k.isNotEmpty() && k.length <= 10 && k.all { it in '0'..'9' } && (k.length == 1 || k[0] != '0') && k.toLong() <= 4_294_967_294L
}
