package com.mcpulse

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Everything [MCPulse] accepts, and what it means when you leave it out.
 *
 * An empty [key] turns the SDK off: a server started without its key configured should be silent,
 * not a source of 401s on every flush.
 */
data class Options(
    /** Ingest key, `mp_live_…`, minted per MCP in the dashboard. */
    val key: String,
    /** Point at a local API while developing. */
    val endpoint: String = DEFAULT_ENDPOINT,
    /** `false` makes everything a no-op — useful in tests and CI. */
    val enabled: Boolean = true,
    /** Log what is being sent, and why a send failed, to stderr. */
    val debug: Boolean = false,
) {
    companion object {
        /** Where payloads go when no endpoint is given. */
        const val DEFAULT_ENDPOINT = "https://api.getmcpulse.com"

        /** Flush when either is reached, whichever comes first. */
        const val FLUSH_AT_ITEMS = 30
        const val FLUSH_EVERY_MS = 5_000L

        /**
         * Hard ceiling on the buffer. Reached only when the network is gone; past it the oldest
         * payloads are dropped, because a customer's server running out of memory over our
         * analytics is the one failure we must never cause.
         */
        const val MAX_BUFFERED = 1000

        /** Best-effort window for the final flush on the way out, and the cap on one batch. */
        const val EXIT_FLUSH_MS = 1_000L
        const val SEND_TIMEOUT_MS = 10_000L

        /** Caps, so one malformed name cannot bloat a batch. */
        const val MAX_TOOL_NAME = 200
        const val MAX_CLIENT_NAME = 128
        const val MAX_TOOLS = 500
    }

    internal val cleanKey: String get() = key.trim()

    internal val cleanEndpoint: String
        get() = (if (endpoint.isBlank()) DEFAULT_ENDPOINT else endpoint).trimEnd('/')

    internal val active: Boolean get() = enabled && cleanKey.isNotEmpty()

    internal val streamKey: String get() = "$cleanEndpoint|$cleanKey"
}

/** How a tool call ended. Exactly one of these, always. */
enum class Outcome(val wire: String) {
    /** Ran and returned a result. */
    OK("ok"),

    /** Arguments failed validation; the handler never ran. */
    BAD_ARGS("bad_args"),

    /** Ran and returned `isError: true`. */
    TOOL_ERROR("tool_error"),

    /** Threw. */
    CRASHED("crashed"),
}

/**
 * Analytics for MCP servers.
 *
 * ```kotlin
 * MCPulse.configure(Options(key = "mp_live_…"))
 *
 * val result = MCPulse.record("search", arguments, clientName) { handler(arguments) }
 * ```
 *
 * Three rules this library keeps, in order of how badly it would hurt to break one:
 *
 * 1. **Never throw.** Every entry point swallows. If MCPulse fails inside a customer's tool call,
 *    their tool fails and they blame us.
 * 2. **Never block.** Record, buffer, return. Nothing waits on the network on the path a model is
 *    waiting on.
 * 3. **Never store customer data.** Sizes and hashes leave this process. Arguments and results do
 *    not, and no option turns that off.
 */
object MCPulse {

    @Volatile
    private var stream: Stream? = null

    /**
     * Starts recording, or turns everything into a no-op if the options say not to.
     *
     * Idempotent: calling it twice reuses the same session rather than opening a second one. Left
     * unguarded, a server built per request would report every call under two sessions and double
     * both the customer's numbers and their bill.
     */
    @JvmStatic
    fun configure(options: Options) {
        try {
            val log = DebugLog.make(options.debug)

            if (!options.active) {
                log("disabled — no key, or enabled = false")
                stream = null
                return
            }

            stream = Stream.forOptions(options, log)
            log("watching")
        } catch (failure: Exception) {
            // Deliberately silent. Failing here must look like configure was never called, and
            // there is no logger to complain to if the options were the thing that was malformed.
            stream = null
        }
    }

