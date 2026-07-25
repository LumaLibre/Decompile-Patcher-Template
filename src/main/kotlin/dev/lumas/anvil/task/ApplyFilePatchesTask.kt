package dev.lumas.anvil.task

import dev.lumas.anvil.internal.GitOperationsService
import dev.lumas.anvil.internal.PatchApplier
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

abstract class ApplyFilePatchesTask : AnvilSourceTask() {

    @get:Internal
    abstract val gitOps: Property<GitOperationsService>

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:Internal
    abstract val rejectsDir: DirectoryProperty

    @get:Internal
    abstract val failOnApplyError: Property<Boolean>

    @get:Internal
    @get:Option(
        option = "fuzzy",
        description = "Add a fuzzy `patch` matching rung before rejecting hunks.",
    )
    abstract val fuzzy: Property<Boolean>

    @TaskAction
    fun apply() {
        val summary = PatchApplier(
            git = gitOps.get(),
            layout = patchLayout(),
            workDir = workDir.get().asFile,
            rejectsDir = rejectsDir.get().asFile,
            logger = logger,
        ).apply(fuzzy.getOrElse(false))

        if (failOnApplyError.getOrElse(false) && summary.needsHelp.isNotEmpty()) {
            throw GradleException(
                "Anvil: ${summary.needsHelp.size} patch(es) did not apply cleanly. " +
                    "See the report above."
            )
        }
    }
}
