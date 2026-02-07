// Git patch system is not my original work
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import groovy.json.JsonSlurper
import org.gradle.kotlin.dsl.build
import org.gradle.kotlin.dsl.invoke
import org.gradle.kotlin.dsl.jar
import org.gradle.kotlin.dsl.shadowJar
import java.net.URI
import javax.inject.Inject
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream

val decompilerDir = layout.buildDirectory.dir("decompiler")
val sourcesDir = layout.projectDirectory.dir("sources")
val modelEngineJar = sourcesDir.file("ModelEngine.jar")
val generatedOutputDir = sourcesDir.dir("generated")
val decompiledRoot = generatedOutputDir.dir("com/ticxo/modelengine")
val patchesDirPath = layout.projectDirectory.dir("patches")

plugins {
    id("java-library")
    id("io.freefair.lombok") version "9.2.0"
    id("com.gradleup.shadow") version "9.3.1"
}

allprojects {
    apply(plugin = "java-library")
    apply(plugin = "io.freefair.lombok")
    apply(plugin = "com.gradleup.shadow")

    group = "com.ticxo.modelengine"
    version = "R4.1.0"

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://repo.inventivetalent.org/repository/public/")
        maven("https://repo.viaversion.com/everything")
        maven("https://maven.citizensnpcs.co/repo")
        maven("https://mvn.lib.co.nz/public")
        maven("https://mvn.lumine.io/repository/maven-public/")
    }

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
}


val nms = project(":nms")
    .subprojects
    .map { it.name }
dependencies {
    implementation(project(":core"))
    implementation(project(":core21"))

    // NMS Implementations
    for (project in nms) {
        implementation(project(path = ":nms:${project}"))
    }
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("ModelEngine")

        configurations = listOf(project.configurations.runtimeClasspath.get())

        relocate("org.mineskin", "com.ticxo.modelengine.libs.mineskin")

        manifest {
            attributes(
                "Implementation-Title" to "ModelEngine",
                "Implementation-Version" to project.version,
                "Multi-Release" to "true",
                "paperweight-mappings-namespace" to "mojang"
            )
        }
    }

    build {
        dependsOn(shadowJar)
    }

    jar {
        enabled = false
    }
}

