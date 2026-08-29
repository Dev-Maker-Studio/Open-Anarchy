plugins {
    application
    `maven-publish`
    id("com.gradleup.shadow") version "9.3.0"
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
}

tasks.withType<Jar> {
    manifest {
        attributes["Main-Class"] = "org.darkan.world.MainKt"
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    isZip64 = true
    mergeServiceFiles()
    archiveBaseName.set("world-server")
}

publishing {
    val ciProjectId: String? = System.getenv("CI_PROJECT_ID")
    val ciJobToken: String? = System.getenv("CI_JOB_TOKEN")
    val ciPipelineId: String? = System.getenv("CI_PIPELINE_ID")
    if (ciProjectId == null || ciJobToken == null || ciPipelineId == null) {
        println("Failed to get project id, job token or pipeline id.")
        return@publishing
    }

    publications.create<MavenPublication>("library") {
        version = "${project.version}-${ciPipelineId}"
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
