package dev.lumas.anvil.internal

import java.io.File

/** A single tracked file: [base] is the pristine decompiled copy, [working] the module copy. */
internal data class SrcEntry(val canonical: String, val base: File, val working: File)

/**
 * Maps between the three trees Anvil juggles:
 *
 *  - **base**    — the pristine decompiled output under `generatedDir`
 *  - **working** — the live sources inside each module
 *  - **patches** — one `.patch` per file, mirroring the canonical layout
 *
 * The canonical layout is the module-relative path, so a file at
 * `<module>/src/main/java/dev/foo/Bar.java` is patched by `patches/src/main/java/dev/foo/Bar.java.patch`.
 *
 * All fields are plain [File]s and [Map]s so instances survive configuration-cache serialization.
 */
internal class PatchLayout(
    val generatedDir: File,
    val patchesDir: File,
    private val packageMappings: Map<String, File>,
    private val resourceMappings: Map<String, File>,
    private val javaRoot: String,
    private val resourceRoot: String,
) {

    fun moduleSrcDir(moduleDir: File, packagePath: String): File =
        moduleDir.resolve("$javaRoot/$packagePath")

    fun moduleResourcesDir(moduleDir: File): File = moduleDir.resolve(resourceRoot)

    /** Every file Anvil knows about, whether it exists in base, working, or both. */
    fun enumerateEntries(): List<SrcEntry> {
        val entries = mutableListOf<SrcEntry>()

        packageMappings.forEach { (packagePath, moduleDir) ->
            val basePkg = generatedDir.resolve(packagePath)
            val workPkg = moduleSrcDir(moduleDir, packagePath)
            val rels = LinkedHashSet<String>()
            if (basePkg.exists()) basePkg.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { rels.add(it.invariantRelativeTo(basePkg)) }
            if (workPkg.exists()) workPkg.walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .forEach { rels.add(it.invariantRelativeTo(workPkg)) }
            rels.forEach { rel ->
                entries.add(
                    SrcEntry("$javaRoot/$packagePath/$rel", basePkg.resolve(rel), workPkg.resolve(rel))
                )
            }
        }

        resourceMappings.forEach { (resourceName, moduleDir) ->
            val baseRes = generatedDir.resolve(resourceName)
            val workRes = moduleResourcesDir(moduleDir).resolve(resourceName)
            if (baseRes.isDirectory || workRes.isDirectory) {
                val rels = LinkedHashSet<String>()
                if (baseRes.exists()) baseRes.walkTopDown().filter { it.isFile }
                    .forEach { rels.add(it.invariantRelativeTo(baseRes)) }
                if (workRes.exists()) workRes.walkTopDown().filter { it.isFile }
                    .forEach { rels.add(it.invariantRelativeTo(workRes)) }
                rels.forEach { rel ->
                    entries.add(
                        SrcEntry(
                            "$resourceRoot/$resourceName/$rel",
                            baseRes.resolve(rel),
                            workRes.resolve(rel),
                        )
                    )
                }
            } else {
                entries.add(SrcEntry("$resourceRoot/$resourceName", baseRes, workRes))
            }
        }

        return entries
    }

    /**
     * Maps a canonical path — possibly for a file that only exists in a patch — back to where it
     * should be written in the module tree. Returns null if no mapping covers it.
     */
    fun canonicalToWorking(canonical: String): File? = when {
        canonical.startsWith("$javaRoot/") -> {
            val rel = canonical.removePrefix("$javaRoot/")
            longestMatch(packageMappings, rel)?.let { (_, moduleDir) ->
                moduleDir.resolve("$javaRoot/$rel")
            }
        }
        canonical.startsWith("$resourceRoot/") -> {
            val rel = canonical.removePrefix("$resourceRoot/")
            longestMatch(resourceMappings, rel)?.let { (_, moduleDir) ->
                moduleResourcesDir(moduleDir).resolve(rel)
            }
        }
        else -> null
    }

    private fun longestMatch(mappings: Map<String, File>, rel: String): Pair<String, File>? =
        mappings.entries
            .filter { rel == it.key || rel.startsWith("${it.key}/") }
            .maxByOrNull { it.key.length }
            ?.let { it.key to it.value }

    /** Lays the pristine base into [scratch] at canonical paths. */
    fun layoutBaseInto(scratch: File) {
        enumerateEntries().forEach { entry ->
            if (entry.base.isFile) {
                val target = scratch.resolve(entry.canonical)
                target.parentFile.mkdirs()
                entry.base.copyTo(target, overwrite = true)
            }
        }
    }

    /**
     * Replaces the canonical content in [scratch] with the current module sources. Clearing the
     * roots first means files deleted in a module show up as deletions in the diff.
     */
    fun layoutWorkingInto(scratch: File) {
        canonicalRoots().forEach { scratch.resolve(it).deleteRecursively() }
        enumerateEntries().forEach { entry ->
            if (entry.working.isFile) {
                val target = scratch.resolve(entry.canonical)
                target.parentFile.mkdirs()
                entry.working.copyTo(target, overwrite = true)
            }
        }
    }

    /** Top-level directories the canonical layout occupies, e.g. `src` for `src/main/java`. */
    private fun canonicalRoots(): Set<String> =
        setOf(javaRoot.substringBefore('/'), resourceRoot.substringBefore('/'))
            .filter { it.isNotEmpty() && it != "." }
            .toSet()

    fun allPatchFiles(): List<File> {
        if (!patchesDir.exists()) return emptyList()
        return patchesDir.walkTopDown()
            .filter { it.isFile && it.extension == "patch" }
            .sortedBy { it.path }
            .toList()
    }

    fun patchCanonical(patchFile: File): String =
        patchFile.invariantRelativeTo(patchesDir).removeSuffix(".patch")

    fun patchFileFor(canonical: String): File = patchesDir.resolve("$canonical.patch")
}

/** Always use forward slashes so paths match what git emits in diffs, regardless of host OS. */
internal fun File.invariantRelativeTo(base: File): String =
    this.relativeTo(base).path.replace('\\', '/')

internal fun pruneEmptyDirs(root: File) {
    if (!root.exists()) return
    root.walkBottomUp()
        .filter { it.isDirectory && it != root }
        .forEach { if (it.listFiles()?.isEmpty() == true) it.delete() }
}

internal fun hasConflictMarkers(file: File): Boolean = try {
    file.readLines().any {
        it.startsWith("<<<<<<<") || it.startsWith("=======") || it.startsWith(">>>>>>>")
    }
} catch (e: Exception) {
    false
}