tasks.register("setupVineFlower") {
    doLast {
        decompilerDir.get().asFile.mkdirs()
        sourcesDir.asFile.mkdirs()
        generatedOutputDir.asFile.mkdirs()

        if (!modelEngineJar.asFile.exists()) {
            println("ModelEngine.jar not found in sources/. Nothing to decompile.")
            return@doLast
        }

        println("Fetching latest Vineflower release info...")

        val apiUrl = URI("https://api.github.com/repos/Vineflower/vineflower/releases/latest").toURL()
        val json = JsonSlurper().parse(apiUrl)

        @Suppress("UNCHECKED_CAST")
        val assets = (json as Map<String, Any>)["assets"] as List<Map<String, Any>>
        val jarAsset = assets.firstOrNull { it["name"].toString().endsWith(".jar") }
            ?: error("Could not find Vineflower jar in latest release")

        val downloadUrl = jarAsset["browser_download_url"].toString()
        val vineflowerJar = decompilerDir.get().file(jarAsset["name"].toString()).asFile

        if (!vineflowerJar.exists()) {
            println("Downloading Vineflower...")
            URI(downloadUrl).toURL().openStream().use { input ->
                Files.copy(input, vineflowerJar.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } else {
            println("Vineflower already downloaded.")
        }

        println("Vineflower jar: ${vineflowerJar.name}")
    }
}

val decompile by tasks.registering(Exec::class) {
    dependsOn("setupVineFlower")

    val vineflowerJar = decompilerDir.get().asFile.listFiles()
        ?.firstOrNull { it.name.endsWith(".jar") }
        ?: error("Vineflower jar not found. Run setupVineFlower first.")

    inputs.file(modelEngineJar)
    outputs.dir(generatedOutputDir)

    doFirst {
        generatedOutputDir.asFile.mkdirs()
    }

    commandLine(
        "java",
        "-jar",
        vineflowerJar.absolutePath,
        modelEngineJar.asFile.absolutePath,
        generatedOutputDir.asFile.absolutePath
    )
}

/**
 * Maps package prefixes to their corresponding submodule directories.
 */
val packageToModuleMap = mapOf(
    "com/ticxo/modelengine/api" to "api",
    "com/ticxo/modelengine/core" to "core",
    "com/ticxo/modelengine/core21" to "core21",
//    "com/ticxo/modelengine/v1_19_R3" to "nms:v1_19_R3",
//    "com/ticxo/modelengine/v1_20_R1" to "nms:v1_20_R1",
//    "com/ticxo/modelengine/v1_20_R2" to "nms:v1_20_R2",
//    "com/ticxo/modelengine/v1_20_R3" to "nms:v1_20_R3",
    "com/ticxo/modelengine/v1_20_R4" to "nms:v1_20_R4",
    "com/ticxo/modelengine/v1_21_R1" to "nms:v1_21_R1",
    "com/ticxo/modelengine/v1_21_R2" to "nms:v1_21_R2",
    "com/ticxo/modelengine/v1_21_R3" to "nms:v1_21_R3",
    "com/ticxo/modelengine/v1_21_R4" to "nms:v1_21_R4",
    "com/ticxo/modelengine/v1_21_R5" to "nms:v1_21_R5",
    "com/ticxo/modelengine/v1_21_R6" to "nms:v1_21_R6",
    "com/ticxo/modelengine/v1_21_R7" to "nms:v1_21_R7"
)


val coreResources = listOf("internal", "me", "pack", "config.yml", "plugin.yml", "plugin-data.json")

tasks.register("distributeSources") {
    dependsOn(decompile)

    doLast {
        val generatedDir = generatedOutputDir.asFile
        if (!generatedDir.exists()) {
            println("No generated sources found. Run 'decompile' task first.")
            return@doLast
        }

        println("Distributing decompiled sources to submodules...")

        packageToModuleMap.forEach { (packagePath, moduleName) ->
            val sourcePackageDir = generatedDir.resolve(packagePath)

            if (!sourcePackageDir.exists()) {
                println("⚠ Package directory not found: $packagePath")
                return@forEach
            }

            val moduleProject = try {
                project(":$moduleName")
            } catch (e: Exception) {
                println("❌ Module not found: :$moduleName")
                return@forEach
            }

            val moduleDir = moduleProject.projectDir
            val targetSrcDir = moduleDir.resolve("src/main/java/$packagePath")

            println("📦 Copying $packagePath -> :$moduleName")

            targetSrcDir.mkdirs()

            var fileCount = 0
            sourcePackageDir.walkTopDown().forEach { sourceFile ->
                if (sourceFile.isFile && sourceFile.extension == "java") {
                    val relativePath = sourceFile.relativeTo(sourcePackageDir)
                    val targetFile = targetSrcDir.resolve(relativePath)

                    targetFile.parentFile.mkdirs()
                    sourceFile.copyTo(targetFile, overwrite = true)
                    fileCount++
                }
            }

            println("✓ Copied $fileCount files to :$moduleName")
        }


        println("\n📦 Copying resources to :core...")
        val coreModule = try {
            project(":core")
        } catch (e: Exception) {
            println("❌ Core module not found")
            return@doLast
        }

        val coreResourcesDir = coreModule.projectDir.resolve("src/main/resources")
        coreResourcesDir.mkdirs()

        var resourceCount = 0
        coreResources.forEach { resourceName ->
            val sourceResource = generatedDir.resolve(resourceName)
            if (sourceResource.exists()) {
                val targetResource = coreResourcesDir.resolve(resourceName)
                if (sourceResource.isDirectory) {
                    sourceResource.copyRecursively(targetResource, overwrite = true)
                    val fileCount = sourceResource.walkTopDown().count { it.isFile }
                    println("  ✓ Copied directory: $resourceName ($fileCount files)")
                    resourceCount += fileCount
                } else {
                    sourceResource.copyTo(targetResource, overwrite = true)
                    println("  ✓ Copied file: $resourceName")
                    resourceCount++
                }
            } else {
                println("  ⚠ Resource not found: $resourceName")
            }
        }

        println("✓ Copied $resourceCount resource files to :core")
        println("\n✓ Source distribution complete!")
    }
}

tasks.register("cleanDistributedSources") {
    doLast {
        println("Cleaning distributed sources from submodules...")

        packageToModuleMap.forEach { (packagePath, moduleName) ->
            try {
                val moduleDir = project(":$moduleName").projectDir
                val targetSrcDir = moduleDir.resolve("src/main/java/$packagePath")

                if (targetSrcDir.exists()) {
                    targetSrcDir.deleteRecursively()
                    println("🗑 Cleaned :$moduleName/src/main/java/$packagePath")
                }
            } catch (e: Exception) {
                println("⚠ Could not clean :$moduleName - ${e.message}")
            }
        }

        // Clean resource files from core module
        try {
            val coreModule = project(":core")
            val coreResourcesDir = coreModule.projectDir.resolve("src/main/resources")

            coreResources.forEach { resourceName ->
                val targetResource = coreResourcesDir.resolve(resourceName)
                if (targetResource.exists()) {
                    if (targetResource.isDirectory) {
                        targetResource.deleteRecursively()
                    } else {
                        targetResource.delete()
                    }
                    println("🗑 Cleaned :core/src/main/resources/$resourceName")
                }
            }
        } catch (e: Exception) {
            println("⚠ Could not clean core resources - ${e.message}")
        }

        println("✓ Clean complete!")
    }
}

tasks.register("cleanGenerated") {
    doLast {
        if (generatedOutputDir.asFile.exists()) {
            generatedOutputDir.asFile.deleteRecursively()
            println("🗑 Cleaned sources/generated")
        }
    }
}

// ============================================================================
// GIT-BASED PATCH MANAGEMENT SYSTEM
// ============================================================================

// Helper class to encapsulate git operations with ExecOperations
class GitOperations(private val execOps: ExecOperations) {

    fun init(workingDir: File) {
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "init")
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    }

    fun config(workingDir: File, key: String, value: String) {
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "config", key, value)
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    }

    fun add(workingDir: File, path: String = ".") {
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "add", path)
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
        }
    }

    fun commit(workingDir: File, message: String): Int {
        val result = execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "commit", "-m", message)
            standardOutput = ByteArrayOutputStream()
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return result.exitValue
    }

    fun status(workingDir: File): String {
        val output = ByteArrayOutputStream()
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "status", "--short")
            standardOutput = output
        }
        return output.toString()
    }

    fun diffStat(workingDir: File): String {
        val output = ByteArrayOutputStream()
        execOps.exec {
            this.workingDir = workingDir
            commandLine("git", "diff", "--cached", "--stat")
            standardOutput = output
        }
        return output.toString()
    }

    fun diffCached(workingDir: File, outputFile: File) {
        outputFile.outputStream().use { output ->
            execOps.exec {
                this.workingDir = workingDir
                commandLine("git", "diff", "--cached")
                standardOutput = output
            }
        }
    }

    fun apply(workingDir: File, patchFile: File, verbose: Boolean = false): Pair<Int, String> {
        val errorOutput = ByteArrayOutputStream()
        val stdOutput = ByteArrayOutputStream()
        val args = mutableListOf("git", "apply")
        if (verbose) args.add("--verbose")
        args.add(patchFile.absolutePath)

        val result = execOps.exec {
            this.workingDir = workingDir
            commandLine(args)
            standardOutput = stdOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        return Pair(result.exitValue, errorOutput.toString() + stdOutput.toString())
    }

    fun setupRepo(workingDir: File) {
        init(workingDir)
        config(workingDir, "user.name", "ModelEngine Patcher")
        config(workingDir, "user.email", "patch@modelengine.local")
    }
}

