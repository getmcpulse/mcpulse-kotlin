package com.mcpulse

import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

/**
 * The cross-language contract, plus the parts of the SDK only the JVM can get wrong.
 *
 * `canonical.json` is the shared conformance suite, copied from
 * `packages/schemas/fixtures` in the mcpulse monorepo. Every other
 * MCPulse SDK runs the same file. If it passes in all of them, their hashes are interchangeable and
 * a customer running more than one sees one set of numbers rather than several.
 *
 * Never edit a fixture to make a failure go away — these hashes are in the product's history, and
 * rewriting one rewrites what every stored row means.
 *
 * A plain `main` rather than a test framework, so the suite runs with nothing but kotlinc.
 */
object ConformanceTest {

    private var passed = 0
    private var failed = 0

    @JvmStatic
    fun main(args: Array<String>) {
        fixtures(args.firstOrNull() ?: "src/test/resources/canonical.json")
        numbers()
        strings()
        keyOrder()
        argsHash()
        emptiness()
        recording()

        println()
        println("$passed passed, $failed failed")
        if (failed > 0) kotlin.system.exitProcess(1)
    }

    private fun fixtures(path: String) {
        val mapper = ObjectMapper()
        val file = mapper.readTree(File(path).readText())

        check("algorithm is pinned", "sha256/rfc8785/hex12", file["algorithm"].asText())
        check("wire version is pinned", 1, file["wire_version"].asInt())
        check("the full suite is present", true, file["fixtures"].size() >= 23)

        for (fixture in file["fixtures"]) {
            val name = fixture["name"].asText()
            val input = mapper.treeToValue(fixture["input"], Any::class.java)

            check("canonical: $name", fixture["canonical"].asText(), Canonical.canonicalize(input))
            check("hash: $name", fixture["args_hash"].asText(), Hashing.argsHash(input))

            // Each fixture's hash must match its own canonical form, so a corrupted file is caught
            // rather than silently agreed with.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(fixture["canonical"].asText().toByteArray(Charsets.UTF_8))
            check(
                "self-consistent: $name",
                fixture["args_hash"].asText(),
                digest.take(6).joinToString("") { "%02x".format(it) },
            )
        }
    }

    /** ECMAScript Number::toString, which is where Kotlin's formatting differs most. */
    private fun numbers() {
        check("1.0 loses the decimal", "1", Canonical.canonicalize(1.0))
        check("negative zero", "0", Canonical.canonicalize(-0.0))
        check("2.5", "2.5", Canonical.canonicalize(2.5))
        check("1e21", "1e+21", Canonical.canonicalize(1e21))
        check("1e-7", "1e-7", Canonical.canonicalize(1e-7))
        check("1e-6", "0.000001", Canonical.canonicalize(1e-6))
        check("0.1", "0.1", Canonical.canonicalize(0.1))
        check("min subnormal", "5e-324", Canonical.canonicalize(Double.MIN_VALUE))
        check("max double", "1.7976931348623157e+308", Canonical.canonicalize(Double.MAX_VALUE))
        check("-1.5e-9", "-1.5e-9", Canonical.canonicalize(-1.5e-9))
        check("2^53-1", "9007199254740991", Canonical.canonicalize(9007199254740991L))
        check("a million stays plain", "1000000", Canonical.canonicalize(1000000))
        check("plain integers", "42", Canonical.canonicalize(42))

        // Integers are doubles, as RFC 8785 requires and as JavaScript does.
        check(
            "big integers are doubles",
            Canonical.canonicalize(1.2345678901234567e19),
            Canonical.canonicalize(BigInteger("12345678901234567890")),
        )

        checkThrows("NaN is refused") { Canonical.canonicalize(Double.NaN) }
        checkThrows("Infinity is refused") { Canonical.canonicalize(Double.POSITIVE_INFINITY) }
    }

