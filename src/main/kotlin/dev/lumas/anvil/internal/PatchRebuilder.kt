package dev.lumas.anvil.internal

import java.io.File
import org.gradle.api.logging.Logger

internal data class RebuildSummary(
    val created: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val unchanged: Int = 0,
) {
    val hasWork get() = created + updated + removed > 0
}

/**
 * Diffs the module sources against the pristine base and reconciles the patch tree with the
 * result: new diffs become patches, changed diffs overwrite theirs, and patches whose file no
 * longer differs get pruned.
 *
 * `patchStatus` runs exactly this logic with [dryRun] set, so the two can never disagree.
 */
internal class PatchRebuilder(
    private val git: GitOperationsService,
    private val layout: PatchLayout,
    private val workDir: File,
    private val logger: Logger,
) {

    fun run(dryRun: Boolean): RebuildSummary {
        val scratch = freshScratch(workDir)
        git.setupRepo(scratch)
        layout.layoutBaseInto(scratch)
        git.add(scratch)
        git.commit(scratch, "Pristine decompiled base")

        layout.layoutWorkingInto(scratch)
        git.add(scratch)

        val changed = git.changedPaths(scratch)
        val changedSet = changed.toSet()
        val tmp = scratch.resolve(".anvil-diff.tmp")

        var created = 0
        var updated = 0
        var unchanged = 0

        changed.forEach { canonical ->
            git.writeFileDiff(scratch, canonical, tmp)
            val fresh = if (tmp.exists()) tmp.readBytes() else ByteArray(0)
            val patchFile = layout.patchFileFor(canonical)
            when {
                !patchFile.exists() -> {
                    if (!dryRun) {
                        patchFile.parentFile.mkdirs()
                        tmp.copyTo(patchFile, overwrite = true)
                    }
                    logger.lifecycle(if (dryRun) "  ➕ new patch:  $canonical" else "  ➕ created:  $canonical.patch")
                    created++
                }
                !patchFile.readBytes().contentEquals(fresh) -> {
                    if (!dryRun) tmp.copyTo(patchFile, overwrite = true)
                    logger.lifecycle(if (dryRun) "  ✏️  updated:    $canonical" else "  ✏️  updated:  $canonical.patch")
                    updated++
                }
                else -> unchanged++ // identical diff — leave the file untouched
            }
        }

        var removed = 0
        layout.allPatchFiles().forEach { patch ->
            val canonical = layout.patchCanonical(patch)
            if (canonical !in changedSet) {
                if (!dryRun) patch.delete()
                logger.lifecycle(
                    if (dryRun) "  🗑️  stale:      $canonical (would be removed)"
                    else "  🗑 removed:  $canonical.patch"
                )
                removed++
            }
        }

        if (!dryRun) pruneEmptyDirs(layout.patchesDir)
        workDir.deleteRecursively()

        return RebuildSummary(created, updated, removed, unchanged)
    }
}