/**
 * Creates a single named patch file containing all modified files using git.
 * Usage: ./gradlew createPatch -PpatchName="my-feature"
 */
abstract class CreatePatchTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val patchName: Property<String>

    @get:InputDirectory
    abstract val generatedOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:Input
    abstract val packageToModuleMap: MapProperty<String, String>

    @get:Internal
    abstract val projectDir: DirectoryProperty

    @TaskAction
    fun createPatch() {
        val gitOps = GitOperations(execOps)
        val patchNameValue = patchName.get()
        val patchesDirFile = patchesDir.get().asFile
        patchesDirFile.mkdirs()

        val patchFile = patchesDirFile.resolve("$patchNameValue.patch")

        println("\n🔧 Creating patch: $patchNameValue.patch")
        println("=".repeat(60))

        val gitDir = projectDir.get().asFile.resolve(".patch-git")

        if (gitDir.exists()) {
            gitDir.deleteRecursively()
        }
        gitDir.mkdirs()

        try {
            gitOps.setupRepo(gitDir)

            println("📋 Setting up git workspace...")
            packageToModuleMap.get().forEach { (packagePath, _) ->
                val sourceDir = generatedOutputDir.get().asFile.resolve(packagePath)
                if (sourceDir.exists()) {
                    val targetDir = gitDir.resolve(packagePath)
                    sourceDir.copyRecursively(targetDir, overwrite = true)
                }
            }

            gitOps.add(gitDir)
            gitOps.commit(gitDir, "Original decompiled sources")

            println("📝 Comparing with modified sources...")
            packageToModuleMap.get().forEach { (packagePath, moduleName) ->
                val moduleDir = findModuleDir(moduleName)
                val moduleSrcDir = moduleDir?.resolve("src/main/java/$packagePath")

                val targetDir = gitDir.resolve(packagePath)

                // Delete the target directory first to capture file/folder deletions
                if (targetDir.exists()) {
                    targetDir.deleteRecursively()
                }

                // Copy module sources (if they exist) - deleted files/folders won't be copied
                // If moduleSrcDir doesn't exist or is empty, the entire package is deleted
                if (moduleSrcDir != null && moduleSrcDir.exists() && moduleSrcDir.walkTopDown().any { it.isFile }) {
                    moduleSrcDir.copyRecursively(targetDir, overwrite = true)
                }
                // If moduleSrcDir doesn't exist, targetDir stays deleted = entire package deletion
            }

            val hasChanges = gitOps.status(gitDir).isNotBlank()

            if (!hasChanges) {
                println("=".repeat(60))
                println("ℹ️  No changes detected - no patch created")
                return
            }

            gitOps.add(gitDir, "-A") // -A stages deletions too
            println(gitOps.diffStat(gitDir))
            gitOps.diffCached(gitDir, patchFile)

            // Apply changes to generated sources so future patches are incremental
            println("\n📦 Updating generated sources...")
            packageToModuleMap.get().forEach { (packagePath, moduleName) ->
                val moduleDir = findModuleDir(moduleName)
                val moduleSrcDir = moduleDir?.resolve("src/main/java/$packagePath")
                val generatedTargetDir = generatedOutputDir.get().asFile.resolve(packagePath)

                // Delete existing generated sources for this package
                if (generatedTargetDir.exists()) {
                    generatedTargetDir.deleteRecursively()
                }

                // Copy current module sources to generated (if they exist)
                if (moduleSrcDir != null && moduleSrcDir.exists() && moduleSrcDir.walkTopDown().any { it.isFile }) {
                    moduleSrcDir.copyRecursively(generatedTargetDir, overwrite = true)
                    println("  ✓ Updated generated: $packagePath")
                } else if (generatedTargetDir.exists() || moduleDir != null) {
                    println("  ✓ Removed from generated: $packagePath")
                }
            }

            println("\n" + "=".repeat(60))
            println("✨ Created patch: patches/$patchNameValue.patch")
            println("   Generated sources updated - patchStatus should now show no changes")

        } finally {
            gitDir.deleteRecursively()
        }
    }

    private fun findModuleDir(moduleName: String): File? {
        return try {
            project.project(":$moduleName").projectDir
        } catch (e: Exception) {
            null
        }
    }
}

