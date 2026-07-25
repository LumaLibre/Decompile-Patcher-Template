package dev.lumas.anvil

import java.io.File
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

internal const val GREETER_SOURCE = """
package dev.example;

public class Greeter {
    private final String prefix;

    public Greeter(String prefix) {
        this.prefix = prefix;
    }

    public String greet(String name) {
        return this.prefix + ", " + name + "!";
    }
}
"""

internal const val FIXTURE_PLUGIN_YML = "name: Fixture\nversion: 1.0.0\nmain: dev.example.Greeter\n"

/**
 * Compiles a tiny jar for the tests to decompile. Targeting release 17 keeps the class file
 * version inside what Vineflower understands, regardless of the JDK running the tests.
 */
internal fun buildFixtureJar(target: File) {
    val work = Files.createTempDirectory("anvil-fixture").toFile()
    val sourceFile = File(work, "src/dev/example/Greeter.java").apply {
        parentFile.mkdirs()
        writeText(GREETER_SOURCE.trimStart())
    }
    val classesDir = File(work, "classes").apply { mkdirs() }

    val compiler = ToolProvider.getSystemJavaCompiler()
        ?: error("No JDK compiler available; tests must run on a JDK, not a JRE.")
    val exit = compiler.run(null, null, null, "--release", "17", "-d", classesDir.path, sourceFile.path)
    check(exit == 0) { "Failed to compile the fixture source" }

    target.parentFile.mkdirs()
    JarOutputStream(target.outputStream().buffered()).use { jar ->
        classesDir.walkTopDown().filter { it.isFile }.forEach { classFile ->
            jar.putNextEntry(JarEntry(classFile.relativeTo(classesDir).path.replace('\\', '/')))
            jar.write(classFile.readBytes())
            jar.closeEntry()
        }
        jar.putNextEntry(JarEntry("plugin.yml"))
        jar.write(FIXTURE_PLUGIN_YML.toByteArray())
        jar.closeEntry()
    }

    work.deleteRecursively()
}
