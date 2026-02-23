plugins {
    kotlin("plugin.serialization") version "2.3.0"
}

sourceSets {
    main {
        java { srcDir("src/main/java") }
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    implementation("io.netty:netty-all:4.2.0.Final")
    implementation("io.undertow:undertow-core:2.3.18.Final")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
}