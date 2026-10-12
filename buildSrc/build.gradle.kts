plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(localGroovy())

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
