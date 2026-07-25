import java.net.HttpURLConnection
import java.net.URI
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "dev.lumas.anvil"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

gradlePlugin {
    plugins.create("anvil") {
        id = "dev.lumas.anvil"
        implementationClass = "dev.lumas.anvil.AnvilPlugin"
        displayName = "Anvil"
        description = "Per-file patches for JVM jars, with a decompile-and-patch workflow."
    }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = providers.gradleProperty("anvil.testOutput").isPresent
    }
}

val repoUsername = providers.environmentVariable("REPO_USERNAME")
    .orElse(providers.gradleProperty("repoUsername"))
val repoPassword = providers.environmentVariable("REPO_PASSWORD")
    .orElse(providers.gradleProperty("repoPassword"))

val releasesUrl = "https://repo.jsinco.dev/releases"

publishing {
    repositories {
        maven {
            name = "jsinco"
            url = uri(releasesUrl)
            credentials {
                username = repoUsername.orNull
                password = repoPassword.orNull
            }
        }
    }

    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "Anvil"
            description = "Per-file patches for JVM jars, with a decompile-and-patch workflow."
            url = "https://github.com/LumaLibre/Anvil"
            licenses {
                license {
                    name = "MIT License"
                    url = "https://github.com/LumaLibre/Anvil/blob/master/LICENSE"
                }
            }
            developers {
                developer {
                    id = "Jsinco"
                    name = "Jsinco"
                    organization = "LumaLibre"
                    organizationUrl = "https://github.com/LumaLibre"
                }
            }
            scm {
                url = "https://github.com/LumaLibre/Anvil"
                connection = "scm:git:https://github.com/LumaLibre/Anvil.git"
                developerConnection = "scm:git:ssh://git@github.com/LumaLibre/Anvil.git"
            }
        }
    }
}

tasks.register("printVersion") {
    description = "Prints the project version."
    doLast { logger.quiet(project.version.toString()) }
}

val checkPublishable = tasks.register("checkPublishable") {
    description = "Verifies the version and credentials are fit to publish."
    doLast {
        if (version.toString().endsWith("SNAPSHOT")) {
            error("repo.jsinco.dev/releases does not take SNAPSHOT versions. Set a fixed version first.")
        }
        val missing = buildList {
            if (!repoUsername.isPresent) add("REPO_USERNAME (or repoUsername)")
            if (!repoPassword.isPresent) add("REPO_PASSWORD (or repoPassword)")
        }
        if (missing.isNotEmpty()) {
            error(
                "Missing publish credentials: ${missing.joinToString(", ")}. Set the environment " +
                    "variables, or put repoUsername/repoPassword in ~/.gradle/gradle.properties."
            )
        }
        if (!allowOverwrite.get() && alreadyPublished(existingReleaseUrl)) {
            error(
                "Version $version is already published at $existingReleaseUrl.\n" +
                    "Bump the version and push a matching ver/<version> branch, or pass " +
                    "-PallowOverwrite to republish."
            )
        }
    }
}

// A version branch can be pushed repeatedly, unlike a one-shot tag, so a re-push would otherwise
// retry a version that is already out. Computed eagerly: `group`/`version` are fixed above.
val existingReleaseUrl =
    "$releasesUrl/${group.toString().replace('.', '/')}/$name/$version/$name-$version.pom"
val allowOverwrite = providers.gradleProperty("allowOverwrite").map { true }.orElse(false)

/** True only on a definite hit. An unreachable repository must not block a release. */
fun Task.alreadyPublished(url: String): Boolean = try {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.requestMethod = "HEAD"
    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    val code = connection.responseCode
    connection.disconnect()
    code == 200
} catch (e: Exception) {
    logger.lifecycle("Could not check $releasesUrl for an existing release (${e.message}); continuing.")
    false
}

tasks.withType<PublishToMavenRepository>().configureEach {
    if (name.endsWith("ToJsincoRepository")) {
        dependsOn(checkPublishable, tasks.test, tasks.named("validatePlugins"))
    }
}

tasks.register("publishRelease") {
    group = "publishing"
    description = "Publishes the plugin and its marker to repo.jsinco.dev/releases."
    dependsOn(tasks.named("publishAllPublicationsToJsincoRepository"))
}
