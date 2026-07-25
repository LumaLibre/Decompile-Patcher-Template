package dev.lumas.anvil

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property

/**
 * Configuration for the Anvil, exposed to consumers as the `anvil { }` block.
 */
abstract class AnvilExtension(private val project: Project) {

    /**
     * The jar to decompile. Has no default — point it at whatever you dropped into `sources/`.
     *
     * ```
     * inputJar = layout.projectDirectory.file("sources/Example.jar")
     * ```
     */
    abstract val inputJar: RegularFileProperty

    /** Vineflower version, resolved from Maven Central. Defaults to `1.12.0`. */
    abstract val vineflowerVersion: Property<String>

    /** Where the pristine decompiled tree lands. Defaults to `sources/generated`. */
    abstract val generatedDir: DirectoryProperty

    /** Where per-file patches live. Defaults to `patches`. */
    abstract val patchesDir: DirectoryProperty

    /** Java source root inside each module. Defaults to `src/main/java`. */
    abstract val javaSourceRoot: Property<String>

    /** Resource root inside each module. Defaults to `src/main/resources`. */
    abstract val resourceRoot: Property<String>

    /**
     * Whether `applyFilePatches` fails the build when a patch does not apply cleanly.
     * Defaults to `false` — conflicts are reported with guidance and left in the working tree
     * for you to resolve. Turn it on in CI.
     */
    abstract val failOnApplyError: Property<Boolean>

    /** packagePath (e.g. `dev/lumas/example`) -> module project directory. */
    internal abstract val packageMappings: MapProperty<String, Directory>

    /** resource name (e.g. `plugin.yml` or `lang`) -> module project directory. */
    internal abstract val resourceMappings: MapProperty<String, Directory>

    /**
     * Routes a decompiled package into a module's Java source root.
     *
     * ```
     * sourcePackage("dev/lumas/example")             // -> root project
     * sourcePackage("dev/lumas/example/nms", ":nms") // -> the :nms module
     * ```
     */
    @JvmOverloads
    fun sourcePackage(packagePath: String, module: String = ".") {
        packageMappings.put(normalize(packagePath), moduleDirectory(module))
    }

    /** Routes a decompiled package into an explicit directory. */
    fun sourcePackage(packagePath: String, moduleDir: Directory) {
        packageMappings.put(normalize(packagePath), moduleDir)
    }

    /**
     * Routes a decompiled resource — a single file or a whole directory — into a module's
     * resource root.
     *
     * ```
     * resource("plugin.yml")
     * resource("lang", ":core")
     * ```
     */
    @JvmOverloads
    fun resource(name: String, module: String = ".") {
        resourceMappings.put(normalize(name), moduleDirectory(module))
    }

    /** Routes a decompiled resource into an explicit directory. */
    fun resource(name: String, moduleDir: Directory) {
        resourceMappings.put(normalize(name), moduleDir)
    }

    private fun moduleDirectory(module: String): Directory {
        if (module == "." || module == ":" || module.isEmpty()) {
            return project.layout.projectDirectory
        }
        val path = if (module.startsWith(":")) module else ":$module"
        val target = project.findProject(path)
            ?: throw IllegalArgumentException(
                "Anvil: no such module '$path'. Declare it in settings.gradle.kts, " +
                    "or use \".\" to target the root project."
            )
        return target.layout.projectDirectory
    }

    /** Patches are keyed by forward-slash paths, so accept either separator and trim edges. */
    private fun normalize(path: String): String =
        path.replace('\\', '/').trim('/')
}
