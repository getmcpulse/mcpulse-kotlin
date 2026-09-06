package com.mcpulse

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Debug output, on stderr.
 *
 * stdout is the transport for a stdio MCP server — a single stray line there corrupts the JSON-RPC
 * stream and takes the customer's server down with it. This is the one thing in the library that
 * would be trivially easy to get wrong and catastrophic to ship, so it goes through one place.
 *
 * Deliberately not SLF4J: a library that logs through the host's configuration can end up on
 * stdout because of a setting it never saw, and an MCP server on stdio is exactly the application
 * most likely to have one.
 */
internal typealias Log = (String) -> Unit

internal object DebugLog {
    fun make(debug: Boolean): Log =
        if (!debug) {
            { }
        } else {
            { message ->
                try {
                    System.err.println("[mcpulse] $message")
                } catch (ignored: Exception) {
                    // Logging is never worth an exception.
                }
            }
        }
}

/**
 * Did this call succeed while returning nothing useful?
 *
 * This is the metric that catches the failures nobody reports: a search that finds no rows, a
 * lookup that misses, a query that comes back `[]`. The protocol calls all of those success, the
 * model gets nothing it can use, and the author never hears about it.
 *
 * Only ever asked of a call that already succeeded — an error has its own outcome and is not also
 * "empty".
 */
internal object Emptiness {

    fun isEmptyResult(result: Any?): Boolean {
        if (result == null) return true

        val structured = member(result, "structuredContent")
        if (structured != null) {
            val inner = unwrapResultEnvelope(structured)
            // What comes out of the envelope is whatever the tool returned. When that is a string
            // it gets the same reading a text part does.
            return if (inner is String) isHollowText(inner) else isHollow(inner)
        }

        val content = member(result, "content")
        if (content is List<*>) return isEmptyContent(content)

        // Not a tool result shape at all — judge the thing itself.
        return isHollow(result)
    }

    /**
     * Undoes a single-key `{"result": …}` wrapper.
     *
     * SDKs that derive an output schema from a handler's return type wrap a non-object return: a
     * tool that returns `"[]"` arrives as `{"result": "[]"}`. Judging the envelope would quietly
     * kill this metric — every result would be an object with one key, so nothing would ever be
     * empty, and the one thing is_empty exists to catch would never fire.
     */
    private fun unwrapResultEnvelope(structured: Any?): Any? {
        if (structured is Map<*, *> && structured.size == 1 && structured.containsKey("result")) {
            return structured["result"]
        }
        return structured
    }

    /**
     * MCP returns content as a list of parts.
     *
     * No parts is empty. One text part is the common case, and it is empty when the text is blank
     * or when the text is itself a serialised empty collection — `"[]"` is the single most common
     * way a tool says "nothing found" while reporting success.
     */
    private fun isEmptyContent(content: List<*>): Boolean {
        if (content.isEmpty()) return true
        if (content.size > 1) return false

        val part = content[0]
        if (member(part, "type") != "text") return false

        val text = member(part, "text")
        return text is String && isHollowText(text)
    }

    private fun isHollowText(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        return try {
            isHollow(MiniJson.parse(trimmed))
        } catch (prose: Exception) {
            // Not JSON. A tool that answers in a sentence has said something.
            false
        }
    }

    /** Empty list, empty map, blank string, or nothing at all. */
    private fun isHollow(value: Any?): Boolean = when (value) {
        null -> true
        is CharSequence -> value.toString().trim().isEmpty()
        is Collection<*> -> value.isEmpty()
        is Map<*, *> -> value.isEmpty()
        // A number or a boolean is an answer. 0 and false are results, not absences, and counting
        // them as empty would report working tools as broken.
        else -> false
    }

    /** Reads a named member off a map, or off an object with a matching accessor. */
    fun member(value: Any?, name: String): Any? {
        if (value == null) return null
        if (value is Map<*, *>) return value[name]

        return try {
            value.javaClass.getMethod(name).invoke(value)
        } catch (absent: Exception) {
            try {
                value.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(value)
            } catch (stillAbsent: Exception) {
                null
            }
        }
    }
}

/**
 * A minimal JSON reader.
 *
 * Deliberately not Jackson or kotlinx.serialization. This library is loaded into other people's
 * servers, and a JSON dependency is the single most likely thing to collide with what a customer
 * already pins. The only thing needed here is reading a tool's text result to see whether it is an
 * empty collection — canonical JSON does not come from here, [Canonical] owns that.
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue()
        reader.skipWhitespace()
        require(reader.done()) { "trailing content" }
        return value
    }

    private class Reader(private val source: String) {
        private var at = 0

        fun done() = at >= source.length

        fun skipWhitespace() {
            while (at < source.length && source[at].isWhitespace()) at++
        }

        fun readValue(): Any? {
            require(!done()) { "unexpected end" }
            return when (source[at]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> readNumber()
            }
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            at++
            skipWhitespace()
            if (!done() && source[at] == '}') {
                at++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                out[key] = readValue()
                skipWhitespace()
                require(!done()) { "unterminated object" }
                when (source[at++]) {
                    '}' -> return out
                    ',' -> continue
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            at++
            skipWhitespace()
            if (!done() && source[at] == ']') {
                at++
                return out
            }
            while (true) {
                skipWhitespace()
                out.add(readValue())
                skipWhitespace()
                require(!done()) { "unterminated array" }
                when (source[at++]) {
                    ']' -> return out
                    ',' -> continue
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(!done()) { "unterminated string" }
                val c = source[at++]
                if (c == '"') return out.toString()
                if (c != '\\') {
                    out.append(c)
                    continue
                }
                when (val escape = source[at++]) {
                    '"', '\\', '/' -> out.append(escape)
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> {
                        out.append(source.substring(at, at + 4).toInt(16).toChar())
                        at += 4
                    }
                    else -> throw IllegalArgumentException("bad escape")
                }
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            require(source.startsWith(literal, at)) { "bad literal" }
            at += literal.length
            return value
        }

        private fun readNumber(): Double {
            val start = at
            while (at < source.length && source[at] in "-+.eE0123456789") at++
            require(start != at) { "expected a value" }
            return source.substring(start, at).toDouble()
        }

        private fun expect(c: Char) {
            require(!done() && source[at++] == c) { "expected $c" }
        }
    }
}

/**
 * Holds payloads and sends them in batches, on a thread of its own.
 *
 * The contract with the tool call that produced a payload is that [add] returns immediately and
 * never throws. Everything expensive happens on a scheduled executor, so no model ever waits on
 * MCPulse to answer.
 *
 * The executor's thread is a daemon: a customer's server must be free to exit while a flush is
 * pending, and MCPulse holding their process open would be the same failure as blocking their
 * request path, just slower to notice.
 */
