plugins {
    application
}

application {
    mainClass.set("org.darkan.migration.MainKt")
    tasks.run.get().workingDir = rootDir
}

dependencies {
    implementation(project(":core"))
    implementation(project(":world"))
    implementation(project(":lobby"))
    implementation("com.google.code.gson:gson:2.14.0")
}

dependencies {
    testImplementation(kotlin("test"))
}
