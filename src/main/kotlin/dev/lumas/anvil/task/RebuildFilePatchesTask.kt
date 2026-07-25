package dev.lumas.anvil.task

import dev.lumas.anvil.internal.GitOperationsService
import dev.lumas.anvil.internal.PatchRebuilder
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Regenerates per-file patches by diffing the module sources against the pristine base.
 * This is also how you finish a conflict: fix the files, then rebuild.
 */
abstract class RebuildFilePatchesTask : AnvilSourceTask() {

    @get:Internal
    abstract val gitOps: Property<GitOperationsService>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @TaskAction
    fun rebuild() {
        logger.lifecycle("")
        logger.lifecycle("🔁 Rebuilding file patches against pristine base...")
        logger.lifecycle("=".repeat(60))

        val summary = PatchRebuilder(
            git = gitOps.get(),
            layout = patchLayout(),
            workDir = workDir.get().asFile,
            logger = logger,
        ).run(dryRun = false)

        logger.lifecycle("")
        logger.lifecycle("=".repeat(60))
        logger.lifecycle(
            "✨ ${summary.created} created, ${summary.updated} updated, ${summary.removed} removed " +
                "(${summary.unchanged} unchanged)"
        )
    }
}
