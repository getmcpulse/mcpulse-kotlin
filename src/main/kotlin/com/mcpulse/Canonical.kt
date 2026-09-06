package com.mcpulse

import java.math.BigDecimal
import java.math.MathContext

/**
 * JSON Canonicalization Scheme (RFC 8785).
 *
 * `argsHash` only means anything if every MCPulse SDK, in every language, turns the same arguments
 * into the same bytes. `kotlinx.serialization` does not get there on its own, and neither does
 * [Double.toString]: Kotlin writes `1.0` where ECMAScript writes `1`, and `1.0E21` where it writes
 * `1e+21`. Each of those silently sends the same call to a different bucket than the TypeScript SDK
 * would, and the first-call-success metric built on top becomes noise the moment a customer runs
 * both.
 *
 * So none of the serialisation below goes through a JSON library. Every rule is spelled out, and
 * `canonical.json` — the same file every other MCPulse SDK runs — is what holds this object to
 * them.
 *
 * One thing the JVM gets right for free: RFC 8785 §3.2.3 sorts keys by UTF-16 code unit, and a
 * Kotlin `String` is UTF-16, so natural ordering is already the required one. Python, Go, Rust,
 * Ruby and PHP all need a workaround here.
 */
object Canonical {

    /** Thrown for anything JSON cannot represent: a NaN, an infinity, a cycle, an unknown type. */
    class NotJsonException(message: String) : RuntimeException(message)

    private const val MAX_DEPTH = 1000

    /** The RFC 8785 canonical JSON form of [value]. */
    fun canonicalize(value: Any?): String = buildString { write(this, value, 0, HashSet()) }