// Capture variables for task configuration
val rootGeneratedOutputDir = generatedOutputDir
val rootPackageToModuleMap = packageToModuleMap
val rootPatchesDirPath = patchesDirPath

tasks.register<CreatePatchTask>("createPatch") {
    val patchNameProp = project.findProperty("patchName") as String? ?: ""
    patchName.set(patchNameProp)
    generatedOutputDir.set(rootGeneratedOutputDir)
    patchesDir.set(rootPatchesDirPath)
    packageToModuleMap.set(rootPackageToModuleMap)
    projectDir.set(layout.projectDirectory)

    doFirst {
        if (patchName.get().isEmpty()) {
            throw GradleException("Please specify patch name: -PpatchName=\"name\"")
        }
    }
}

/**
 * Lists all available patches with git-style stats
 */
tasks.register("listPatches") {
    doLast {
        val patchesDirFile = patchesDirPath.asFile

        if (!patchesDirFile.exists() || patchesDirFile.listFiles()?.isEmpty() != false) {
            println("❌ No patches found in patches/")
            return@doLast
        }

        println("\n📋 Available Patches")
        println("=".repeat(60))

        patchesDirFile.listFiles()
            ?.filter { it.extension == "patch" }
            ?.sortedBy { it.name }
            ?.forEach { patchFile ->
                val lines = patchFile.readLines()
                val filesChanged = lines.count { it.startsWith("diff --git") }
                val additions = lines.count { it.startsWith("+") && !it.startsWith("+++") }
                val deletions = lines.count { it.startsWith("-") && !it.startsWith("---") }
                val sizeKb = patchFile.length() / 1024

                println("📄 ${patchFile.nameWithoutExtension}")
                println("   Files: $filesChanged | +$additions -$deletions | ${sizeKb}KB")
            }

        println("=".repeat(60))
    }
}

