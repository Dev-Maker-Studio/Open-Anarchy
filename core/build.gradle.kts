import org.darkan.build.gameval.GenerateGamevalConstants

plugins {
    kotlin("plugin.serialization")
    idea
    `maven-publish`
}

java {
    withSourcesJar()
}

dependencies {
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.google.guava:guava:33.7.1-jre")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation(kotlin("test"))

    testImplementation("rs.darkan:core:2.0.6") {
        isTransitive = false
    }
}

val cacheSourceDir: File = (findProperty("darkan.cacheSource") as String?
    ?: System.getenv("DARKAN_CACHE_SOURCE"))
    ?.let { rootProject.file(it) }
    ?: rootProject.file("cache-source")
val generateGamevalConstants = tasks.register<GenerateGamevalConstants>("generateGamevalConstants") {
    group = "generation"
    description = "Generates org.darkan.core.gameval.gen.* from the cache source tree's gamevals/*.json"
    catalogDirectory.set(File(cacheSourceDir, "gamevals"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sources/gameval/java"))
    generatorLabel.set("the :core:generateGamevalConstants Gradle task (buildSrc org.darkan.build.gameval)")
    regenerateCommand.set("./gradlew :core:generateGamevalConstants")
}

sourceSets.main {
    java.srcDir(generateGamevalConstants.flatMap { it.outputDirectory })
}

idea.module {
    generatedSourceDirs.add(layout.buildDirectory.dir("generated/sources/gameval/java").get().asFile)
}

publishing {
    val ciProjectId: String? = System.getenv("CI_PROJECT_ID")
    val ciJobToken: String? = System.getenv("CI_JOB_TOKEN")
    val ciPipelineId: String? = System.getenv("CI_PIPELINE_ID")
    val ciBranch: String? = System.getenv("CI_COMMIT_REF_SLUG")
    val ciTag: String? = System.getenv("CI_COMMIT_TAG")

    publications.create<MavenPublication>("library") {
        version = ciTag ?: if (ciBranch != null && ciPipelineId != null) "${project.version}-${ciBranch}-${ciPipelineId}" else project.version.toString()
        artifactId = "core"
        from(components["java"])
    }

    if (ciProjectId == null || ciJobToken == null) return@publishing
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
