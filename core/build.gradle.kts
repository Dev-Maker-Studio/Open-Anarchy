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

// Bundle the gameval name catalogs (gamevals/<type>.json at the repo root) under /gamevals on the
// classpath so a jar with no working tree can still resolve names. GAMEVAL_PATH overrides per file
// at runtime; see docs/gameval.md. Only the catalogs are shipped: CONTRACT.md, crosswalk/ and
// work/ stay behind. The directory may be absent or mid-regeneration; the copy just takes what is there.
tasks.processResources {
    from(rootProject.file("gamevals")) {
        into("gamevals")
        include("*.json")
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

// Typed gameval constants (org.darkan.core.gameval.gen.*) are generated from gamevals/<type>.json
// by gamevals/gen_kotlin.py and committed, so a build never needs python. Run this (or the script
// directly) after the catalogs change; `-Pcheck` only reports whether they are current.
tasks.register<Exec>("generateGamevals") {
    group = "generation"
    description = "Regenerates core/src/main/java/org/darkan/core/gameval/gen from gamevals/*.json"
    workingDir = rootDir
    val args = mutableListOf("python3", "gamevals/gen_kotlin.py")
    if (project.hasProperty("check")) args += "--check"
    commandLine(args)
}
