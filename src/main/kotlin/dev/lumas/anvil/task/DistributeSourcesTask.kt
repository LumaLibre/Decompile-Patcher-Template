package dev.lumas.anvil.task

import org.gradle.api.tasks.TaskAction

/** Copies the pristine decompiled tree into the module source sets, package by package. */
abstract class DistributeSourcesTask : AnvilSourceTask() {

    @TaskAction
    fun distribute() {
        val layout = patchLayout()
        val generated = layout.generatedDir
        if (!generated.exists()) {
            logger.lifecycle("No generated sources found. Run 'decompile' first.")
            return
        }

        logger.lifecycle("Distributing decompiled sources to modules...")

        packageMappings.get().forEach { (packagePath, moduleDir) ->
            val sourcePackageDir = generated.resolve(packagePath)
            if (!sourcePackageDir.exists()) {
                logger.warn("⚠ Package directory not found: $packagePath")
                return@forEach
            }
            val targetSrcDir = layout.moduleSrcDir(moduleDir.asFile, packagePath)
            logger.lifecycle("📦 Copying $packagePath -> ${moduleDir.asFile.name}")
            targetSrcDir.mkdirs()

            var fileCount = 0
            sourcePackageDir.walkTopDown().forEach { sourceFile ->
                if (sourceFile.isFile && sourceFile.extension == "java") {
                    val targetFile = targetSrcDir.resolve(sourceFile.relativeTo(sourcePackageDir))
                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    fileCount++
                }
            }
            logger.lifecycle("✓ Copied $fileCount files")
        }

        val resources = resourceMappings.get()
        if (resources.isNotEmpty()) {
            resources.entries.groupBy({ it.value }, { it.key }).forEach { (moduleDir, resourceNames) ->
                logger.lifecycle("")
                logger.lifecycle("📦 Copying resources to ${moduleDir.asFile.name}...")
                val resourcesDir = layout.moduleResourcesDir(moduleDir.asFile)
                resourcesDir.mkdirs()

                var resourceCount = 0
                resourceNames.forEach { resourceName ->
                    val sourceResource = generated.resolve(resourceName)
                    if (!sourceResource.exists()) {
                        logger.warn("  ⚠ Resource not found: $resourceName")
                        return@forEach
                    }
                    val targetResource = resourcesDir.resolve(resourceName)
                    if (sourceResource.isDirectory) {
                        sourceResource.copyRecursively(targetResource, overwrite = true)
                        val count = sourceResource.walkTopDown().count { it.isFile }
                        logger.lifecycle("  ✓ Copied directory: $resourceName ($count files)")
                        resourceCount += count
                    } else {
                        targetResource.parentFile.mkdirs()
                        sourceResource.copyTo(targetResource, overwrite = true)
                        logger.lifecycle("  ✓ Copied file: $resourceName")
                        resourceCount++
                    }
                }
                logger.lifecycle("✓ Copied $resourceCount resource files")
            }
        }

        logger.lifecycle("")
        logger.lifecycle("✓ Source distribution complete!")
    }
}