    /**
     * The session calls are being filed under, or `null` when recording is off.
     *
     * Exposed so a server can log which session it joined, and so the shared-session guarantee can
     * be asserted rather than assumed.
     */
    @JvmStatic
    val sessionId: String? get() = stream?.sessionId

    /** Notes who is connected, so calls can be attributed to a client. */
    @JvmStatic
    fun rememberClient(name: String?) {
        stream?.rememberClient(name)
    }

    /**
     * Times one tool call and buffers the result.
     *
     * The block's return value is handed back untouched and an exception is re-thrown untouched,
     * so a recorded call behaves exactly like an unrecorded one.
     *
     * Wrapping the handler rather than watching from outside is what lets MCPulse tell a handler
     * that threw from one that returned an error result — a distinction an MCP server erases by
     * converting both into `isError` before anything outside can see it.
     */
    @JvmStatic
    fun <T> record(
        toolName: String,
        arguments: Any?,
        clientName: String? = null,
        handler: () -> T,
    ): T {
        val active = stream ?: return handler()
        active.rememberClient(clientName)

        val startedAt = Instant.now()
        val started = System.nanoTime()

        var result: Any? = null
        var threw = false
        try {
            val value = handler()
            result = value
            return value
        } catch (error: Throwable) {
            threw = true
            // Re-thrown untouched: swallowing it would change what the customer's server does.
            throw error
        } finally {
            try {
                emitCall(active, toolName, arguments, result, threw, startedAt, started)
            } catch (ignored: Exception) {
                // Recording must never be the reason a tool call fails.
            }
        }
    }

    /**
     * Reports the server's tool list, once per session.
     *
     * `schema_bytes` is the cost of a tool's presence in the context window, so pass the JSON that
     * actually goes over the wire — what `tools/list` returns — not the Kotlin type the tool was
     * declared from.
     */
    @JvmStatic
    @JvmOverloads
    fun recordStartup(tools: Iterable<Any?>, clientName: String? = null) {
        try {
            val active = stream ?: return
            if (!active.claimStartup()) return
            active.rememberClient(clientName)

            val described = tools.take(Options.MAX_TOOLS).mapNotNull { tool ->
                val name = Emptiness.member(tool, "name") as? String ?: return@mapNotNull null
                if (name.isEmpty()) return@mapNotNull null
                mapOf(
                    "name" to name.take(Options.MAX_TOOL_NAME),
                    "schema_bytes" to measure(tool),
                )
            }

            active.emit(
                mapOf(
                    "v" to 1,
                    "type" to "startup",
                    "session_id" to active.sessionId,
                    "client_name" to active.client,
                    "tools" to described,
                )
            )
            active.log("startup: ${described.size} tools, client ${active.client}")
        } catch (ignored: Exception) {
            // A startup payload is worth nothing next to the server that would have failed for it.
        }
    }

    /**
     * Sends everything buffered and stops accepting more.
     *
     * A JVM shutdown hook already does this. Call it by hand only when the server stops without the
     * JVM exiting — a test suite, or a host that restarts servers in place.
     */
    @JvmStatic
    fun flushAll() = Stream.flushAll()

    private fun emitCall(
        stream: Stream,
        toolName: String,
        arguments: Any?,
        result: Any?,
        threw: Boolean,
        startedAt: Instant,
        startedNanos: Long,
    ) {
        val outcome = decideOutcome(result, threw)
        val name = toolName.ifEmpty { "unknown" }

        stream.emit(
            mapOf(
                "v" to 1,
                "type" to "call",
                "session_id" to stream.sessionId,
                "client_name" to stream.client,
                "tool_name" to name.take(Options.MAX_TOOL_NAME),
                "started_at" to startedAt.toString(),
                "duration_ms" to maxOf(0L, (System.nanoTime() - startedNanos) / 1_000_000L),
                "outcome" to outcome.wire,
                "response_bytes" to measure(result),
                // An error is not also an absence — it has its own outcome already.
                "is_empty" to (outcome == Outcome.OK && Emptiness.isEmptyResult(result)),
                "args_hash" to Hashing.argsHash(arguments),
            )
        )
    }

