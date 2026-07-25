package dev.lumas.anvil

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Covers the command surface that replaced `patcher.sh`: aliases, `fresh`, and the guard. */
class AnvilCommandsFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val greeter: File
        get() = projectDir.resolve("core/src/main/java/dev/example/Greeter.java")

    private val generatedDir: File
        get() = projectDir.resolve("sources/generated")

    @BeforeEach
    fun setUpFixtureProject() {
        buildFixtureJar(projectDir.resolve("sources/Fixture.jar"))
        projectDir.resolve("settings.gradle.kts").writeText(
            """
            rootProject.name = "fixture"
            include(":core")
            """.trimIndent()
        )
        projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("dev.lumas.anvil")
            }

            anvil {
                inputJar = layout.projectDirectory.file("sources/Fixture.jar")
                sourcePackage("dev/example", ":core")
                resource("plugin.yml", ":core")
            }
            """.trimIndent()
        )
        projectDir.resolve("core").mkdirs()
        projectDir.resolve("core/build.gradle.kts").writeText("")
    }

    @Test
    fun `short aliases delegate to the real tasks`() {
        run("setup")

        assertContains(run("status").output, "Patch tree is in sync")
        assertContains(run("list").output, "No patches under")
        assertContains(run("inspect").output, "Decompiled package structure")
        assertContains(run("rebuild").output, "0 created")
        assertContains(run("applyOnly").output, "nothing to apply")
    }

    @Test
    fun `anvilHelp prints the workflow guide`() {
        val result = run("anvilHelp")

        // Anchored on the section structure, not the title copy, so rewording the header text
        // does not break the build.
        assertContains(result.output, "Getting started")
        assertContains(result.output, "Conflict workflow")
        assertContains(result.output, "./gradlew rebuild")
    }

    @Test
    fun `fresh wipes the base and the module sources, then rebuilds`() {
        run("setup")
        edit()
        run("rebuild")

        // Leave a stray file behind; a real wipe should not carry it over.
        val stray = projectDir.resolve("core/src/main/java/dev/example/Stray.java")
        stray.writeText("package dev.example; class Stray {}")

        val result = run("fresh", "--force")

        assertContains(result.output, "Fresh start complete")
        assertFalse(stray.exists(), "fresh should have wiped the module sources")
        assertTrue(generatedDir.isDirectory, "fresh should have re-decompiled the base")
        assertContains(greeter.readText(), "\" -- \"", message = "patches should be re-applied")
    }

    @Test
    fun `destructive tasks refuse to discard edits that are not saved as patches`() {
        run("setup")
        edit()

        val result = runAndFail("reset")

        assertContains(result.output, "not saved as patches")
        assertContains(result.output, "./gradlew rebuild")
        assertContains(greeter.readText(), "\" -- \"", message = "the edit must survive a refused reset")
    }

    @Test
    fun `--force discards unsaved edits`() {
        run("setup")
        val pristine = greeter.readText()
        edit()

        val result = run("reset", "--force")

        assertContains(result.output, "--force")
        assertContains(greeter.readText(), pristine.substringAfter("class Greeter"))
    }

    @Test
    fun `-P anvil force works where the flag cannot be typed`() {
        run("setup")
        edit()

        run("cleanDistributedSources", "-Panvil.force")

        assertFalse(greeter.exists(), "the module sources should have been cleaned")
    }

    @Test
    fun `the guard allows destructive tasks once edits are saved`() {
        run("setup")
        edit()
        run("rebuild")

        val result = run("reset")

        assertContains(result.output, "Sources reset")
        assertContains(greeter.readText(), "\" -- \"")
    }

    /** The same edit everywhere: change the separator inside `greet`. */
    private fun edit() {
        greeter.writeText(greeter.readText().replace("\", \"", "\" -- \""))
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()
}
