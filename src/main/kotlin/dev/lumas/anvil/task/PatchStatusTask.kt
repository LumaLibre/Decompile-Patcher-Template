package dev.lumas.anvil.task

import dev.lumas.anvil.internal.GitOperationsService
import dev.lumas.anvil.internal.PatchRebuilder
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/** Dry run of [RebuildFilePatchesTask]: shows what the patch tree is missing, without writing. */
abstract class PatchStatusTask : AnvilSourceTask() {

    @get:Internal
    abstract val gitOps: Property<GitOperationsService>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @TaskAction
    fun status() {
        logger.lifecycle("")
        logger.lifecycle("📊 Patch status (patch tree vs module sources)")
        logger.lifecycle("=".repeat(60))

        val summary = PatchRebuilder(
            git = gitOps.get(),
            layout = patchLayout(),
            workDir = workDir.get().asFile,
            logger = logger,
        ).run(dryRun = true)

        logger.lifecycle("")
        logger.lifecycle("=".repeat(60))
        logger.lifecycle(
            "Summary: ${summary.created} new, ${summary.updated} updated, ${summary.removed} stale " +
                "(${summary.unchanged} already in sync)"
        )
        logger.lifecycle("")
        if (summary.hasWork) {
            logger.lifecycle("💡 Run './gradlew rebuildFilePatches' to sync the patch tree.")
        } else {
            logger.lifecycle("ℹ️  Patch tree is in sync — rebuildFilePatches would make no changes.")
        }
    }
}