    /**
     * What the outcome was, given that the handler is what we wrapped.
     *
     * Wrapping the block means a throw arrives here as a throw rather than as the `isError` result
     * the server would have converted it into. What cannot be seen from here is `bad_args`: a
     * server that validates arguments before calling the handler rejects them outside the block.
     * Reporting it anyway would mean reading the difference back out of an error message, and error
     * strings are not an interface anyone promised to keep.
     */
    private fun decideOutcome(result: Any?, threw: Boolean): Outcome = when {
        threw -> Outcome.CRASHED
        Emptiness.member(result, "isError") == true -> Outcome.TOOL_ERROR
        else -> Outcome.OK
    }

    /** What something costs the context window. Unserialisable means unmeasurable. */
    private fun measure(value: Any?): Int = try {
        if (value == null) 0 else Sizes.utf16Length(Canonical.canonicalize(value))
    } catch (unmeasurable: Exception) {
        0
    }
}

/**
 * One session and one buffer per destination, for the life of the process.
 *
 * The obvious shape is to make both where the server is built, which is right for a stdio server —
 * one process, one server, one session — and wrong for an HTTP one. A streamable-HTTP server
 * builds a fresh handler per request, and every tool call would become a session of its own.
 *
 * That is not a cosmetic difference. Retries are found by looking for the same tool twice inside
 * one session, and first-call success is defined as no retry following. With one call per session
 * there can never be a retry, so the server reports a perfect score however badly it is doing — the
 * one number this product exists to tell the truth about.
 *
 * Keyed by endpoint and key rather than a bare singleton: two watched servers reporting to
 * different MCPs in one process are two different streams, and merging them would file one
 * customer's calls under another's.
 */
internal class Stream private constructor(options: Options, val log: Log) {

    val sessionId: String = Hashing.newSessionId()
    val buffer = PayloadBuffer(options, log)

    private val clientName = AtomicReference("unknown")
    private val startupSent = AtomicBoolean(false)

    /**
     * Whoever most recently identified themselves.
     *
     * One value per process per destination, last identification wins. For a server with two
     * concurrent clients that is an approximation, but it is the same approximation the shared
     * session already makes, and a name that is occasionally the other client's beats a column
     * that is always "unknown".
     */
    val client: String get() = clientName.get()

    fun rememberClient(name: String?) {
        if (!name.isNullOrEmpty()) clientName.set(name.take(Options.MAX_CLIENT_NAME))
    }

    fun claimStartup(): Boolean = startupSent.compareAndSet(false, true)

    /** Hands one payload to the buffer, or to a test's capture. */
    fun emit(payload: Map<String, Any?>) {
        val capture = sink
        if (capture != null) {
            capture(payload)
            return
        }
        buffer.add(payload)
    }

    companion object {
        /** Diverts payloads away from the buffer. Only tests set it. */
        @Volatile
        @JvmStatic
        var sink: ((Map<String, Any?>) -> Unit)? = null

        private val streams = ConcurrentHashMap<String, Stream>()
        private val hookRegistered = AtomicBoolean(false)

        fun forOptions(options: Options, log: Log): Stream =
            streams.computeIfAbsent(options.streamKey) {
                if (hookRegistered.compareAndSet(false, true)) {
                    // Registered once for the whole library, however many servers are watched. A
                    // shutdown hook runs before the daemon threads are torn down, which is what
                    // makes the final flush possible at all.
                    Runtime.getRuntime().addShutdownHook(Thread(::flushAll, "mcpulse-exit"))
                }
                Stream(options, log)
            }

        /** One last flush on the way out, so the final few calls of a session are not lost. */
        fun flushAll() {
            val pending = streams.values.toList()
            streams.clear()
            for (stream in pending) {
                try {
                    stream.buffer.close()
                } catch (ignored: Exception) {
                    // Nothing left to report to.
                }
            }
        }
    }
}
