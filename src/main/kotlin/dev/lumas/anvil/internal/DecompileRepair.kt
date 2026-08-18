package dev.lumas.anvil.internal

import java.io.ByteArrayOutputStream
import java.io.File
import org.gradle.api.logging.Logger
import org.gradle.process.ExecOperations

private const val CLASS_MARKER = $$"$VF: Unable to decompile class"
private const val METHOD_MARKER = $$"$VF: Couldn't be decompiled"

private const val STUB_MESSAGE = "Anvil stub: this could not be decompiled"

internal data class RepairReport(
    val stubbedClasses: List<String> = emptyList(),
    val repairedMethods: Map<String, Int> = emptyMap(),
    val unrepairable: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = stubbedClasses.isEmpty() && repairedMethods.isEmpty() && unrepairable.isEmpty()
}

/**
 * Repairs decompiler output that is not valid Java.
 */
internal class DecompileRepair(
    private val generatedDir: File,
    private val inputJar: File,
    private val javap: File?,
    private val execOps: ExecOperations,
    private val logger: Logger,
) {

    fun repair(): RepairReport {
        val stubbed = mutableListOf<String>()
        val methods = linkedMapOf<String, Int>()
        val unrepairable = mutableListOf<String>()

        generatedDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .sortedBy { it.path }
            .forEach { file ->
                val text = file.readText()
                val rel = file.invariantRelativeTo(generatedDir)
                when {
                    CLASS_MARKER in text -> {
                        val stub = buildClassStub(file, text)
                        if (stub == null) {
                            unrepairable += rel
                        } else {
                            file.writeText(stub)
                            stubbed += rel
                        }
                    }
                    METHOD_MARKER in text -> {
                        val (repaired, count) = repairMethodBodies(text)
                        if (count > 0) {
                            file.writeText(repaired)
                            methods[rel] = count
                        } else {
                            unrepairable += rel
                        }
                    }
                }
            }

        return RepairReport(stubbed, methods, unrepairable)
    }

    /**
     * Inserts a throwing statement into every body that is nothing but the failure comment.
     *
     * Only touches bodies whose remaining lines are all comments — a body with real statements
     * could already end in `return`, where an appended `throw` would be unreachable code.
     */
    private fun repairMethodBodies(text: String): Pair<String, Int> {
        val lines = text.lines().toMutableList()
        var count = 0
        var i = 0
        while (i < lines.size) {
            if (METHOD_MARKER !in lines[i]) {
                i++
                continue
            }
            var close = i + 1
            while (close < lines.size && lines[close].trim() != "}") close++
            if (close >= lines.size || !lines.subList(i + 1, close).all { it.isCommentOrBlank() }) {
                i++
                continue
            }
            val indent = lines[close].takeWhile { it == ' ' } + "   "
            lines.add(close, "${indent}throw new UnsupportedOperationException(\"$STUB_MESSAGE\");")
            count++
            i = close + 2
        }
        return lines.joinToString("\n") to count
    }

    private fun String.isCommentOrBlank(): Boolean {
        val t = trim()
        return t.isEmpty() || t.startsWith("//") || t.startsWith("/*") || t.startsWith("*")
    }

    private fun buildClassStub(file: File, original: String): String? {
        val javapTool = javap ?: return null
        val binaryName = file.invariantRelativeTo(generatedDir)
            .removeSuffix(".java")
            .replace('/', '.')

        val declarations = runJavap(javapTool, binaryName) ?: return null
        val parsed = JavapClass.parse(declarations, binaryName) ?: return null
        return parsed.render(reason = original.failureReason())
    }

    private fun runJavap(javapTool: File, binaryName: String): String? {
        val out = ByteArrayOutputStream()
        val result = try {
            execOps.exec {
                commandLine(javapTool.absolutePath, "-p", "-cp", inputJar.absolutePath, binaryName)
                standardOutput = out
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        } catch (e: Exception) {
            logger.info("Anvil: javap failed for $binaryName: ${e.message}")
            return null
        }
        if (result.exitValue != 0) return null
        return out.toString().ifBlank { null }
    }

    private fun String.failureReason(): String =
        lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("/*") && CLASS_MARKER !in it && !it.startsWith("Please report") }
            ?.take(200)
            ?: "unknown decompiler error"
}

/**
 * A javap class
 */
