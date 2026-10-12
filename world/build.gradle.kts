plugins {
    application
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
    kotlin("plugin.serialization")
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
    implementation(project(":cs2"))
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.guava:guava:33.7.1-jre")
    implementation("it.unimi.dsi:fastutil:8.5.19")

    testImplementation(kotlin("test"))
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "org.darkan.world.MainKt"
    }
}

tasks.shadowJar {
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    archiveBaseName.set("world-server")
    archiveClassifier.set("")
}

publishing {
    val ciProjectId: String? = System.getenv("CI_PROJECT_ID")
    val ciJobToken: String? = System.getenv("CI_JOB_TOKEN")
    val ciPipelineId: String? = System.getenv("CI_PIPELINE_ID")
    val ciBranch: String? = System.getenv("CI_COMMIT_REF_SLUG")
    val ciTag: String? = System.getenv("CI_COMMIT_TAG")
    if (ciProjectId == null || ciJobToken == null || ciPipelineId == null || ciBranch == null) {
        println("Failed to get project id, job token, pipeline id or branch.")
        return@publishing
    }

    publications.create<MavenPublication>("library") {
        version = ciTag ?: "${project.version}-${ciBranch}-${ciPipelineId}"
        artifactId = "world-server"
        artifact(tasks.shadowJar)
    }

    repositories {
        maven {
            name = "GitLab"
            url = uri("https://gitlab.com/api/v4/projects/${ciProjectId}/packages/maven")
            credentials(HttpHeaderCredentials::class) {
                name = "Job-Token"
                value = ciJobToken
            }
            authentication {
                create<HttpHeaderAuthentication>("header")
            }
        }
    }
}
