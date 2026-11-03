package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Publication safety for a public repository, run over the files git tracks (never build directories). Replaces
 * the old tests/test_scaffold.py, which walked every file on disk and so also scanned Gradle build output.
 */
class PublicationSafetyTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "AGENTS.md").isFile && File(it, "android").isDirectory }

    private val tracked: List<String> by lazy {
        val process = ProcessBuilder("git", "-C", root.path, "ls-files", "-z").redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "git ls-files failed: $out" }
        out.split('\u0000').filter { it.isNotEmpty() }
    }

    private val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "ttf", "jar")

    private fun textOf(path: String): String? {
        if (path.substringAfterLast('.', "").lowercase() in imageExtensions) return null
        val file = File(root, path)
        return if (file.isFile) runCatching { file.readText() }.getOrNull() else null
    }

    private fun scan(label: String, pattern: Regex, exempt: Set<String> = emptySet()) {
        val hits = tracked.filter { it !in exempt && it != SELF }.filter { path ->
            textOf(path)?.let { pattern.containsMatchIn(it) } == true
        }
        assertTrue("$label found in: $hits", hits.isEmpty())
    }

    @Test
    fun `tracked tree has no secret-shaped file names`() {
        val bad = Regex("""(?i)(^|/)(\.env($|\.)|id_rsa|credentials\.json|google-services\.json|.*\.(dpapi|pem|key|jks|keystore)|local\.properties)$""")
        val hits = tracked.filter { bad.containsMatchIn(it) && !it.endsWith(".env.example") }
        assertTrue("secret-shaped names: $hits", hits.isEmpty())
    }

    @Test
    fun `tracked tree has no keys or tokens`() {
        scan("Google API key", Regex("AIza[0-9A-Za-z_-]{35}"))
        scan("reCAPTCHA site key", Regex("6L[0-9A-Za-z_-]{38}"))
        scan("private key", Regex("-----BEGIN (RSA |OPENSSH |EC )?PRIVATE KEY-----"))
        scan("GitHub token", Regex("(ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9]{20,})"))
    }

    @Test
    fun `no personal e-mail addresses or non-empty debug tokens are tracked`() {
        scan("personal e-mail address", Regex("""[A-Za-z0-9._%+-]+@(yahoo|gmail|hotmail|outlook|icloud|live|proton)\.[a-z]{2,}""", RegexOption.IGNORE_CASE))
        scan("App Check debug token value", Regex("""(?m)^\s*FIREBASE_APPCHECK_DEBUG_TOKEN\s*[=:]\s*\S+"""))
        scan("debug token UUID", Regex("""(?i)debug[_ -]?token["']?\s*[:=]\s*["']?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"""))
    }

    @Test
    fun `tracked images are launcher icons or on the provenance allowlist`() {
        val raster = Regex("""(?i)\.(png|jpe?g|gif|webp)$""")
        val hits = tracked.filter { raster.containsMatchIn(it) && "/res/mipmap-" !in it && it !in IMAGE_ALLOWLIST }
        assertTrue("images without provenance: $hits (add to IMAGE_ALLOWLIST with a note, or remove)", hits.isEmpty())
    }

    @Test
    fun `no AI Studio working files, screenshots, databases or calendars are tracked`() {
        val bad = Regex("""(^|/)(\.aistudio/|\.tempmediaStorage|Screenshot_\d{8}_)|\.(ics|db|db-wal|db-shm)$""")
        val hits = tracked.filter { bad.containsMatchIn(it) }
        assertTrue("tracked working files: $hits", hits.isEmpty())
    }

    @Test
    fun `no false licence or human-voice claims`() {
        scan(
            "licence claim",
            Regex("""(?i)(human stem|certified human|verified human consent|rights cleared|public domain field)"""),
            // Removed with the voice-pack rebuild (M4b/M9); until then the legacy seed data still carries them.
            exempt = LEGACY_VOICE_CLAIM_FILES,
        )
    }

    @Test
    fun `no destructive migrations or debug-only features in main sources`() {
        val main = tracked.filter { it.contains("/src/main/") && it.endsWith(".kt") }
        val hits = main.filter { path ->
            textOf(path)?.let { "Simulate Missed Day" in it } == true
        }
        // Tracked as issues #9/#15; removed by the M4b cut-over. This list shrinks to empty, never grows.
        assertEquals(LEGACY_MAIN_FILES_WITH_SIMULATE_BUTTON, hits.toSet())
        assertTrue(
            "fallbackToDestructiveMigration must go with the M3 database: $LEGACY_DESTRUCTIVE",
            main.filter { p -> textOf(p)?.contains("fallbackToDestructiveMigration") == true }
                .toSet() == LEGACY_DESTRUCTIVE,
        )
    }

    @Test
    fun `app disables backup and keeps the debug App Check provider out of release`() {
        val manifest = File(root, "android/app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""android:allowBackup="false""""))
        val gradle = File(root, "android/app/build.gradle.kts").readText()
        assertFalse(
            "appcheck-debug must be debugImplementation only",
            Regex("""(?m)^\s*implementation\(libs\.firebase\.appcheck\.debug\)""").containsMatchIn(gradle),
        )
        assertTrue(gradle.contains("debugImplementation(libs.firebase.appcheck.debug)"))
        val releaseSources = tracked.filter { it.contains("/src/release/") && it.endsWith(".kt") }
        assertTrue(releaseSources.none { textOf(it)?.contains("DebugAppCheckProviderFactory") == true })
    }

    @Test
    fun `required project files exist and public docs carry a content security policy`() {
        listOf("README.md", "AGENTS.md", "LICENSE", ".gitignore", "docs/index.html", "docs/status.html", "docs/status.css")
            .forEach { rel ->
                val f = File(root, rel)
                assertTrue("missing or empty $rel", f.isFile && f.length() > 0)
            }
        // PRODUCT-SPEC.html is the owner's contract document and predates this check; every other page needs a CSP.
        tracked.filter { it.startsWith("docs/") && it.endsWith(".html") && it != "docs/PRODUCT-SPEC.html" }.forEach { rel ->
            val text = File(root, rel).readText()
            assertTrue("$rel lacks a CSP", text.contains("Content-Security-Policy") && text.contains("default-src 'none'"))
            assertTrue("$rel lacks lang", text.contains("""lang="en""""))
        }
        assertTrue(File(root, "LICENSE").readText().contains("All rights reserved"))
        val ignore = File(root, ".gitignore").readText()
        listOf(".env", "*.dpapi", "*.pem").forEach { assertTrue("gitignore lacks $it", ignore.contains(it)) }
    }

    private companion object {
        const val SELF = "android/app/src/test/java/com/example/PublicationSafetyTest.kt"
        val LEGACY_VOICE_CLAIM_FILES = setOf(
            // The design document quotes the claims it removes.
            "docs/RECONSTRUCTION-DESIGN.html",
            "android/app/src/main/java/com/example/data/CommandRepository.kt",
            "android/app/src/main/java/com/example/data/CommandModels.kt",
            "android/app/src/main/java/com/example/ui/screens/VoiceStudioScreen.kt",
            "android/app/src/test/java/com/example/ExampleRobolectricTest.kt",
        )
        // Provenance: AI Studio-generated placeholder art from the original import. All five go with the legacy UI in M4b.
        val IMAGE_ALLOWLIST = setOf(
            "android/app/src/main/res/drawable/drill_sergeant_avatar.jpg",
            "android/app/src/main/res/drawable/img_life_coach_avatar.png",
            "android/app/src/main/res/drawable/img_life_coach_frame1.png",
            "android/app/src/main/res/drawable/img_life_coach_frame2.png",
            "android/app/src/main/res/drawable/img_life_coach_frame3.png",
        )
        val LEGACY_MAIN_FILES_WITH_SIMULATE_BUTTON = setOf("android/app/src/main/java/com/example/ui/screens/StreakVaultScreen.kt")
        val LEGACY_DESTRUCTIVE = setOf("android/app/src/main/java/com/example/data/CommandDatabase.kt")
    }
}
