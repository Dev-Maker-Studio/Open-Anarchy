plugins {
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    testImplementation(kotlin("test"))
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
