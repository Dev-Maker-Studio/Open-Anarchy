import org.darkan.build.gameval.GenerateGamevalConstants

plugins {
    kotlin("plugin.serialization") version "2.3.0"
    idea
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    // Two reference tables in the 727 cache (255/2 and 255/9) were bzip2ed by commons-compress
    // rather than libbzip2, so reproducing them byte for byte needs its encoder as well as ours.
    implementation("org.apache.commons:commons-compress:1.27.1")
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

// Typed gameval constants (org.darkan.core.gameval.gen.*): one Java class of public static final
// int per <cache source tree>/gamevals/<type>.json, generated into build/generated/sources/gameval/java
// by the buildSrc task and compiled as part of this module. Nothing is committed and no python is
// involved; see docs/gameval.md, "Typed constants". The catalogs are the only copy of the names and
// live in the cache source tree (docs/cache-source.md): the cache-source submodule by default, or the
// directory named by -Pdarkan.cacheSource=<path> / the DARKAN_CACHE_SOURCE environment variable.
val cacheSourceDir: File = (findProperty("darkan.cacheSource") as String?
    ?: System.getenv("DARKAN_CACHE_SOURCE"))
    ?.let { rootProject.file(it) }
    ?: rootProject.file("cache-source")
val generateGamevalConstants = tasks.register<GenerateGamevalConstants>("generateGamevalConstants") {
    group = "generation"
    description = "Generates org.darkan.core.gameval.gen.* from the cache source tree's gamevals/*.json"
    catalogDirectory.set(File(cacheSourceDir, "gamevals"))
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
