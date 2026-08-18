package dev.lumas.anvil.task

import dev.lumas.anvil.internal.DecompileRepair
import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
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

    @get:Internal
    abstract val repairFailures: Property<Boolean>

    @get:Internal
    abstract val failOnDecompileError: Property<Boolean>

    @get:Internal
    abstract val javaHome: Property<String>

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
        // A class the decompiler chokes on is expected, not fatal: it still emits everything else.
        // Exit-value handling is explicit so a future decompiler that *does* exit non-zero on a
        // partial failure cannot silently start breaking builds.
        val result = execOps.javaexec {
            classpath = decompilerClasspath
            mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")
            args(jar.absolutePath, out.absolutePath)
            isIgnoreExitValue = true
        }

        val produced = out.walkTopDown().count { it.isFile && it.extension == "java" }
        if (produced == 0) {
            throw GradleException(
                "Anvil: the decompiler produced no sources from ${jar.name} " +
                    "(exit ${result.exitValue}). See the output above."
            )
        }
        if (result.exitValue != 0) {
            val message = "Anvil: the decompiler exited ${result.exitValue} but produced " +
                "$produced source file(s); continuing with what it managed."
            if (failOnDecompileError.getOrElse(false)) throw GradleException(message)
            logger.warn("⚠️  $message")
        }

        if (repairFailures.getOrElse(true)) repairFailedOutput(jar, out)
    }

    /**
     * The decompiler reports per-class failures in-band, as comments, and still exits 0 — so the
     * damage only shows up later as a compile error. Rewrite those into valid Java here.
     */
    private fun repairFailedOutput(jar: File, out: File) {
        val report = DecompileRepair(
            generatedDir = out,
            inputJar = jar,
            javap = locateJavap(),
            execOps = execOps,
            logger = logger,
        ).repair()

        if (report.isEmpty) return

        logger.lifecycle("")
        logger.lifecycle("🩹 Some output was not valid Java; Anvil repaired it:")
        report.stubbedClasses.forEach {
            logger.lifecycle("   ✗ class   $it  (stub reconstructed from bytecode)")
        }
        report.repairedMethods.forEach { (file, count) ->
            logger.lifecycle("   ≈ method  $file  ($count body/bodies stubbed)")
        }
        report.unrepairable.forEach {
            logger.warn("   ⚠ could not repair $it — it will not compile as-is")
        }
        logger.lifecycle(
            "   These are stubs, not real code. Patch them by hand if you need the behaviour."
        )

        if (report.unrepairable.isNotEmpty() && failOnDecompileError.getOrElse(false)) {
            throw GradleException(
                "Anvil: ${report.unrepairable.size} file(s) could not be repaired into valid Java."
            )
        }
    }

    /** `javap` ships with the JDK running Gradle; without it, class stubs are skipped. */
    private fun locateJavap(): File? {
        val home = File(javaHome.get())
        return listOf("bin/javap", "bin/javap.exe")
            .map { home.resolve(it) }
            .firstOrNull { it.isFile }
    }
}
