package dev.lumas.anvil.task

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

abstract class AnvilHelpTask : DefaultTask() {

    @TaskAction
    fun printHelp() {
        HELP.trimIndent().lines().forEach { logger.quiet(it) }
    }

    private companion object {
        // from patcher.sh
        const val HELP = """

            Anvil // per-file patches
            ============================================================

            Getting started
              setup              Decompile + distribute base + apply all file patches
              fresh              Wipe everything and rebuild from scratch (--force)
              inspect            Show the decompiled package structure

            Every day
              status             Show which module files differ from the base
              rebuild            Regenerate per-file patches from your module edits
              list               List the per-file patches

            Reconstructing
              applyPatches       Module sources = base + all patches
              applyOnly          Overlay patches without re-distributing the base
              reset              Discard module edits; rebuild from base + patches (--force)

            Cleaning
              cleanDistributedSources   Module sources only (--force)
              cleanGenerated            The decompiled base only
              cleanPatchWork            Ephemeral work dirs + .patch-rejects
              cleanAll                  All three

            Edit / add code
              1) Edit files in the module src tree, or add new ones
              2) './gradlew status' to see what changed
              3) './gradlew rebuild' to update the patch tree

            Conflict workflow (one patch per file, so failures are isolated)
              1) './gradlew applyPatches' reports the files needing attention
                   - conflict markers are left directly in the module file
                   - rejected hunks are written to .patch-rejects/<path>.rej
              2) Fix those files in your IDE
              3) './gradlew rebuild' regenerates clean patches from your fixes

              Looser matching:  './gradlew applyFilePatches --fuzzy'
                                './gradlew applyPatches -Panvil.fuzzy'

            Destructive tasks refuse to run when the module sources hold edits that
            are not in the patch tree. Run 'rebuild' first, or pass --force.

            ============================================================
        """
    }
}
