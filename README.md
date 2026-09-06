# mcpulse-kotlin

Analytics for MCP servers, in Kotlin.

```kotlin
import com.getmcpulse.MCPulse
import com.getmcpulse.Options

MCPulse.configure(Options(key = "mp_live_…"))

// Around your tool handler:
val result = MCPulse.record("search", arguments, clientName) {
    handler(arguments)
}
```

Wrapping the handler rather than watching from outside is what lets MCPulse tell
a handler that threw from one that returned an error result — a distinction an
MCP server erases by converting both into `isError` before anything outside sees
it.

## Install

```kotlin
dependencies {
    implementation("com.getmcpulse:mcpulse-kotlin:0.1.0")
}
```

No transitive dependencies. This library is loaded into other people's servers,
and a pin that conflicts with theirs is a support burden with no upside. The
canonicaliser, the JSON reader and the HTTP client are all owned here or come
from the JDK — nothing this package brings can collide with the Jackson or Ktor
your application has already settled on.

## Options

| Field | Default | Meaning |
|---|---|---|
| `key` | — | Ingest key, `mp_live_…`, minted per MCP in the dashboard |
| `endpoint` | `https://api.getmcpulse.com` | Point at a local API while developing |
| `enabled` | `true` | `false` makes everything a no-op — useful in tests and CI |
| `debug` | `false` | Log what is sent, and why a send failed, to **stderr** |

An empty key turns it off, so a server started without its key configured is
silent rather than a source of 401s on every flush.

## One known gap

`bad_args` is not reported. A server that validates arguments before calling the
handler rejects them outside the block, so the call never reaches `record`.
Reporting it anyway would mean reading the difference back out of an error
message, and error strings are not an interface anyone promised to keep. `ok`,
`tool_error` and `crashed` are all exact.

## What leaves your process

Sizes and hashes. Arguments and results do not, and no option turns that on.

## The three rules

1. **Never throw.** Every entry point swallows. Your exception is re-thrown
   untouched; ours never reach you.
2. **Never block.** Sending happens on a daemon thread, so your server is free
   to exit while a flush is pending.
3. **Never store customer data.** See above.

## Cross-language consistency

`argsHash` is the first 12 hex characters of the SHA-256 of the
[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785) canonical form of the
arguments. `src/test/resources/canonical.json` is the shared conformance suite
every MCPulse SDK runs.

Kotlin needed two things undone and got one for free. `1.0.toString()` is
`"1.0"` and `1e21.toString()` is `"1.0E21"`, neither of which ECMAScript would
write — and the JVM's shortest-round-trip formatting only arrived in JDK 19, so
the digits are found by a `BigDecimal` search that is exact on any JDK. The free
part: RFC 8785 sorts keys by UTF-16 code unit, which is what natural `String`
ordering already does. Python, Go, Rust, Ruby and PHP all need a workaround.

## Running the tests

```bash
./gradlew test
```

Or with nothing but `kotlinc`:

```bash
./fetch-test-deps.sh    # lib/ is gitignored; this pulls the jars
CP="lib/jackson-databind-2.18.2.jar:lib/jackson-core-2.18.2.jar:lib/jackson-annotations-2.18.2.jar"
kotlinc src/main/kotlin/com/mcpulse/*.kt src/test/kotlin/com/mcpulse/*.kt -cp "$CP" -d out
java -cp "out:$CP:$KOTLIN_HOME/lib/kotlin-stdlib.jar" com.getmcpulse.ConformanceTest
```

## Licence

MIT
