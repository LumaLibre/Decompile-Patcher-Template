package dev.lumas.anvil.task

import dev.lumas.anvil.internal.GitOperationsService
import dev.lumas.anvil.internal.PatchRebuilder
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

/**
 * Guards destructive tasks.
 */
abstract class CheckUnsavedWorkTask : AnvilSourceTask() {

    @get:Internal
    abstract val gitOps: Property<GitOperationsService>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Internal
    abstract val force: Property<Boolean>

    @TaskAction
    fun check() {
        if (force.getOrElse(false)) {
            logger.lifecycle("⚠️  --force: discarding module edits without checking.")
            return
        }

        val layout = patchLayout()
        // No base means nothing has been distributed yet, so there is nothing to lose.
        if (!layout.generatedDir.exists()) return

        val summary = PatchRebuilder(
            git = gitOps.get(),
            layout = layout,
            workDir = workDir.get().asFile,
            logger = logger,
        ).run(dryRun = true)

        if (!summary.hasWork) return

        val changes = summary.created + summary.updated + summary.removed
        throw GradleException(
            buildString {
                appendLine("Anvil: $changes change(s) in the module sources are not saved as patches (listed above).")
                appendLine("This task would discard them.")
                appendLine()
                appendLine("  ./gradlew rebuild          save them to the patch tree first")
                append("  ./gradlew <task> --force   discard them anyway")
            }
        )
    }
}
