package dev.lumas.anvil.task

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

abstract class DecompileTask : DefaultTask() {

    @get:Internal
    abstract val inputJar: RegularFileProperty

    /**
     * The tracked view of [inputJar].
     *
     * `@InputFile` — even with `@Optional` — fails validation when the property points at a file
     * that does not exist, which buries the useful advice under a generic Gradle error. A
     * `FileCollection` input tolerates the missing file and lets [decompile] report it properly.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val inputJarFiles: FileCollection by lazy { objects.fileCollection().from(inputJar) }

    @get:Classpath
    abstract val decompilerClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    protected abstract val objects: ObjectFactory

    @get:Inject
    protected abstract val execOps: ExecOperations

    @get:Inject
    protected abstract val fsOps: FileSystemOperations

    @TaskAction
    fun decompile() {
        val jar = inputJar.orNull?.asFile
            ?: throw GradleException(
                "Anvil: no input jar configured. Set it in your build script:\n" +
                    "    anvil { inputJar = layout.projectDirectory.file(\"sources/Example.jar\") }"
            )
        if (!jar.isFile) {
            throw GradleException(
                "Anvil: input jar not found at ${jar.absolutePath}\n" +
                    "Drop the jar there, or point anvil.inputJar somewhere else."
            )
        }

        val out = outputDir.get().asFile
        // start clean so classes removed from the jar don't linger in the base tree.
        fsOps.delete { delete(out) }
        out.mkdirs()

        logger.lifecycle("🔨 Decompiling ${jar.name} -> ${out.name}/")
        execOps.javaexec {
            classpath = decompilerClasspath
            mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")
            args(jar.absolutePath, out.absolutePath)
        }
    }
}