    private fun strings() {
        check("non-ascii is literal", "\"café\"", Canonical.canonicalize("café"))
        check("emoji is literal", "\"🚀\"", Canonical.canonicalize("🚀"))
        check("html is not escaped", "\"a<b>c&d\"", Canonical.canonicalize("a<b>c&d"))
        check(
            "short escapes",
            "\"\\b\\t\\n\\f\\r\\\"\\\\\"",
            Canonical.canonicalize("\b\t\n\u000C\r\"\\"),
        )
        check(
            "other control chars",
            "\"\\u0000\\u0001\\u001f\"",
            Canonical.canonicalize("\u0000\u0001\u001F"),
        )
    }

    private fun keyOrder() {
        check(
            "sorts at every depth",
            "{\"o\":{\"a\":2,\"z\":1}}",
            Canonical.canonicalize(mapOf("o" to linkedMapOf("z" to 1, "a" to 2))),
        )

        // U+1F680 is the surrogate pair D83D DE80, so it sorts before U+FFFD. Natural String
        // ordering gets this right for free; Python, Go, Rust, Ruby and PHP all need a workaround.
        check(
            "utf-16 key order",
            "{\"a\":4,\"é\":3,\"🚀\":2,\"�\":1}",
            Canonical.canonicalize(
                linkedMapOf("�" to 1, "🚀" to 2, "é" to 3, "a" to 4),
            ),
        )

        check("array order is left alone", "[2,1]", Canonical.canonicalize(listOf(2, 1)))
    }

    private fun argsHash() {
        // A no-argument tool is an ordinary call. Sharing the failure sentinel would make every
        // such tool look broken.
        check("absent args are {}", Hashing.argsHash(emptyMap<String, Any>()), Hashing.argsHash(null))
        refute("absent args are not the sentinel", Hashing.UNHASHABLE, Hashing.argsHash(null))

        val circular = HashMap<String, Any?>()
        circular["self"] = circular
        check("circular gets the sentinel", Hashing.UNHASHABLE, Hashing.argsHash(circular))
        check("NaN gets the sentinel", Hashing.UNHASHABLE, Hashing.argsHash(mapOf("n" to Double.NaN)))

        val hash = Hashing.argsHash(mapOf("q" to "anything"))
        check("twelve characters", 12, hash.length)
        check("lowercase hex", hash, hash.lowercase())

        val id = Hashing.newSessionId()
        check("session id shape", true, id.startsWith("s_") && id.length == 14)
        refute("session ids differ", id, Hashing.newSessionId())

        check("utf16 length counts code units", 4, Sizes.utf16Length("café"))
        check("an emoji is two code units", 2, Sizes.utf16Length("🚀"))
    }

    private fun textResult(text: String): Map<String, Any?> =
        mapOf("content" to listOf(mapOf("type" to "text", "text" to text)))

    private fun emptiness() {
        check("null is empty", true, Emptiness.isEmptyResult(null))
        check("no parts is empty", true, Emptiness.isEmptyResult(mapOf("content" to emptyList<Any>())))
        check("a serialised empty list is empty", true, Emptiness.isEmptyResult(textResult("[]")))
        check("blank text is empty", true, Emptiness.isEmptyResult(textResult("   ")))
        check("prose is not empty", false, Emptiness.isEmptyResult(textResult("no rows found")))

        // 0 and false are results, not absences. Counting them as empty would report working tools
        // as broken.
        check("zero is an answer", false, Emptiness.isEmptyResult(textResult("0")))
        check("false is an answer", false, Emptiness.isEmptyResult(textResult("false")))

        // The {"result": …} envelope some SDKs add must not hide an empty answer.
        check(
            "the result envelope is opened",
            true,
            Emptiness.isEmptyResult(mapOf("structuredContent" to mapOf("result" to "[]"))),
        )
    }

