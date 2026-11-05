package com.command

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The first screen tells a new person that everything stays on the phone unless they turn on an optional feature that says
 * otherwise. Until the features that talk to the network arrive (each behind its own consent, with its own test), nothing the
 * app runs may open a connection, so this scans the code that runs for the ways of doing so. That includes the voice engine in :voice:
 * its one way to Gemini is an interface, and the Firebase side of it is in :cloud, which the app does not depend on yet.
 */
class NoNetworkInRuntimeCodeTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "android").isDirectory }

    private val runtimeSources: List<File> = listOf(
        "android/app/src/main/java/com/command",
        "android/alarm/src/main",
        "android/data/src/main",
        "android/domain/src/main",
        "android/voice/src/main",
    ).flatMap { dir -> File(root, dir).walkTopDown().filter { it.extension == "kt" }.toList() }

    private val ways = Regex(
        """\b(java\.net\.|javax\.net\.|okhttp3|retrofit2|HttpURLConnection|HttpsURLConnection|URLConnection|WebView|DownloadManager|""" +
            """com\.google\.firebase|FirebaseApp|Firebase\.|Socket\()""",
    )

    @Test
    fun `the code that runs is found`() {
        assertTrue("expected the app's own sources, found ${runtimeSources.size}", runtimeSources.size >= 40)
    }

    @Test
    fun `none of it opens a network connection`() {
        val hits = runtimeSources.flatMap { file ->
            file.readLines().mapIndexed { i, line -> Triple(file, i + 1, line) }
                .filterNot { (_, _, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .filter { (_, _, line) -> ways.containsMatchIn(line) }
                .map { (file, n, line) -> "${file.name}:$n: ${line.trim()}" }
        }
        assertTrue("a way to reach the network was added; it needs its own consent and test first:\n${hits.joinToString("\n")}", hits.isEmpty())
    }
}
