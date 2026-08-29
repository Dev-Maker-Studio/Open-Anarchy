plugins {
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    testImplementation(kotlin("test"))

    // The battle-tested legacy server library, for the differential codec tests only. Kept out of
    // the main source set on purpose: nothing in production may depend on it. Its POM pulls a
    // parallel server stack (netty, undertow, okhttp, mongo, argon2) in at runtime scope, none of
    // which the tests need, so the transitive graph is dropped entirely and the jar is used alone.
    testImplementation("rs.darkan:core:2.0.6") {
        isTransitive = false
    }
}

tasks.test {
    // Cache paths (CACHE_PATH, default "../cache/") and the .env file are resolved relative to the
    // repository root, not the module directory.
    workingDir = rootDir
    maxHeapSize = "4g"
    testLogging {
        showStandardStreams = true
    }
}