    private fun write(out: StringBuilder, value: Any?, depth: Int, seen: MutableSet<Int>) {
        if (depth > MAX_DEPTH) throw NotJsonException("nested too deeply")

        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is CharSequence -> writeString(out, value.toString())
            is Char -> writeString(out, value.toString())

            // Every numeric type is written as the IEEE-754 double RFC 8785 limits JSON to.
            // Matching JavaScript is the point: an integer past 2^53 must lose precision here
            // exactly as it does there, or the two SDKs disagree.
            is Number -> writeNumber(out, value)

            is Map<*, *> -> {
                guard(seen, value)
                try {
                    writeObject(out, value, depth, seen)
                } finally {
                    seen.remove(System.identityHashCode(value))
                }
            }

            is Iterable<*> -> {
                guard(seen, value)
                try {
                    out.append('[')
                    value.forEachIndexed { index, item ->
                        if (index > 0) out.append(',')
                        write(out, item, depth + 1, seen)
                    }
                    out.append(']')
                } finally {
                    seen.remove(System.identityHashCode(value))
                }
            }

            is Array<*> -> write(out, value.asList(), depth, seen)
            is IntArray -> write(out, value.asList(), depth, seen)
            is LongArray -> write(out, value.asList(), depth, seen)
            is DoubleArray -> write(out, value.asList(), depth, seen)

            else -> throw NotJsonException("cannot canonicalize ${value::class.simpleName}")
        }
    }

    private fun guard(seen: MutableSet<Int>, value: Any) {
        if (!seen.add(System.identityHashCode(value))) throw NotJsonException("circular structure")
    }

    private fun writeObject(out: StringBuilder, map: Map<*, *>, depth: Int, seen: MutableSet<Int>) {
        val keys = map.keys.map {
            (it as? CharSequence)?.toString()
                ?: throw NotJsonException("object key must be a string")
        }

        out.append('{')
        // Natural String ordering compares UTF-16 code units, which is exactly what RFC 8785 asks
        // for — including above the BMP, where U+1F680 (the surrogate pair D83D DE80) sorts before
        // U+FFFD.
        keys.sorted().forEachIndexed { index, key ->
            if (index > 0) out.append(',')
            writeString(out, key)
            out.append(':')
            write(out, map[key], depth + 1, seen)
        }
        out.append('}')
    }

    // ─── Strings ─────────────────────────────────────────────────────────────

    private const val HEX = "0123456789abcdef"

    /**
     * A JSON string per JCS §3.2.2.2, which is ECMAScript's escaping: the short escapes where one
     * exists, lowercase `\u00xx` for the rest of the C0 range, and nothing else touched.
     *
     * In particular non-ASCII is written literally.
     */
    private fun writeString(out: StringBuilder, text: String) {
        out.append('"')
        for (c in text) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else ->
                    if (c < ' ') {
                        out.append("\\u00").append(HEX[(c.code shr 4) and 0xF]).append(HEX[c.code and 0xF])
                    } else {
                        out.append(c)
                    }
            }
        }
        out.append('"')
    }

    // ─── Numbers ─────────────────────────────────────────────────────────────

    /**
     * ECMAScript `Number::toString`, which JCS §3.2.2.3 defers to.
     *
     * Kotlin's own formatting is not close: `1.0.toString()` is `"1.0"` and `1e21.toString()` is
     * `"1.0E21"`. Both would disagree with every other SDK.
     */
    private fun writeNumber(out: StringBuilder, number: Number) {
        val value = number.toDouble()

        if (value.isNaN() || value.isInfinite()) {
            // Not JSON. Coercing to null the way some encoders do would hand two genuinely
            // different calls the same hash.
            throw NotJsonException("non-finite number")
        }

        if (value == 0.0) {
            // Covers negative zero, which JCS writes as "0".
            out.append('0')
            return
        }

        var magnitude = value
        if (magnitude < 0) {
            out.append('-')
            magnitude = -magnitude
        }

        val digits = shortestDigits(magnitude)
        val n = decimalPointPosition(magnitude, digits)
        val k = digits.length

        // The five cases of ECMAScript Number::toString, in its own order.
        when {
            k <= n && n <= 21 -> out.append(digits).append("0".repeat(n - k))
            n in 1..21 -> out.append(digits, 0, n).append('.').append(digits, n, k)
            n in -5..0 -> out.append("0.").append("0".repeat(-n)).append(digits)
            else -> {
                val exponent = n - 1
                if (k == 1) out.append(digits) else out.append(digits[0]).append('.').append(digits, 1, k)
                out.append('e').append(if (exponent >= 0) '+' else '-').append(kotlin.math.abs(exponent))
            }
        }
    }

    /**
     * The shortest decimal that round-trips to [value], as digits with no leading or trailing
     * zeros.
     *
     * Written as a search rather than read off [Double.toString] on purpose. The JVM's
     * shortest-round-trip formatting only arrived in JDK 19; on 17 the older algorithm can emit
     * more digits than necessary, and one extra digit is a different hash. Rounding to increasing
     * precision until the value round-trips is exact on every JDK.
     */
    private fun shortestDigits(value: Double): String {
        val exact = BigDecimal(value)
        for (precision in 1..17) {
            val candidate = exact.round(MathContext(precision))
            if (candidate.toDouble() == value) return stripZeros(candidate.unscaledValue().toString())
        }
        return stripZeros(exact.round(MathContext(17)).unscaledValue().toString())
    }

    private fun stripZeros(digits: String): String {
        var end = digits.length
        while (end > 1 && digits[end - 1] == '0') end--
        var start = 0
        while (start < end - 1 && digits[start] == '0') start++
        return digits.substring(start, end)
    }

    /**
     * Where the decimal point sits: the value is `digits * 10^(n - digits.length)`.
     *
     * Derived from the base-10 exponent and then checked, because `log10` is not exact at the
     * boundaries and a one-off there is a different hash.
     */
    private fun decimalPointPosition(value: Double, digits: String): Int {
        val rounded = BigDecimal(digits)
        val estimate = kotlin.math.floor(kotlin.math.log10(value)).toInt() + 1

        for (candidate in (estimate - 1)..(estimate + 1)) {
            if (rounded.movePointLeft(digits.length - candidate).toDouble() == value) return candidate
        }
        return estimate
    }
}
