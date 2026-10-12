plugins {
    application
    `maven-publish`
    id("com.gradleup.shadow") version "9.6.1"
    kotlin("plugin.serialization")
}

application {
    mainClass.set("org.darkan.lobby.MainKt")
    tasks.run.get().workingDir = rootProject.projectDir
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":core"))
    implementation(project(":cs2"))
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "org.darkan.lobby.MainKt"
    }
}

tasks.shadowJar {
    isZip64 = true
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    filesNotMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    archiveBaseName.set("lobby-server")
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
        artifactId = "lobby-server"
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