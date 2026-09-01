import org.darkan.build.gameval.GenerateGamevalConstants

plugins {
    kotlin("plugin.serialization") version "2.3.0"
    idea
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
// at runtime; see docs/gameval.md. Only the catalogs are shipped; CONTRACT.md stays behind. The
// directory may be absent or mid-edit; the copy just takes what is there.
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

// Typed gameval constants (org.darkan.core.gameval.gen.*): one Java class of public static final
// int per gamevals/<type>.json, generated into build/generated/sources/gameval/java by the
// buildSrc task and compiled as part of this module. Nothing is committed and no python is
// involved; see docs/gameval.md, "Typed constants".
val generateGamevalConstants = tasks.register<GenerateGamevalConstants>("generateGamevalConstants") {
    group = "generation"
    description = "Generates org.darkan.core.gameval.gen.* from gamevals/*.json"
    catalogDirectory.set(rootProject.layout.projectDirectory.dir("gamevals"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/gameval/java"))
    generatorLabel.set("the :core:generateGamevalConstants Gradle task (buildSrc org.darkan.build.gameval)")
    regenerateCommand.set("./gradlew :core:generateGamevalConstants")
}

sourceSets.main {
    java.srcDir(generateGamevalConstants.flatMap { it.outputDirectory })
}

// So a Gradle-imported IDE indexes the constants (and marks them generated rather than hand-written).
idea.module {
    generatedSourceDirs.add(layout.buildDirectory.dir("generated/sources/gameval/java").get().asFile)
}
