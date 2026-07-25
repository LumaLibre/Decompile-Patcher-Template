package dev.lumas.anvil.internal

import java.io.File
import org.gradle.api.logging.Logger

internal enum class ApplyResult { CLEAN, OFFSET, CONFLICT, REJECTED, FAILED }

internal data class ApplySummary(
    val outcomes: Map<String, ApplyResult>,
) {
    val clean get() = outcomes.values.count { it == ApplyResult.CLEAN }
    val offset get() = outcomes.values.count { it == ApplyResult.OFFSET }
    val needsHelp
        get() = outcomes.filterValues {
            it == ApplyResult.CONFLICT || it == ApplyResult.REJECTED || it == ApplyResult.FAILED
        }
}

/**
 * Applies every per-file patch onto a scratch git repo seeded with the pristine base, then copies
 * the results — conflict markers and all — back into the module sources.
 *
 * One patch per file means a failure is isolated to that file; everything else still lands.
 */
internal class PatchApplier(
    private val git: GitOperationsService,
    private val layout: PatchLayout,
    private val workDir: File,
    private val rejectsDir: File,
    private val logger: Logger,
) {

    fun apply(fuzzy: Boolean): ApplySummary {
        val patches = layout.allPatchFiles()
        if (patches.isEmpty()) {
            logger.lifecycle("ℹ️  No patches found under ${layout.patchesDir.name}/ — nothing to apply.")
            return ApplySummary(emptyMap())
        }

        if (rejectsDir.exists()) rejectsDir.deleteRecursively()

        logger.lifecycle("")
        logger.lifecycle("🔧 Applying ${patches.size} file patch(es)${if (fuzzy) " (fuzzy)" else ""}...")
        logger.lifecycle("=".repeat(60))

        val scratch = freshScratch(workDir)
        git.setupRepo(scratch)
        layout.layoutBaseInto(scratch)
        git.add(scratch)
        git.commit(scratch, "Pristine decompiled base")

        val outcomes = LinkedHashMap<String, ApplyResult>()

        patches.forEach { patch ->
            val canonical = layout.patchCanonical(patch)
            val result = applyOne(scratch, patch, canonical, fuzzy)
            outcomes[canonical] = result
            val icon = when (result) {
                ApplyResult.CLEAN -> "✓"
                ApplyResult.OFFSET -> "≈"
                ApplyResult.CONFLICT -> "⚠"
                ApplyResult.REJECTED, ApplyResult.FAILED -> "✗"
            }
            logger.lifecycle("  $icon ${result.name.lowercase().padEnd(8)} $canonical")
        }

        logger.lifecycle("")
        logger.lifecycle("📦 Writing patched files into module sources...")
        patches.forEach { patch ->
            val canonical = layout.patchCanonical(patch)
            val working = layout.canonicalToWorking(canonical) ?: return@forEach
            val fromScratch = scratch.resolve(canonical)
            if (fromScratch.exists()) {
                working.parentFile.mkdirs()
                fromScratch.copyTo(working, overwrite = true)
            } else if (working.exists()) {
                working.delete() // the patch deleted this file
            }
        }

        workDir.deleteRecursively()

        val summary = ApplySummary(outcomes)
        report(summary, fuzzy)
        return summary
    }

    /** Escalating ladder: straight apply → 3-way → optional fuzzy → salvage what we can as .rej. */
    private fun applyOne(scratch: File, patch: File, canonical: String, fuzzy: Boolean): ApplyResult {
        if (git.apply(scratch, patch).first == 0) return ApplyResult.CLEAN

        if (git.apply(scratch, patch, threeWay = true).first == 0) {
            val target = scratch.resolve(canonical)
            return if (target.exists() && hasConflictMarkers(target)) {
                ApplyResult.CONFLICT
            } else {
                ApplyResult.OFFSET
            }
        }

        if (fuzzy && git.patchFuzzy(scratch, patch, fuzz = 3).first == 0) return ApplyResult.OFFSET

        git.apply(scratch, patch, reject = true)
        val rejected = scratch.walkTopDown().filter { it.name.endsWith(".rej") }.toList()
        if (rejected.isEmpty()) return ApplyResult.FAILED

        rejected.forEach { rej ->
            val dest = rejectsDir.resolve(rej.invariantRelativeTo(scratch))
            dest.parentFile.mkdirs()
            rej.copyTo(dest, overwrite = true)
            rej.delete()
        }
        return ApplyResult.REJECTED
    }

    private fun report(summary: ApplySummary, fuzzy: Boolean) {
        val needsHelp = summary.needsHelp
        logger.lifecycle("")
        logger.lifecycle("=".repeat(60))
        logger.lifecycle(
            "✨ clean: ${summary.clean} | offset: ${summary.offset} | needs attention: ${needsHelp.size}"
        )

        if (needsHelp.isEmpty()) {
            logger.lifecycle("ℹ️  All patches applied without conflicts.")
            return
        }

        logger.lifecycle("")
        logger.lifecycle("👋 Human intervention needed — these did not apply cleanly:")
        needsHelp.forEach { (canonical, res) ->
            val working = layout.canonicalToWorking(canonical)
            when (res) {
                ApplyResult.CONFLICT ->
                    logger.lifecycle("  ⚠ conflict markers in: ${working ?: canonical}")
                ApplyResult.REJECTED ->
                    logger.lifecycle(
                        "  ✗ rejected hunks:       ${rejectsDir.name}/$canonical.rej  " +
                            "(partial result in module)"
                    )
                else -> logger.lifecycle("  ✗ failed:               $canonical")
            }
        }
        logger.lifecycle("")
        logger.lifecycle("  1) Open the files above and resolve the markers / apply the .rej hunks.")
        logger.lifecycle("  2) Run './gradlew rebuildFilePatches' to regenerate clean patches from your fixes.")
        if (!fuzzy) logger.lifecycle("  3) Or retry with fuzzier matching: './gradlew applyFilePatches --fuzzy'")
    }
}

internal fun freshScratch(workDir: File): File {
    if (workDir.exists()) workDir.deleteRecursively()
    workDir.mkdirs()
    return workDir
}
