package com.command.domain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The domain must stay portable and testable: no Android, no dependency-injection framework, and no reading of
 * the system clock or zone except in the one file that wraps them. (API-24 safety of the JDK calls it does make is
 * enforced by the compiler: the module builds with -Xjdk-release=1.8.)
 */
class DomainPurityTest {

    private val sources: List<File> by lazy {
        val dir = File("src/main/kotlin")
        assertTrue("run from the :domain module directory, got ${File("").absolutePath}", dir.isDirectory)
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun scan(label: String, pattern: Regex, exempt: (File) -> Boolean = { false }) {
        val hits = sources.filter { !exempt(it) && pattern.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("$label found in: $hits", hits.isEmpty())
    }

    @Test
    fun `the domain has sources`() {
        assertTrue(sources.size >= 10)
    }

    @Test
    fun `no android or injection imports`() {
        scan("platform import", Regex("""(?m)^\s*import\s+(android|androidx|dagger|javax\.inject|com\.google)\."""))
    }

    @Test
    fun `nothing reads the clock or the zone except the Clock wrapper`() {
        scan(
            "direct clock access",
            Regex("""System\.currentTimeMillis\(|System\.nanoTime\(|Instant\.now\(\)|LocalDate\.now\(\)|LocalDateTime\.now\(\)|ZonedDateTime\.now\(\)|ZoneId\.systemDefault\(|Clock\.systemUTC\(|Clock\.systemDefaultZone\("""),
            exempt = { it.path.replace('\\', '/').endsWith("time/Clock.kt") },
        )
    }

    @Test
    fun `no printing or logging`() {
        scan("console output", Regex("""\bprintln\(|\bprint\(|System\.out|System\.err"""))
    }
}