internal class JavapClass(
    private val binaryName: String,
    private val header: String,
    private val members: List<String>,
) {

    private val simpleName = binaryName.substringAfterLast('.')
    private val packageName = binaryName.substringBeforeLast('.', "")
    private val isInterface = header.contains(" interface ")
    private val isAbstract = header.contains(" abstract ")

    private val isEnum = header.contains("extends java.lang.Enum<")

    fun render(reason: String): String = buildString {
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName;")
            appendLine()
        }
        appendLine("// Anvil: Vineflower could not decompile this class; the stub below was")
        appendLine("// reconstructed from the input jar's bytecode so dependents still compile.")
        appendLine("// $reason")
        if (isEnum) renderEnum() else renderType()
    }

    private fun StringBuilder.renderType() {
        appendLine("${header.withSimpleName()} {")
        members.forEach { member ->
            renderMember(member)?.let { appendLine("   $it") }
        }
        appendLine("}")
    }

    private fun StringBuilder.renderEnum() {
        val constants = members.mapNotNull { it.enumConstantName() }
        val modifiers = header.substringBefore(" class ").trim()
        appendLine("$modifiers enum $simpleName {")
        if (constants.isEmpty()) {
            appendLine("   ;")
        } else {
            appendLine(constants.joinToString(",\n") { "   $it" } + ";")
        }
        members.filter { it.isCallable() && !it.isSyntheticEnumMember() && !it.isConstructor() }
            .forEach { member -> renderMember(member)?.let { appendLine("   $it") } }
        appendLine("}")
    }

    private fun String.enumConstantName(): String? {
        if (isCallable() || !startsWith("public static final ")) return null
        val name = removeSuffix(";").substringAfterLast(' ')
        return name.takeIf { it.isNotEmpty() && !it.startsWith("$") && it.all { c -> c.isJavaIdentifier() } }
    }

    private fun renderMember(raw: String): String? {
        val member = raw.removeSuffix(";").trim()
        if (member.isEmpty() || member == "static {}") return null
        return if (member.isCallable()) renderCallable(member) else renderField(member)
    }

    private fun renderField(member: String): String? {
        val name = member.substringAfterLast(' ')
        if (name.startsWith("$") || !name.all { it.isJavaIdentifier() }) return null
        val type = member.dropLast(name.length).trim()

        if (isInterface) {
            val bareType = type.split(" ").last()
            return "$type $name = ${bareType.defaultValue()};".qualifyNested()
        }
        return "${type.replace(Regex("\\bfinal\\b"), "").trim()} $name;".qualifyNested()
    }

    private fun renderCallable(member: String): String? {
        val open = member.indexOf('(')
        val close = member.lastIndexOf(')')
        if (open !in 0..close) return null

        val head = member.substring(0, open).trim()
        val name = head.substringAfterLast(' ')
        if (name.contains("$") && name != binaryName) return null

        val constructor = name == binaryName
        val displayName = if (constructor) simpleName else name
        if (!constructor && !name.all { it.isJavaIdentifier() }) return null

        val prefix = head.dropLast(name.length).trim()
        val params = splitParameters(member.substring(open + 1, close))
            .mapIndexed { index, type -> "$type arg$index" }
            .joinToString(", ")
        val tail = member.substring(close + 1).trim()

        val signature = listOf(prefix, displayName).filter { it.isNotEmpty() }.joinToString(" ") +
            "($params)" + if (tail.isEmpty()) "" else " $tail"

        val abstract = prefix.contains("abstract") || prefix.contains("native") ||
            (isInterface && !prefix.contains("default") && !prefix.contains("static"))
        val body = if (abstract) ";" else " { throw new UnsupportedOperationException(\"$STUB_MESSAGE\"); }"
        return (signature + body).qualifyNested()
    }

    private fun splitParameters(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        raw.forEach { c ->
            when {
                c == '<' -> { depth++; current.append(c) }
                c == '>' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { out += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out
    }

    private fun String.isCallable() = contains('(')

    private fun String.isConstructor() = substringBefore('(').trim().endsWith(binaryName)

    private fun String.isSyntheticEnumMember(): Boolean {
        val head = substringBefore('(')
        return head.contains("\$values") || head.endsWith(" values") || head.endsWith(" valueOf")
    }

    private fun String.withSimpleName(): String =
        replace(Regex("\\b" + Regex.escape(binaryName) + "\\b"), simpleName)

    private fun String.qualifyNested(): String = replace('$', '.')

    private fun String.defaultValue(): String = when (this) {
        "boolean" -> "false"
        "char" -> "'\\0'"
        "byte", "short", "int" -> "0"
        "long" -> "0L"
        "float" -> "0.0f"
        "double" -> "0.0d"
        else -> "null"
    }

    private fun Char.isJavaIdentifier() = isLetterOrDigit() || this == '_' || this == '$'

    companion object {
        fun parse(javapOutput: String, binaryName: String): JavapClass? {
            val lines = javapOutput.lines().map { it.trim() }.filter { it.isNotEmpty() }
            val headerIndex = lines.indexOfFirst { it.endsWith("{") }
            if (headerIndex < 0) return null
            val header = lines[headerIndex].removeSuffix("{").trim()
            val members = lines.drop(headerIndex + 1).filter { it != "}" }
            return JavapClass(binaryName, header, members)
        }
    }
}
