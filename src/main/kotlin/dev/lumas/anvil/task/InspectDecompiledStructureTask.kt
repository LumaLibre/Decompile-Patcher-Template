package dev.lumas.anvil.task

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Prints the decompiled package tree and root-level resources — the input you need to fill in
 * `sourcePackage(...)` and `resource(...)` calls for a jar you have not mapped yet.
 */
abstract class InspectDecompiledStructureTask : DefaultTask() {

    @get:Internal
    abstract val generatedDir: DirectoryProperty

    @TaskAction
    fun inspect() {
        val generated = generatedDir.get().asFile
        if (!generated.exists()) {
            logger.lifecycle("❌ No generated sources found. Run 'decompile' first.")
            return
        }

        logger.lifecycle("")
        logger.lifecycle("📂 Decompiled package structure")
        logger.lifecycle("=".repeat(60))
        generated.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { dir ->
            printTree(generated, dir, "", 0)
        }

        val resources = generated.listFiles()?.filter { !it.isDirectory }?.sorted().orEmpty()
        val resourceDirs = generated.listFiles()
            ?.filter { it.isDirectory && it.walkTopDown().none { f -> f.extension == "java" } }
            ?.sorted()
            .orEmpty()
        if (resources.isNotEmpty() || resourceDirs.isNotEmpty()) {
            logger.lifecycle("")
            logger.lifecycle("📋 Resources at root:")
            resourceDirs.forEach {
                logger.lifecycle("  📁 ${it.name}/ (${it.walkTopDown().count { f -> f.isFile }} files)")
            }
            resources.forEach { logger.lifecycle("  📄 ${it.name}") }
        }

        logger.lifecycle("")
        logger.lifecycle("=".repeat(60))
        logger.lifecycle("💡 Use this to write your sourcePackage(...) / resource(...) mappings.")
    }

    private fun printTree(root: File, file: File, prefix: String, depth: Int) {
        if (depth > 4) return
        val javaCount = file.walkTopDown().count { it.isFile && it.extension == "java" }
        if (javaCount == 0) return
        logger.lifecycle("$prefix📦 ${file.relativeTo(root)} ($javaCount files)")
        file.listFiles()?.filter { it.isDirectory }?.sorted()?.forEach { child ->
            printTree(root, child, "$prefix   ", depth + 1)
        }
    }
}
