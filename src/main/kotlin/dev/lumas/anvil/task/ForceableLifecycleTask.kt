package dev.lumas.anvil.task

import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

/**
 * A lifecycle task that carries the `--force` flag for [CheckUnsavedWorkTask].
 */
abstract class ForceableLifecycleTask : DefaultTask() {

    @get:Internal
    @get:Option(
        option = "force",
        description = "Discard module edits that have not been saved to the patch tree.",
    )
    abstract val force: Property<Boolean>

    @get:Internal
    abstract val completionMessage: Property<String>

    @TaskAction
    fun done() {
        completionMessage.orNull?.let { logger.lifecycle(it) }
    }
}
