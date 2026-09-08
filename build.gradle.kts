plugins {
    kotlin("jvm") version "2.0.21"
    `java-library`
    `maven-publish`
}

group = "com.getmcpulse"
version = "0.1.0"

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
}

dependencies {
    // No implementation dependencies at all, deliberately. This library is loaded into other
    // people's servers, and a transitive pin that conflicts with theirs is a support burden with
    // no upside. The canonicaliser, the JSON reader and the HTTP client are all owned here or
    // come from the JDK.

    // Jackson is used only to read the conformance fixtures in the test suite.
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("mcpulse")
                description.set("Analytics for MCP servers. One import, one wrap.")
                url.set("https://getmcpulse.com")
                licenses {
                    license {
                        name.set("MIT")
                    }
                }
                // Maven Central rejects a release without these two. Developer
                // email is optional and deliberately left out — it is published
                // verbatim in the pom and scraped from there.
                developers {
                    developer {
                        id.set("mcpulse")
                        name.set("MCPulse")
                        url.set("https://github.com/getmcpulse")
                    }
                }
                scm {
                    url.set("https://github.com/getmcpulse/mcpulse-kotlin")
                    connection.set("scm:git:https://github.com/getmcpulse/mcpulse-kotlin.git")
                    developerConnection.set("scm:git:ssh://git@github.com/getmcpulse/mcpulse-kotlin.git")
                }
            }
        }
    }
}
