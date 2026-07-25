package dev.lumas.anvil.task

import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.options.Option

abstract class CleanDistributedSourcesTask : AnvilSourceTask() {

    @get:Internal
    @get:Option(
        option = "force",
        description = "Discard module edits that have not been saved to the patch tree.",
    )
    abstract val force: Property<Boolean>

    @TaskAction
    fun clean() {
        val layout = patchLayout()
        logger.lifecycle("Cleaning distributed sources from modules...")

        packageMappings.get().forEach { (packagePath, moduleDir) ->
            val targetSrcDir = layout.moduleSrcDir(moduleDir.asFile, packagePath)
            if (targetSrcDir.exists()) {
                targetSrcDir.deleteRecursively()
                logger.lifecycle("🗑 Cleaned ${targetSrcDir.path}")
            }
        }

        resourceMappings.get().entries
            .groupBy({ it.value }, { it.key })
            .forEach { (moduleDir, resourceNames) ->
                val resourcesDir = layout.moduleResourcesDir(moduleDir.asFile)
                resourceNames.forEach { resourceName ->
                    val target = resourcesDir.resolve(resourceName)
                    if (target.exists()) {
                        if (target.isDirectory) target.deleteRecursively() else target.delete()
                        logger.lifecycle("🗑 Cleaned ${target.path}")
                    }
                }
            }

        logger.lifecycle("✓ Clean complete!")
    }
}
