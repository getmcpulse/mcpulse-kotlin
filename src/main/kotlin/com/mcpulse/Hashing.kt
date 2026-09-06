package com.mcpulse

import java.security.MessageDigest
import java.security.SecureRandom

/** Fingerprinting a call's arguments. */
object Hashing {

    /** What an argument set hashes to when it cannot be serialised at all. */
    const val UNHASHABLE = "000000000000"

    private val random = SecureRandom()
    private const val HEX = "0123456789abcdef"

    /**
     * A short, one-way fingerprint of a call's arguments.
     *
     * This is the only thing MCPulse ever learns about what was passed to a tool, and it is
     * deliberately not enough to learn anything: 12 hex characters of a SHA-256 over the RFC 8785
     * canonical form, with no way back. All the product asks of it is "were these two calls made
     * with the same arguments or different ones" — which is what separates a model retrying a
     * reworded request from a client paging through results.
     */
    fun argsHash(args: Any?): String {
        // A tool that takes no arguments is called with `arguments` absent. That is an ordinary
        // call, not a failure, and it hashes as the empty object it is — otherwise every
        // no-argument tool shares one hash with every call whose arguments blew up.
        val value = args ?: emptyMap<String, Any?>()

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(Canonical.canonicalize(value).toByteArray(Charsets.UTF_8))
            hex(digest, 6)
        } catch (unserialisable: Exception) {
            // Arguments JSON cannot represent. The call still happened and still deserves a row;
            // it simply cannot be compared to another, so give it a constant that says exactly
            // that.
            UNHASHABLE
        }
    }

    /**
     * Identifies one run of the customer's server, so calls can be grouped and a cost-per-session
     * worked out.
     *
     * Random rather than derived — there is nothing about the process worth encoding here, and
     * anything derived from the machine would be an identifier we did not intend to collect.
     */
    fun newSessionId(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return "s_" + hex(bytes, 6)
    }

    private fun hex(bytes: ByteArray, count: Int): String = buildString {
        for (i in 0 until minOf(count, bytes.size)) {
            append(HEX[(bytes[i].toInt() shr 4) and 0xF])
            append(HEX[bytes[i].toInt() and 0xF])
        }
    }
}

/** How MCPulse measures what a payload costs a context window. */
internal object Sizes {

    /**
     * The length of [text] in UTF-16 code units.
     *
     * `response_bytes` and `schema_bytes` are, today, what JavaScript's `String.length` returns —
     * code units, not bytes. The fields are named for bytes and hold code units, so `"café"`
     * measures 4 and an emoji measures 2.
     *
     * That is a known wart in the wire format, and fixing it is a pending decision. Until it is
     * made, every port reproduces the TypeScript behaviour rather than each inventing its own,
     * because the whole value of these numbers is that they are comparable across a customer's
     * servers. When the wire fixes it, this function is the one line that changes.
     *
     * The JVM is one of the two places where this needs no work: a Kotlin String is already UTF-16.
     */
    fun utf16Length(text: String): Int = text.length
}
