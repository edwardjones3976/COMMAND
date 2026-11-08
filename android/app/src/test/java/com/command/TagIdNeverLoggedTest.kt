package com.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A tag's id is turned into a fingerprint on the reader thread and nothing after that sees it. This scans the files the NFC path runs
 * through for any way of writing to a log, and the tags screen for anything kept across a rotation that could hold a read or a
 * fingerprint. It is a weak guard: the redaction tests on TagId, TagRead.Seen and the saved state carry the real weight.
 */
class TagIdNeverLoggedTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "android").isDirectory }

    private val nfcPath = listOf(
        "android/app/src/main/java/com/command/reveille/NfcReader.kt",
        "android/app/src/main/java/com/command/handshake/NfcStatus.kt",
        "android/domain/src/main/kotlin/com/command/domain/handshake/TagId.kt",
        "android/domain/src/main/kotlin/com/command/domain/handshake/TokenHash.kt",
        "android/data/src/main/kotlin/com/command/data/TokenRepository.kt",
        "android/app/src/main/java/com/command/handshake/RegisterTokenViewModel.kt",
        "android/app/src/main/java/com/command/handshake/RegisterTokenScreen.kt",
        "android/app/src/main/java/com/command/reveille/ReveilleViewModel.kt",
        "android/app/src/main/java/com/command/reveille/ReveilleScreens.kt",
        "android/app/src/main/java/com/command/ReveilleActivity.kt",
        "android/app/src/main/java/com/command/reveille/HandshakeScreens.kt",
    ).map { File(root, it) }

    /** Every main source file of the app, the domain and the data that names a read or an id: each must be on the tag path above. */
    private val mainSources = listOf("android/app/src/main", "android/domain/src/main", "android/data/src/main")
        .flatMap { dir -> File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val logging = Regex("""\b(Log\.[a-zA-Z]+\(|println\(|print\(|Timber\b|System\.(out|err)\b|printStackTrace\()""")

    private fun code(file: File) = file.readLines().mapIndexed { i, line -> i + 1 to line }
        .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }

    @Test
    fun `every file on the tag path is found`() {
        val missing = nfcPath.filterNot { it.isFile }.map { it.path }
        assertEquals("files named here were moved or renamed", emptyList<String>(), missing)
    }

    @Test
    fun `every file that handles a read or an id is on the tag path`() {
        val handles = Regex("""\b(TagRead|TagId)\b""")
        val listed = nfcPath.map { it.canonicalFile }.toSet()
        val missing = mainSources.filter { file -> code(file).any { (_, line) -> handles.containsMatchIn(line) } }
            .filterNot { it.canonicalFile in listed }
            .map { it.relativeTo(root).invariantSeparatorsPath }
        assertEquals("these handle a TagRead or a TagId and are not scanned for logs", emptyList<String>(), missing)
    }

    @Test
    fun `nothing on the tag path writes to a log`() {
        val hits = nfcPath.flatMap { file ->
            code(file).filter { (_, line) -> logging.containsMatchIn(line) }.map { (n, line) -> "${file.name}:$n: ${line.trim()}" }
        }
        assertTrue("a log call on the tag path could carry an id or a fingerprint:\n${hits.joinToString("\n")}", hits.isEmpty())
    }

    @Test
    fun `nothing the tags screen keeps across a rotation holds a read or a fingerprint`() {
        val screen = File(root, "android/app/src/main/java/com/command/handshake/RegisterTokenScreen.kt")
        val forbidden = Regex("""TagRead|hash|Hash|pendingFirst""")
        val hits = code(screen).filter { (_, line) -> "rememberSaveable" in line && forbidden.containsMatchIn(line) }
            .map { (n, line) -> "$n: ${line.trim()}" }
        assertTrue("saved state on the tags screen must hold only names, counters and switches:\n${hits.joinToString("\n")}", hits.isEmpty())
        assertTrue("the screen keeps its state with rememberSaveable", code(screen).any { (_, line) -> "rememberSaveable" in line })
    }
}
