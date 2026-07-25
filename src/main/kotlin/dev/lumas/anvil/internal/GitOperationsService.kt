package dev.lumas.anvil.internal

import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.process.ExecOperations

/**
 * Thin wrapper around the `git` and `patch` command-line tools.
 *
 * Lives as a [BuildService] so process execution goes through [ExecOperations] rather than
 * `Project.exec`, which keeps every task that uses it configuration-cache compatible.
 */
abstract class GitOperationsService @Inject constructor(
    private val execOps: ExecOperations,
) : BuildService<BuildServiceParameters.None> {

    private fun run(
        workingDir: File,
        vararg args: String,
        ignoreExit: Boolean = true,
        stdin: File? = null,
    ): Pair<Int, String> {
        val out = ByteArrayOutputStream()
        val result = execOps.exec {
            this.workingDir = workingDir
            commandLine(*args)
            standardOutput = out
            errorOutput = out
            isIgnoreExitValue = ignoreExit
            if (stdin != null) standardInput = stdin.inputStream()
        }
        return result.exitValue to out.toString()
    }

    fun setupRepo(workingDir: File) {
        run(workingDir, "git", "init")
        run(workingDir, "git", "config", "user.name", "Anvil")
        run(workingDir, "git", "config", "user.email", "anvil@local")
        run(workingDir, "git", "config", "commit.gpgsign", "false")
        run(workingDir, "git", "config", "core.autocrlf", "false")
    }

    fun add(workingDir: File, path: String = "-A") = run(workingDir, "git", "add", path)

    fun commit(workingDir: File, message: String): Int =
        run(workingDir, "git", "commit", "--no-gpg-sign", "-q", "-m", message).first

    /** Paths (forward-slash, repo-relative) that differ between the index and HEAD. */
    fun changedPaths(workingDir: File): List<String> {
        val (_, out) = run(workingDir, "git", "diff", "--cached", "--no-renames", "--name-only")
        return out.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Writes a single-file unified diff (with git index headers) for [path]. */
    fun writeFileDiff(workingDir: File, path: String, outputFile: File) {
        outputFile.parentFile.mkdirs()
        outputFile.outputStream().use { stream ->
            execOps.exec {
                this.workingDir = workingDir
                commandLine("git", "diff", "--cached", "--no-renames", "--", path)
                standardOutput = stream
            }
        }
    }

    /** `git apply` with the given flags. Returns (exitCode, combinedOutput). */
    fun apply(
        workingDir: File,
        patchFile: File,
        threeWay: Boolean = false,
        reject: Boolean = false,
    ): Pair<Int, String> {
        val args = mutableListOf("git", "apply", "--whitespace=nowarn")
        if (threeWay) args.add("--3way")
        if (reject) args.add("--reject")
        args.add(patchFile.absolutePath)
        return run(workingDir, *args.toTypedArray())
    }

    /** Fuzzy fallback using the POSIX `patch` tool. Requires `patch` on PATH. */
    fun patchFuzzy(workingDir: File, patchFile: File, fuzz: Int): Pair<Int, String> = try {
        run(
            workingDir,
            "patch", "-p1", "--fuzz=$fuzz", "--no-backup-if-mismatch", "--forward",
            stdin = patchFile,
        )
    } catch (e: Exception) {
        -1 to "patch tool unavailable: ${e.message}"
    }
}
