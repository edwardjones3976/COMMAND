package com.command

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Everything a person reads lives in string resources, so it can be translated and checked in one place. This scans the
 * app's own sources for text typed straight into a composable: a literal as the first argument of Text(...), or as the value
 * of a label, title, placeholder, hint or description argument.
 */
class NoHardcodedComposeStringsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "android").isDirectory }

    private val sources: List<File> = File(root, "android/app/src/main/java/com/command").walkTopDown().filter { it.extension == "kt" }.toList()

    private val literalText = Regex("""\bText\(\s*"""")
    private val literalArgument = Regex("""\b(label|title|placeholder|hint|contentDescription|supportingText|text)\s*=\s*(\{\s*)?"""")

    private fun code(file: File): List<Pair<Int, String>> =
        file.readLines().mapIndexed { i, line -> (i + 1) to line }
            .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

    @Test
    fun `the app sources are found`() {
        assertTrue("expected the app's own sources under com/command", sources.size >= 10)
    }

    @Test
    fun `no text a person reads is typed into a composable`() {
        val hits = sources.flatMap { file ->
            code(file).filter { (_, line) -> literalText.containsMatchIn(line) || literalArgument.containsMatchIn(line) }
                .map { (n, line) -> "${file.name}:$n: ${line.trim()}" }
        }
        assertTrue("move these into string resources:\n${hits.joinToString("\n")}", hits.isEmpty())
    }
}