/**
 * Applies a specific patch by name using git apply.
 * Usage: ./gradlew applyPatch -PpatchName="my-feature"
 */
abstract class ApplyPatchTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:Input
    abstract val patchName: Property<String>

    @get:InputDirectory
    abstract val patchesDir: DirectoryProperty

    @get:Input
    abstract val packageToModuleMap: MapProperty<String, String>

    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:Internal
    abstract val generatedOutputDir: DirectoryProperty

    @TaskAction
    fun applyPatch() {
        val gitOps = GitOperations(execOps)
        val patchNameValue = patchName.get()
        val patchesDirFile = patchesDir.get().asFile
        val patchFile = patchesDirFile.resolve("$patchNameValue.patch")

        if (!patchFile.exists()) {
            println("❌ Patch not found: $patchNameValue.patch")
            println("\nAvailable patches:")
            patchesDirFile.listFiles()
                ?.filter { it.extension == "patch" }
                ?.forEach { println("  - ${it.nameWithoutExtension}") }
            return
        }

        println("\n🔧 Applying patch: $patchNameValue.patch")
        println("=".repeat(60))

        val gitDir = projectDir.get().asFile.resolve(".patch-git")

        if (gitDir.exists()) {
            gitDir.deleteRecursively()
        }
        gitDir.mkdirs()

        try {
            gitOps.setupRepo(gitDir)

            println("📋 Setting up workspace...")
            packageToModuleMap.get().forEach { (packagePath, moduleName) ->
                val moduleDir = findModuleDir(moduleName) ?: return@forEach
                val moduleSrcDir = moduleDir.resolve("src/main/java/$packagePath")

                if (!moduleSrcDir.exists()) {
                    return@forEach
                }

                val targetDir = gitDir.resolve(packagePath)
                moduleSrcDir.copyRecursively(targetDir, overwrite = true)
            }

            gitOps.add(gitDir)
            gitOps.commit(gitDir, "Current state")

            println("📝 Applying patch...")
            val (exitCode, output) = gitOps.apply(gitDir, patchFile, verbose = true)

            if (exitCode != 0) {
                println("❌ Failed to apply patch:")
                println(output)
                return
            }

            println("📦 Copying patched files back to modules and generated sources...")
            packageToModuleMap.get().forEach { (packagePath, moduleName) ->
                val moduleDir = findModuleDir(moduleName) ?: return@forEach

                val gitSourceDir = gitDir.resolve(packagePath)
                val moduleTargetDir = moduleDir.resolve("src/main/java/$packagePath")
                val generatedTargetDir = generatedOutputDir.get().asFile.resolve(packagePath)

                // Delete existing and replace with patched version (handles deletions)
                if (moduleTargetDir.exists()) {
                    moduleTargetDir.deleteRecursively()
                }
                if (gitSourceDir.exists()) {
                    gitSourceDir.copyRecursively(moduleTargetDir, overwrite = true)
                }

                // Also update generated sources
                if (generatedTargetDir.exists()) {
                    generatedTargetDir.deleteRecursively()
                }
                if (gitSourceDir.exists()) {
                    gitSourceDir.copyRecursively(generatedTargetDir, overwrite = true)
                    println("  ✓ Updated $moduleName (+ generated sources)")
                } else {
                    println("  ✓ Updated $moduleName (package deleted)")
                }
            }

            println("=".repeat(60))
            println("✨ Patch applied successfully!")
            println("   Generated sources updated - future patches will only contain new changes")

        } catch (e: Exception) {
            println("❌ Error applying patch: ${e.message}")
            e.printStackTrace()
        } finally {
            gitDir.deleteRecursively()
        }
    }

    private fun findModuleDir(moduleName: String): File? {
        return try {
            project.project(":$moduleName").projectDir
        } catch (e: Exception) {
            null
        }
    }
}

