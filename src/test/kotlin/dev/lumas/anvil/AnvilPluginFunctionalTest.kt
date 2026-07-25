package dev.lumas.anvil

import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AnvilPluginFunctionalTest {

    @TempDir
    lateinit var projectDir: File

    private val greeter: File
        get() = projectDir.resolve("core/src/main/java/dev/example/Greeter.java")

    private val greeterPatch: File
        get() = projectDir.resolve("patches/src/main/java/dev/example/Greeter.java.patch")

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
    fun `setup decompiles and distributes sources into the module`() {
        val result = run("setup")

        assertEquals(TaskOutcome.SUCCESS, result.task(":decompile")?.outcome)
        assertTrue(greeter.isFile, "expected decompiled Greeter.java in :core")
        assertContains(greeter.readText(), "class Greeter")
        assertContains(result.output, "Setup complete")

        val pluginYml = projectDir.resolve("core/src/main/resources/plugin.yml")
        assertTrue(pluginYml.isFile, "expected the mapped resource in :core")
        assertEquals(FIXTURE_PLUGIN_YML, pluginYml.readText())
    }

    @Test
    fun `an edit round-trips through rebuild, reset and re-apply`() {
        run("setup")
        assertFalse(greeterPatch.exists(), "a clean setup should produce no patches")

        // Make a change the way a user would: edit the module source directly.
        greeter.writeText(greeter.readText().replace("\", \"", "\" -- \""))

        val status = run("patchStatus")
        assertContains(status.output, "new patch")
        assertContains(status.output, "src/main/java/dev/example/Greeter.java")

        run("rebuildFilePatches")
        assertTrue(greeterPatch.isFile, "expected a patch for the edited file")
        assertContains(greeterPatch.readText(), "-- ")

        // reset throws the module tree away and rebuilds it from base + patches.
        val reset = run("reset")
        assertContains(reset.output, "clean: 1")
        assertContains(greeter.readText(), "\" -- \"")
    }

    @Test
    fun `reverting an edit prunes its patch`() {
        run("setup")
        val pristine = greeter.readText()

        greeter.writeText(pristine.replace("\", \"", "\" -- \""))
        run("rebuildFilePatches")
        assertTrue(greeterPatch.isFile)

        greeter.writeText(pristine)
        val result = run("rebuildFilePatches")

        assertContains(result.output, "removed")
        assertFalse(greeterPatch.exists(), "a file identical to base should have no patch")
    }

    @Test
    fun `patchStatus reports an in-sync tree without writing anything`() {
        run("setup")
        greeter.writeText(greeter.readText().replace("\", \"", "\" -- \""))
        run("rebuildFilePatches")

        val patchBefore = greeterPatch.readBytes()
        val result = run("patchStatus")

        assertContains(result.output, "Patch tree is in sync")
        assertTrue(patchBefore.contentEquals(greeterPatch.readBytes()), "patchStatus must not write")
    }

    @Test
    fun `a missing input jar fails with an actionable message`() {
        projectDir.resolve("sources/Fixture.jar").delete()

        val result = runAndFail("decompile")

        assertContains(result.output, "input jar not found")
    }

    @Test
    fun `the workflow is configuration cache compatible`() {
        run("setup", "--configuration-cache")
        val result = run("patchStatus", "--configuration-cache")

        assertContains(result.output, "Configuration cache entry")
        assertFalse(result.output.contains("problems were found"), result.output)
    }

    private fun runner(vararg args: String): GradleRunner = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
        .withArguments(*args, "--stacktrace")
        .forwardOutput()

    private fun run(vararg args: String): BuildResult = runner(*args).build()

    private fun runAndFail(vararg args: String): BuildResult = runner(*args).buildAndFail()
}