    private fun recording() {
        val recorded = mutableListOf<Map<String, Any?>>()
        Stream.sink = { recorded.add(it) }
        MCPulse.configure(Options(key = "mp_test_key", endpoint = "http://127.0.0.1:1"))

        val result = MCPulse.record(
            "echo",
            mapOf("text" to "sensitive-argument-value"),
            "test-client",
        ) { textResult("hello") }

        val calls = recorded.filter { it["type"] == "call" }
        check("one call recorded", 1, calls.size)
        check("tool name", "echo", calls[0]["tool_name"])
        check("outcome", "ok", calls[0]["outcome"])
        check("wire version", 1, calls[0]["v"])
        check("client name", "test-client", calls[0]["client_name"])
        check("result passes through", "hello", ((result["content"] as List<*>)[0] as Map<*, *>)["text"])
        check("is_empty", false, calls[0]["is_empty"])
        check("args_hash is twelve characters", 12, (calls[0]["args_hash"] as String).length)
        check(
            "no argument value on the wire",
            false,
            ObjectMapper().writeValueAsString(recorded).contains("sensitive-argument-value"),
        )

        // Argument order must not change the hash.
        recorded.clear()
        MCPulse.record("two", linkedMapOf("a" to 1, "b" to 2)) { textResult("x") }
        MCPulse.record("two", linkedMapOf("b" to 2, "a" to 1)) { textResult("x") }
        check("reordered arguments hash the same", recorded[0]["args_hash"], recorded[1]["args_hash"])

        // A throwing handler is crashed, and the exception still reaches the server.
        recorded.clear()
        var rethrown = false
        try {
            MCPulse.record<Any>("explode", null) { throw IllegalStateException("boom") }
        } catch (expected: IllegalStateException) {
            rethrown = true
        }
        check("the exception still reaches the server", true, rethrown)
        check("outcome is crashed", "crashed", recorded[0]["outcome"])

        // An isError result is a tool error, not a crash.
        recorded.clear()
        MCPulse.record("failing", null) { mapOf("content" to emptyList<Any>(), "isError" to true) }
        check("outcome is tool_error", "tool_error", recorded[0]["outcome"])

        // An empty answer is flagged.
        recorded.clear()
        MCPulse.record("nothing", null) { textResult("[]") }
        check("an empty result is flagged", true, recorded[0]["is_empty"])

        // Startup, once.
        recorded.clear()
        MCPulse.recordStartup(
            listOf(mapOf("name" to "echo", "inputSchema" to mapOf("type" to "object"))),
            "test-client",
        )
        MCPulse.recordStartup(listOf(mapOf("name" to "echo")))
        check("startup is sent once", 1, recorded.size)
        check("startup type", "startup", recorded[0]["type"])

        // Two configurations for the same destination are one session, not two.
        //
        // The bug this guards against is invisible in a stdio server and fatal in an HTTP one: a
        // server configured per request would open a session per request, so a retry could never
        // be detected and first-call success would report a perfect score however badly the server
        // was doing.
        MCPulse.configure(Options(key = "mp_test_key", endpoint = "http://127.0.0.1:1"))
        val firstSession = MCPulse.sessionId
        MCPulse.configure(Options(key = "mp_test_key", endpoint = "http://127.0.0.1:1"))
        check("the same destination is one session", firstSession, MCPulse.sessionId)

        // Two keys are two customers. Merging them would file one customer's calls under another's.
        MCPulse.configure(Options(key = "mp_other_key", endpoint = "http://127.0.0.1:1"))
        refute("different keys are different sessions", firstSession, MCPulse.sessionId)

        // Configured off means nothing is recorded, and the handler still runs.
        recorded.clear()
        MCPulse.configure(Options(key = ""))
        val stillRan = MCPulse.record("echo", null) { textResult("hello") }
        check("a disabled handler still runs", true, stillRan.isNotEmpty())
        check("an empty key records nothing", 0, recorded.size)

        Stream.sink = null
    }

    // ─── Harness ─────────────────────────────────────────────────────────────

    private fun check(what: String, expected: Any?, actual: Any?) {
        if (expected == actual) {
            passed++
        } else {
            failed++
            println("FAIL $what\n  want $expected\n  got  $actual")
        }
    }

    private fun refute(what: String, forbidden: Any?, actual: Any?) {
        if (forbidden != actual) {
            passed++
        } else {
            failed++
            println("FAIL $what: got the forbidden value $forbidden")
        }
    }

    private fun checkThrows(what: String, body: () -> Unit) {
        try {
            body()
            failed++
            println("FAIL $what: nothing was thrown")
        } catch (expected: Exception) {
            passed++
        }
    }
}