tasks.register<ApplyPatchTask>("applyPatch") {
    val patchNameProp = project.findProperty("patchName") as String? ?: ""
    patchName.set(patchNameProp)
    patchesDir.set(rootPatchesDirPath)
    packageToModuleMap.set(rootPackageToModuleMap)
    projectDir.set(layout.projectDirectory)
    generatedOutputDir.set(rootGeneratedOutputDir)

    doFirst {
        if (patchName.get().isEmpty()) {
            throw GradleException("Please specify patch name: -PpatchName=\"name\"")
        }
    }
}

/**
 * Applies all patches from the patches/ directory in alphabetical order.
 */
abstract class ApplyAllPatchesTask @Inject constructor(
    private val execOps: ExecOperations
) : DefaultTask() {

    @get:InputDirectory
    @get:Optional
    abstract val patchesDir: DirectoryProperty

    @get:Input
    abstract val packageToModuleMap: MapProperty<String, String>

    @get:Internal
    abstract val projectDir: DirectoryProperty

    @get:Internal
    abstract val generatedOutputDir: DirectoryProperty

    @TaskAction
    fun applyAllPatches() {
        val gitOps = GitOperations(execOps)
        val patchesDirFile = patchesDir.get().asFile

        if (!patchesDirFile.exists() || patchesDirFile.listFiles()?.isEmpty() != false) {
            println("❌ No patches found in patches/")
            return
        }

        println("\n🔧 Applying all patches...")
        println("=".repeat(60))

        var successCount = 0
        var failedCount = 0
        val failedPatches = mutableListOf<String>()

        patchesDirFile.listFiles()
            ?.filter { it.extension == "patch" }
            ?.sortedBy { it.name }
            ?.forEach { patchFile ->
                println("\n📄 Applying: ${patchFile.nameWithoutExtension}")

                val gitDir = projectDir.get().asFile.resolve(".patch-git")

                if (gitDir.exists()) {
                    gitDir.deleteRecursively()
                }
                gitDir.mkdirs()

                try {
                    gitOps.setupRepo(gitDir)

                    packageToModuleMap.get().forEach { (packagePath, moduleName) ->
                        val moduleDir = findModuleDir(moduleName) ?: return@forEach
                        val moduleSrcDir = moduleDir.resolve("src/main/java/$packagePath")

                        if (moduleSrcDir.exists()) {
                            val targetDir = gitDir.resolve(packagePath)
                            moduleSrcDir.copyRecursively(targetDir, overwrite = true)
                        }
                    }

                    gitOps.add(gitDir)
                    gitOps.commit(gitDir, "Current state")

                    val (exitCode, errorMsg) = gitOps.apply(gitDir, patchFile)

                    if (exitCode != 0) {
                        println("  ❌ Failed: ${errorMsg.take(200)}")
                        failedPatches.add(patchFile.nameWithoutExtension)
                        failedCount++
                    } else {
                        packageToModuleMap.get().forEach { (packagePath, moduleName) ->
                            val gitSourceDir = gitDir.resolve(packagePath)
                            val moduleDir = findModuleDir(moduleName) ?: return@forEach
                            val moduleTargetDir = moduleDir.resolve("src/main/java/$packagePath")
                            val generatedTargetDir = generatedOutputDir.get().asFile.resolve(packagePath)

                            // Delete existing and replace with patched version (handles deletions)
                            if (moduleTargetDir.exists()) {
                                moduleTargetDir.deleteRecursively()
                            }
                            if (gitSourceDir.exists()) {
                                gitSourceDir.copyRecursively(moduleTargetDir, overwrite = true)
                            }

                            // Also update generated sources
                            if (generatedTargetDir.exists()) {
                                generatedTargetDir.deleteRecursively()
                            }
                            if (gitSourceDir.exists()) {
                                gitSourceDir.copyRecursively(generatedTargetDir, overwrite = true)
                            }
                        }

                        println("  ✓ Success (+ updated generated sources)")
                        successCount++
                    }
                } catch (e: Exception) {
                    println("  ❌ Error: ${e.message}")
                    failedPatches.add(patchFile.nameWithoutExtension)
                    failedCount++
                } finally {
                    gitDir.deleteRecursively()
                }
            }

        println("\n" + "=".repeat(60))
        println("✨ Applied: $successCount | Failed: $failedCount")

        if (successCount > 0) {
            println("   Generated sources updated - future patches will only contain new changes")
        }

        if (failedPatches.isNotEmpty()) {
            println("\n❌ Failed patches:")
            failedPatches.forEach { println("  - $it") }
        }
    }

    private fun findModuleDir(moduleName: String): File? {
        return try {
            project.project(":$moduleName").projectDir
        } catch (e: Exception) {
            null
        }
    }
}

