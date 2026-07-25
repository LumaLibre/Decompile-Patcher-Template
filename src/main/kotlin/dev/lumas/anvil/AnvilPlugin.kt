package dev.lumas.anvil

import dev.lumas.anvil.internal.GitOperationsService
import dev.lumas.anvil.task.AnvilHelpTask
import dev.lumas.anvil.task.AnvilSourceTask
import dev.lumas.anvil.task.ApplyFilePatchesTask
import dev.lumas.anvil.task.CheckUnsavedWorkTask
import dev.lumas.anvil.task.CleanDistributedSourcesTask
import dev.lumas.anvil.task.DecompileTask
import dev.lumas.anvil.task.DistributeSourcesTask
import dev.lumas.anvil.task.ForceableLifecycleTask
import dev.lumas.anvil.task.InspectDecompiledStructureTask
import dev.lumas.anvil.task.ListPatchesTask
import dev.lumas.anvil.task.PatchStatusTask
import dev.lumas.anvil.task.RebuildFilePatchesTask
import java.net.URI
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.maven
import org.gradle.kotlin.dsl.register

/**
 * Applies the Anvil decompile-and-patch workflow.
 */
class AnvilPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        require(project === project.rootProject) {
            "Anvil must be applied to the root project. Modules are configured through the " +
                "anvil { } block, e.g. sourcePackage(\"dev/foo\", \":core\")."
        }

        val ext = project.extensions.create<AnvilExtension>(EXTENSION_NAME, project)
        applyConventions(project, ext)

        val gitService = project.gradle.sharedServices
            .registerIfAbsent(GIT_SERVICE_NAME, GitOperationsService::class.java) {}
        val decompilerConfiguration = registerDecompilerConfiguration(project, ext)

        val patchWork = project.layout.buildDirectory.dir("anvil/patch-work")
        val statusWork = project.layout.buildDirectory.dir("anvil/patch-status")
        val guardWork = project.layout.buildDirectory.dir("anvil/guard")
        val rejects = project.layout.projectDirectory.dir(REJECTS_DIR)

        // `-Panvil.fuzzy` / `-Panvil.force` reach the tasks that lifecycle wrappers hide from the
        // command line, and give CI a way to set them without knowing the task graph.
        val fuzzyProperty = flagProperty(project, "anvil.fuzzy")
        val forceProperty = flagProperty(project, "anvil.force")

        val checkTask = registerSourceTask<CheckUnsavedWorkTask>(
            project, ext, "checkUnsavedWork",
            "Fails if the module sources hold edits that are not saved as patches.",
        ) {
            group = null
            gitOps.set(gitService)
            usesService(gitService)
            workDir.set(guardWork)
        }

        val decompileTask = project.tasks.register<DecompileTask>("decompile") {
            group = GROUP
            description = "Decompiles the input jar into the pristine base tree."
            inputJar.set(ext.inputJar)
            outputDir.set(ext.generatedDir)
            decompilerClasspath.from(decompilerConfiguration)
        }

        val distributeTask = registerSourceTask<DistributeSourcesTask>(
            project, ext, "distributeSources",
            "Copies the pristine decompiled tree into the module source sets.",
        ) { dependsOn(decompileTask) }

        val applyFileTask = registerSourceTask<ApplyFilePatchesTask>(
            project, ext, "applyFilePatches",
            "Overlays every per-file patch onto whatever is currently in the modules.",
        ) {
            dependsOn(decompileTask)
            mustRunAfter(distributeTask)
            gitOps.set(gitService)
            usesService(gitService)
            workDir.set(patchWork)
            rejectsDir.set(rejects)
            failOnApplyError.set(ext.failOnApplyError)
            fuzzy.convention(fuzzyProperty)
        }

        val rebuildTask = registerSourceTask<RebuildFilePatchesTask>(
            project, ext, "rebuildFilePatches",
            "Regenerates per-file patches by diffing the module sources against the pristine base.",
        ) {
            dependsOn(decompileTask)
            gitOps.set(gitService)
            usesService(gitService)
            workDir.set(patchWork)
        }

        val statusTask = registerSourceTask<PatchStatusTask>(
            project, ext, "patchStatus",
            "Shows what rebuildFilePatches would change, without writing anything.",
        ) {
            dependsOn(decompileTask)
            gitOps.set(gitService)
            usesService(gitService)
            workDir.set(statusWork)
        }

        val listTask = registerSourceTask<ListPatchesTask>(
            project, ext, "listPatches",
            "Lists the per-file patches currently in the patch tree.",
        )

        val inspectTask = project.tasks.register<InspectDecompiledStructureTask>(
            "inspectDecompiledStructure"
        ) {
            group = GROUP
            description = "Prints the decompiled package structure, to help write your mappings."
            dependsOn(decompileTask)
            generatedDir.set(ext.generatedDir)
        }

        val cleanDistributedTask = registerSourceTask<CleanDistributedSourcesTask>(
            project, ext, "cleanDistributedSources",
            "Removes the distributed sources from the module trees.",
        ) {
            dependsOn(checkTask)
            force.convention(false)
        }

        val cleanGeneratedTask = project.tasks.register<Delete>("cleanGenerated") {
            group = GROUP
            description = "Deletes the pristine decompiled base (regenerated on next decompile)."
            delete(ext.generatedDir)
        }

        val cleanPatchWorkTask = project.tasks.register<Delete>("cleanPatchWork") {
            group = GROUP
            description = "Deletes the ephemeral patch working directories."
            delete(project.layout.buildDirectory.dir("anvil"), rejects)
        }

        registerLifecycle(project, "cleanAll", "Cleans module sources, the base, and the work dirs.") {
            dependsOn(cleanDistributedTask, cleanGeneratedTask, cleanPatchWorkTask)
        }

        val applyPatchesTask = registerLifecycle(
            project, "applyPatches",
            "Full reconstruct: distribute the pristine base into modules, then overlay all patches.",
        ) { dependsOn(distributeTask, applyFileTask) }

        val setupTask = registerLifecycle(
            project, "setup",
            "First-time setup: decompile, distribute, and apply all patches.",
        ) {
            dependsOn(applyPatchesTask)
            doLast { logger.lifecycle("\n✨ Setup complete — module sources reflect base + patches.") }
        }

        val resetTask = project.tasks.register<ForceableLifecycleTask>("reset") {
            group = GROUP
            description = "Discards module edits and rebuilds the working tree from base + patches."
            completionMessage.set("\n✨ Sources reset — module sources reflect base + patches.")
            force.convention(false)
            dependsOn(cleanDistributedTask, applyPatchesTask)
        }

        val freshTask = project.tasks.register<ForceableLifecycleTask>("fresh") {
            group = GROUP
            description = "Wipes module sources, the decompiled base and the work dirs, then re-runs setup."
            completionMessage.set("\n✨ Fresh start complete.")
            force.convention(false)
            dependsOn(cleanDistributedTask, cleanGeneratedTask, cleanPatchWorkTask, setupTask)
        }

        project.tasks.register<AnvilHelpTask>("anvilHelp") {
            group = GROUP
            description = "Prints the Anvil workflow guide."
        }

        decompileTask.configure { mustRunAfter(cleanGeneratedTask, cleanPatchWorkTask) }
        distributeTask.configure { mustRunAfter(cleanDistributedTask) }
        applyFileTask.configure { mustRunAfter(cleanDistributedTask) }
        checkTask.configure { mustRunAfter(decompileTask) }

        checkTask.configure {
            force.set(
                anyOf(
                    forceProperty,
                    resetTask.flatMap { it.force },
                    freshTask.flatMap { it.force },
                    cleanDistributedTask.flatMap { it.force },
                )
            )
        }

        alias(project, "status", statusTask)
        alias(project, "rebuild", rebuildTask)
        alias(project, "list", listTask)
        alias(project, "inspect", inspectTask)
        alias(project, "applyOnly", applyFileTask)
    }

    private fun applyConventions(project: Project, ext: AnvilExtension) {
        ext.vineflowerVersion.convention(DEFAULT_VINEFLOWER_VERSION)
        ext.generatedDir.convention(project.layout.projectDirectory.dir("sources/generated"))
        ext.patchesDir.convention(project.layout.projectDirectory.dir("patches"))
        ext.javaSourceRoot.convention("src/main/java")
        ext.resourceRoot.convention("src/main/resources")
        ext.failOnApplyError.convention(false)
    }

    /** `-Panvil.foo` (no value) means true; `-Panvil.foo=false` turns it off. */
    private fun flagProperty(project: Project, name: String): Provider<Boolean> =
        project.providers.gradleProperty(name)
            .map { it.isEmpty() || it.toBoolean() }
            .orElse(false)

    private fun anyOf(vararg flags: Provider<Boolean>): Provider<Boolean> =
        flags.reduce { acc, flag -> acc.zip(flag) { a, b -> a || b } }

    /**
     * Registers a short name that just depends on the real task. Skipped if something else already
     * claimed the name, so a generic alias like `status` can never break a consumer's build.
     */
    private fun alias(project: Project, name: String, target: TaskProvider<out Task>) {
        if (name in project.tasks.names) {
            project.logger.info("Anvil: task '$name' already exists; skipping the alias.")
            return
        }
        project.tasks.register(name) {
            group = GROUP
            description = "Alias for ${target.name}."
            dependsOn(target)
        }
    }

    /**
     * Vineflower is resolved as an ordinary dependency, which buys the Gradle dependency cache,
     * checksum verification and `--offline` support.
     *
     * The repository is registered with `exclusiveContent` so it only ever serves `org.vineflower`
     * and cannot silently satisfy the consumer's own dependencies from a repository they did not
     * choose to trust.
     */
    private fun registerDecompilerConfiguration(project: Project, ext: AnvilExtension): Any {
        project.repositories.exclusiveContent {
            forRepository { project.repositories.maven(MAVEN_CENTRAL) }
            filter { includeGroup(VINEFLOWER_GROUP) }
        }
        val configuration = project.configurations.resolvable(DECOMPILER_CONFIGURATION) {
            description = "The decompiler Anvil runs against the input jar."
        }
        project.dependencies.addProvider(
            DECOMPILER_CONFIGURATION,
            ext.vineflowerVersion.map { "$VINEFLOWER_GROUP:vineflower:$it" },
        )
        return configuration
    }

    private inline fun <reified T : AnvilSourceTask> registerSourceTask(
        project: Project,
        ext: AnvilExtension,
        name: String,
        taskDescription: String,
        crossinline configure: T.() -> Unit = {},
    ): TaskProvider<T> = project.tasks.register<T>(name) {
        group = GROUP
        description = taskDescription
        generatedDir.set(ext.generatedDir)
        patchesDir.set(ext.patchesDir)
        packageMappings.set(ext.packageMappings)
        resourceMappings.set(ext.resourceMappings)
        javaSourceRoot.set(ext.javaSourceRoot)
        resourceRoot.set(ext.resourceRoot)
        configure()
    }

    private fun registerLifecycle(
        project: Project,
        name: String,
        taskDescription: String,
        configure: Task.() -> Unit,
    ): TaskProvider<Task> = project.tasks.register(name) {
        group = GROUP
        description = taskDescription
        configure()
    }

    companion object {
        const val EXTENSION_NAME = "anvil"
        const val GROUP = "anvil"
        const val GIT_SERVICE_NAME = "anvilGitOps"
        const val DEFAULT_VINEFLOWER_VERSION = "1.12.0"
        private const val DECOMPILER_CONFIGURATION = "anvilDecompiler"
        private const val VINEFLOWER_GROUP = "org.vineflower"
        private val MAVEN_CENTRAL = URI("https://repo.maven.apache.org/maven2")
        private const val REJECTS_DIR = ".patch-rejects"
    }
}
