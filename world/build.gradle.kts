plugins {
    application
    kotlin("plugin.serialization") version "2.3.0"
}

application {
    mainClass.set("org.darkan.world.MainKt")
    tasks.run.get().workingDir = rootDir
}

sourceSets {
    main {
        java { srcDir("src/main/java") }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.google.code.gson:gson:2.12.1")
    implementation("com.google.guava:guava:33.4.0-jre")
    implementation("it.unimi.dsi:fastutil:8.5.15")
    implementation("io.undertow:undertow-core:2.3.18.Final")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "org.darkan.world.MainKt"
    }
}