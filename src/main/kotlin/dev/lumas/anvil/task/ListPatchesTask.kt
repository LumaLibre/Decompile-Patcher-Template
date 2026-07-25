package dev.lumas.anvil.task

import org.gradle.api.tasks.TaskAction

/** Lists the per-file patches currently in the patch tree, with insertion/deletion counts. */
abstract class ListPatchesTask : AnvilSourceTask() {

    @TaskAction
    fun list() {
        val layout = patchLayout()
        val patches = layout.allPatchFiles()
        if (patches.isEmpty()) {
            logger.lifecycle("ℹ️  No patches under ${layout.patchesDir.name}/")
            return
        }

        logger.lifecycle("")
        logger.lifecycle("📋 File patches (${patches.size})")
        logger.lifecycle("=".repeat(60))
        patches.forEach { patch ->
            val lines = patch.readLines()
            val add = lines.count { it.startsWith("+") && !it.startsWith("+++") }
            val del = lines.count { it.startsWith("-") && !it.startsWith("---") }
            logger.lifecycle("📄 ${layout.patchCanonical(patch)}  (+$add -$del)")
        }
        logger.lifecycle("=".repeat(60))
    }
}