tasks.register<ApplyAllPatchesTask>("applyAllPatches") {
    patchesDir.set(rootPatchesDirPath)
    packageToModuleMap.set(rootPackageToModuleMap)
    projectDir.set(layout.projectDirectory)
    generatedOutputDir.set(rootGeneratedOutputDir)
}

/**
 * Shows which files have been modified compared to the original decompiled sources
 */
tasks.register("patchStatus") {
    doLast {
        println("\n📊 Patch Status - Modified Files")
        println("=".repeat(60))

        var modifiedCount = 0
        var newFilesCount = 0
        var deletedCount = 0

        packageToModuleMap.forEach { (packagePath, moduleName) ->
            val moduleProject = try {
                project(":$moduleName")
            } catch (e: Exception) {
                return@forEach
            }

            val moduleDir = moduleProject.projectDir
            val moduleSrcDir = moduleDir.resolve("src/main/java/$packagePath")

            val generatedDir = generatedOutputDir.asFile.resolve(packagePath)

            // Check if entire package was deleted
            if (!moduleSrcDir.exists() && generatedDir.exists()) {
                val deletedFiles = generatedDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .count()
                if (deletedFiles > 0) {
                    println("\n📦 $moduleName")
                    println("  🗑️  DELETED ENTIRE PACKAGE ($deletedFiles files)")
                    deletedCount += deletedFiles
                }
                return@forEach
            }

            if (!generatedDir.exists()) {
                return@forEach
            }

            val moduleModified = mutableListOf<String>()
            val moduleNew = mutableListOf<String>()
            val moduleDeleted = mutableListOf<String>()

            // Check for modified and new files
            if (moduleSrcDir.exists()) {
                moduleSrcDir.walkTopDown()
                    .filter { it.isFile && it.extension == "java" }
                    .forEach { modifiedFile ->
                        val relativePath = modifiedFile.relativeTo(moduleSrcDir)
                        val originalFile = generatedDir.resolve(relativePath)

                        if (!originalFile.exists()) {
                            moduleNew.add(relativePath.path)
                            newFilesCount++
                        } else if (originalFile.readText() != modifiedFile.readText()) {
                            moduleModified.add(relativePath.path)
                            modifiedCount++
                        }
                    }
            }

            // Check for deleted files (exist in generated but not in module)
            generatedDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { originalFile ->
                    val relativePath = originalFile.relativeTo(generatedDir)
                    val moduleFile = if (moduleSrcDir.exists()) moduleSrcDir.resolve(relativePath) else null

                    if (moduleFile == null || !moduleFile.exists()) {
                        moduleDeleted.add(relativePath.path)
                        deletedCount++
                    }
                }

            if (moduleModified.isNotEmpty() || moduleNew.isNotEmpty() || moduleDeleted.isNotEmpty()) {
                println("\n📦 $moduleName")
                moduleModified.forEach { println("  ✏️  Modified: $it") }
                moduleNew.forEach { println("  ➕ New: $it") }
                moduleDeleted.forEach { println("  🗑️  Deleted: $it") }
            }
        }

        println("\n" + "=".repeat(60))
        println("Summary: $modifiedCount modified, $newFilesCount new, $deletedCount deleted")

        if (modifiedCount > 0 || newFilesCount > 0 || deletedCount > 0) {
            println("\n💡 Run './gradlew createPatch -PpatchName=\"name\"' to save these changes")
        } else {
            println("\nℹ️  No modifications detected")
        }
    }
}