internal class PayloadBuffer(private val options: Options, private val log: Log) {

    private val lock = Any()
    private val pending = ArrayDeque<Map<String, Any?>>()
    private var closed = false

    /** Held for the duration of a batch, so a flush waits for a real send. */
    private val sending = Any()

    private val worker = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "mcpulse").apply { isDaemon = true }
    }

    init {
        worker.scheduleWithFixedDelay(
            ::sendQuietly,
            Options.FLUSH_EVERY_MS,
            Options.FLUSH_EVERY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Buffers one payload. Returns immediately, never throws. */
    fun add(payload: Map<String, Any?>) {
        try {
            val ready: Boolean
            synchronized(lock) {
                if (closed) return
                if (pending.size >= Options.MAX_BUFFERED) {
                    // Oldest first: recent calls describe what the server is doing now, and that
                    // is the more useful half of a buffer that could not be sent.
                    pending.pollFirst()
                    log("buffer full, dropped oldest payload")
                }
                pending.addLast(payload)
                ready = pending.size >= Options.FLUSH_AT_ITEMS
            }
            if (ready) worker.execute(::sendQuietly)
        } catch (ignored: Exception) {
            // Recording must never be the reason a tool call fails.
        }
    }

    private fun sendQuietly() {
        try {
            sendOnce()
        } catch (error: Exception) {
            log("send failed: ${error.message}")
        }
    }

    private fun sendOnce() {
        synchronized(sending) {
            val batch: List<Map<String, Any?>>
            synchronized(lock) {
                if (pending.isEmpty()) return
                // Taken in one go: anything added while this is in flight belongs to the next
                // batch, not this one.
                batch = pending.toList()
                pending.clear()
            }

            val sent = Transport.postBatch(batch, options)
            log("${if (sent) "sent" else "dropped"} ${batch.size} payloads")
        }
    }

    /** Final flush, best effort. After this the buffer accepts nothing more. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
        }

        worker.execute(::sendQuietly)
        worker.shutdown()
        try {
            if (!worker.awaitTermination(Options.EXIT_FLUSH_MS, TimeUnit.MILLISECONDS)) {
                log("exit flush timed out")
                worker.shutdownNow()
            }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            worker.shutdownNow()
        }
    }
}

/**
 * Posting one batch.
 *
 * The JDK's [HttpClient] rather than OkHttp or Ktor: this library is loaded into other people's
 * servers, and a transitive HTTP dependency that conflicts with what the customer already pins is
 * a support burden with no upside for a single POST.
 */
internal object Transport {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Options.SEND_TIMEOUT_MS))
        .build()

    /**
     * Sends one batch and reports whether it landed. Never throws — a caller must not have to
     * catch.
     *
     * A failed batch is dropped, deliberately. Retrying means either a queue that grows while the
     * network is down, or duplicate rows when a 202 is lost on the way back. Neither is worth it
     * for analytics: the next flush is five seconds away, and a gap in a chart is a far smaller
     * problem than memory growth inside someone else's server.
     */
    fun postBatch(payloads: List<Map<String, Any?>>, options: Options): Boolean {
        if (payloads.isEmpty()) return true

        return try {
            // Canonical output is not required here — the API parses this, it is never hashed —
            // but reusing the one writer keeps a second encoder out of the library.
            val body = Canonical.canonicalize(mapOf("batch" to payloads))

            val request = HttpRequest.newBuilder(URI.create("${options.cleanEndpoint}/v1/ingest"))
                .timeout(Duration.ofMillis(Options.SEND_TIMEOUT_MS))
                .header("content-type", "application/json")
                .header("authorization", "Bearer ${options.cleanKey}")
                .header("user-agent", "mcpulse-kotlin")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
                .build()

            val response = client.send(request, HttpResponse.BodyHandlers.discarding())
            response.statusCode() in 200..299
        } catch (interrupted: InterruptedException) {
            // Restore the flag and give up on this batch: a shutdown in progress is not something
            // analytics should argue with.
            Thread.currentThread().interrupt()
            false
        } catch (unreachable: Exception) {
            // DNS, TLS, a timeout, a proxy that hung up. All the same to us.
            false
        }
    }
}
