plugins {
    application
}

application {
    mainClass.set("org.darkan.migration.MainKt")
    tasks.run.get().workingDir = rootDir
}

dependencies {
    implementation(project(":core"))
    // Player saves are decoded through the world's own Gson configuration so the verifier can
    // never disagree with what the server would do at login.
    implementation(project(":world"))
    implementation("com.google.code.gson:gson:2.12.1")
}

dependencies {
    testImplementation(kotlin("test"))
}
