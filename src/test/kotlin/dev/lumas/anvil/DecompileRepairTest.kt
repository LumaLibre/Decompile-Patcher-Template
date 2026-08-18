package dev.lumas.anvil

import dev.lumas.anvil.internal.DecompileRepair
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.tools.ToolProvider
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.gradle.process.ExecOperations
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DecompileRepairTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var project: Project
    private lateinit var generatedDir: File
    private lateinit var fixtureJar: File

    internal abstract class Services @Inject constructor(val execOps: ExecOperations)

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(tempDir).build()
        generatedDir = tempDir.resolve("generated").apply { mkdirs() }
        fixtureJar = tempDir.resolve("fixture.jar")
        buildFixtureJar(fixtureJar)
    }

    private fun repair() = DecompileRepair(
        generatedDir = generatedDir,
        inputJar = fixtureJar,
        javap = File(System.getProperty("java.home"), "bin/javap").takeIf { it.isFile },
        execOps = project.objects.newInstance(Services::class.java).execOps,
        logger = project.logger,
    ).repair()

    private fun write(path: String, content: String): File =
        generatedDir.resolve(path).apply {
            parentFile.mkdirs()
            writeText(content.trimIndent())
        }

    @Test
    fun `a whole-class failure is reconstructed from bytecode into compilable java`() {
        val file = write(
            "dev/example/Greeter.java",
            """
            /*
            ${'$'}VF: Unable to decompile class
            Please report this to the Vineflower issue tracker, at https://github.com/Vineflower/vineflower/issues
            java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
              at java.base/java.util.ArrayList.get(ArrayList.java:428)
            */
            """
        )

        val report = repair()

        assertEquals(listOf("dev/example/Greeter.java"), report.stubbedClasses)
        val stub = file.readText()
        assertContains(stub, "package dev.example;")
        assertContains(stub, "class Greeter")
        assertContains(stub, "greet(")
        assertContains(stub, "UnsupportedOperationException")
        assertContains(stub, "java.lang.String greet(java.lang.String arg0)")
        assertCompiles(file)
    }

    @Test
    fun `an empty method body gets a throwing statement`() {
        val file = write(
            "dev/example/Broken.java",
            """
            package dev.example;

            public class Broken {
               public String value() {
                  // ${'$'}VF: Couldn't be decompiled
                  // java.lang.IndexOutOfBoundsException: Index 0 out of bounds for length 0
               }

               public int count() {
                  // ${'$'}VF: Couldn't be decompiled
               }
            }
            """
        )

        val report = repair()

        assertEquals(mapOf("dev/example/Broken.java" to 2), report.repairedMethods)
        assertContains(file.readText(), "throw new UnsupportedOperationException")
        // `missing return statement` is what this test exists to prevent.
        assertCompiles(file)
    }

    @Test
    fun `a body that still has real statements is left alone`() {
        val source = """
            package dev.example;

            public class Partial {
               public String value() {
                  // ${'$'}VF: Couldn't be decompiled
                  return "still here";
               }
            }
        """.trimIndent()
        val file = write("dev/example/Partial.java", source)

        val report = repair()

        assertTrue(report.repairedMethods.isEmpty(), "should not touch a body with statements")
        assertEquals(source, file.readText())
    }

    @Test
    fun `healthy output is untouched`() {
        val source = """
            package dev.example;

            public class Fine {
               public String value() {
                  return "ok";
               }
            }
        """.trimIndent()
        val file = write("dev/example/Fine.java", source)

        val report = repair()

        assertTrue(report.isEmpty, "nothing to repair")
        assertEquals(source, file.readText())
    }

    private fun assertCompiles(file: File) {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: error("tests must run on a JDK")
        val diagnostics = ByteArrayOutputStream()
        val out = tempDir.resolve("classes").apply { mkdirs() }
        val exit = compiler.run(
            null, null, diagnostics,
            "-nowarn", "-cp", fixtureJar.path, "-d", out.path, file.path,
        )
        assertEquals(0, exit, "repaired source did not compile:\n$diagnostics")
    }
}
