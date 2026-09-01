plugins {
    `kotlin-dsl`
}

dependencies {
    // groovy.json.JsonSlurper, from the Gradle distribution: the catalog reader needs no external
    // dependency and no network beyond what a Gradle build already has.
    implementation(localGroovy())

    // JUnit 4 rather than kotlin-test: kotlin-test would drag a second Kotlin stdlib in beside the
    // one the `kotlin-dsl` plugin pins.
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The generator's own tests gate every build that uses it: a build cannot generate constants from a
// generator whose identifier rules or catalog validation are broken. `finalizedBy` rather than
// `dependsOn` because the test compile classpath is the jar itself; the tests still run inside the
// buildSrc build, before the main build's tasks, and a failure fails the whole build. They are up to
// date (and so cost nothing) unless buildSrc changed.
tasks.jar {
    finalizedBy(tasks.test)
}
