plugins {
    kotlin("plugin.serialization") version "2.3.0"
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    testImplementation(kotlin("test"))
}