/**
 * Full workflow: decompile, distribute, and apply all patches
 */
tasks.register("decompileAndApplyPatches") {
    dependsOn(decompile, "distributeSources")
    finalizedBy("applyAllPatches")

    doLast {
        println("\n✨ Decompilation complete! Patches will be applied next...")
    }
}

// Diagnostic task to show actual package structure
tasks.register("inspectDecompiledStructure") {
    doLast {
        val generatedDir = generatedOutputDir.asFile
        if (!generatedDir.exists()) {
            println("❌ No generated sources found. Run 'decompile' task first.")
            return@doLast
        }

        println("\n📂 Inspecting decompiled package structure...")
        println("=".repeat(60))

        val rootPath = generatedDir.resolve("com/ticxo/modelengine")
        if (!rootPath.exists()) {
            println("❌ Base package com/ticxo/modelengine not found!")
            println("   Available structure:")
            generatedDir.walk().maxDepth(3).forEach { file ->
                if (file.isDirectory) {
                    println("   📁 ${file.relativeTo(generatedDir)}")
                }
            }
            return@doLast
        }

        rootPath.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val packageName = "com/ticxo/modelengine/${dir.name}"
            val javaFileCount = dir.walkTopDown().count { it.isFile && it.extension == "java" }

            println("📦 $packageName")
            println("   ├─ Java files: $javaFileCount")

            if (dir.name == "nms") {
                val nmsSubdirs = dir.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }
                nmsSubdirs?.forEach { versionDir ->
                    val versionPath = "$packageName/${versionDir.name}"
                    val versionFileCount = versionDir.walkTopDown().count { it.isFile && it.extension == "java" }
                    println("   │  ├─ $versionPath ($versionFileCount files)")
                }
            }

            println()
        }

        println("=".repeat(60))
        println("\n💡 Use this information to update packageToModuleMap if needed")
    }
}