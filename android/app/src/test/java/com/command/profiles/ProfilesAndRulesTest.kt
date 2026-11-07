package com.command.profiles

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.command.data.ActivityRuleRepository
import com.command.data.AppSettings
import com.command.data.ProfileRepository
import com.command.data.ProfileResolver
import com.command.data.db.ActivityProfileRow
import com.command.data.db.CommandDb
import com.command.domain.model.SettingKeys
import com.command.data.db.SettingRow
import com.command.testing.android.awaitUntil
import com.command.testing.android.testMission
import com.command.ui.theme.CommandTheme
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import javax.inject.Inject

/** The list of profiles, which sets the active one, and the editor of `activity_profiles`, through their view models and the real database. */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [36], qualifiers = "w411dp-h4000dp-xhdpi")
class ProfilesAndRulesTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createComposeRule()

    @Inject lateinit var profiles: ProfileRepository
    @Inject lateinit var rules: ActivityRuleRepository
    @Inject lateinit var settings: AppSettings
    @Inject lateinit var db: CommandDb

    private val openedDials = ArrayList<String>()
    private val openedVoice = ArrayList<String>()
    private val openedHandshake = ArrayList<String>()
    private var openedRules = 0

    @Before fun setUp() = hilt.inject()

    private fun exists(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(tag: String) = compose.waitUntil(timeoutMillis = 45_000) { exists(tag) }
    private fun waitGone(tag: String) = compose.waitUntil(timeoutMillis = 45_000) { !exists(tag) }

    private fun showProfiles() {
        val vm = ProfilesViewModel(profiles)
        compose.setContent {
            CommandTheme { ProfilesRoute(onBack = {}, onOpenDials = { openedDials += it }, onOpenHandshake = { openedHandshake += it }, onOpenRules = { openedRules++ }, onOpenVoice = { openedVoice += it }, viewModel = vm) }
        }
        waitFor(ProfilesTags.LIST)
    }

    private fun showRules(): ActivityRulesViewModel {
        val vm = ActivityRulesViewModel(rules, profiles)
        compose.setContent { CommandTheme { ActivityRulesRoute(onBack = {}, viewModel = vm) } }
        waitFor(RulesTags.LIST)
        return vm
    }

    private fun storedRules() = runBlocking { db.activityProfiles().all() }

    private fun activeSetting() = runBlocking { db.settings().get(SettingKeys.ACTIVE_PROFILE_ID) }

    private fun selected(id: String) = runCatching { compose.onNodeWithTag(ProfilesTags.active(id)).assertIsSelected() }.isSuccess

    @Test
    fun `choosing a profile makes it the active one, one at a time, and the next mission on Automatic rings with it`() {
        showProfiles()
        compose.onNodeWithTag(ProfilesTags.active("builtin_steady")).assertIsSelected()

        compose.onNodeWithTag(ProfilesTags.active("builtin_command")).performClick()

        compose.waitUntil(timeoutMillis = 45_000) { selected("builtin_command") }
        compose.onNodeWithTag(ProfilesTags.active("builtin_steady")).assertIsNotSelected()
        assertEquals("\"builtin_command\"", activeSetting())
        assertEquals("builtin_command", runBlocking { ProfileResolver(db).resolve(testMission()) }?.id)
    }

    /** A view model as the destination holds it: clearing the store is what leaving the screen does to it. */
    private inline fun <reified T : ViewModel> ViewModelStore.holding(noinline make: () -> T): T =
        ViewModelProvider(this, viewModelFactory { initializer { make() } })[T::class.java]

    @Test
    fun `each choice of the active profile is named for its profile, to a screen reader and to a voice`() {
        showProfiles()
        val steady = runBlocking { profiles.get("builtin_steady")!!.name }
        val command = runBlocking { profiles.get("builtin_command")!!.name }

        compose.onNodeWithTag(ProfilesTags.active("builtin_steady")).assertContentDescriptionEquals("$steady is active")
        compose.onNodeWithTag(ProfilesTags.active("builtin_command")).assertContentDescriptionEquals("Make $command active")
    }

    @Test
    fun `leaving the screen does not lose a profile that was just chosen`() {
        val store = ViewModelStore()
        val held = store.holding { ProfilesViewModel(profiles) }

        held.activate("builtin_command")
        store.clear()

        awaitUntil(message = "the choice to be written") { activeSetting() == "\"builtin_command\"" }
    }

    @Test
    fun `leaving the screen does not lose a rule being saved, or one being deleted`() {
        val store = ViewModelStore()
        val held = store.holding { ActivityRulesViewModel(rules, profiles) }
        val gate = CompletableDeferred<Unit>()
        held.beforeWrite = { gate.await() }
        runBlocking { db.activityProfiles().upsert(ActivityProfileRow("old", "Home", null, "builtin_nudge")) }
        held.startNew()
        held.setCategory("Work")
        held.setProfile("builtin_nudge")

        held.save()
        held.delete("old")
        store.clear()
        gate.complete(Unit)

        awaitUntil(message = "the new rule to be written and the old one to go") { storedRules().map { it.matchCategory } == listOf("Work") }
    }

    @Test
    fun `each profile says whether alarms use it, as it changes`() {
        showProfiles()
        compose.onNodeWithTag(ProfilesTags.inUse("builtin_command")).assertTextEquals("No alarm uses this profile yet.")
        compose.onNodeWithTag(ProfilesTags.inUse("builtin_steady")).assertTextEquals("Your alarms use this profile.")

        compose.onNodeWithTag(ProfilesTags.active("builtin_command")).performClick()

        compose.waitUntil(timeoutMillis = 45_000) { selected("builtin_command") }
        compose.waitUntil(timeoutMillis = 45_000) {
            runCatching { compose.onNodeWithTag(ProfilesTags.inUse("builtin_command")).assertTextEquals("Your alarms use this profile.") }.isSuccess
        }
    }

    @Test
    fun `a setting that names no profile is said to, and no profile is shown as active`() {
        runBlocking { db.settings().put(SettingRow(SettingKeys.ACTIVE_PROFILE_ID, "\"gone\"")) }
        showProfiles()

        waitFor(ProfilesTags.NONE_ACTIVE)
        compose.onNodeWithTag(ProfilesTags.active("builtin_steady")).assertIsNotSelected()

        compose.onNodeWithTag(ProfilesTags.active("builtin_steady")).performClick()
        waitGone(ProfilesTags.NONE_ACTIVE)
    }

    @Test
    fun `each profile opens its own dials and its own handshake, and the rules are one tap away`() {
        showProfiles()

        compose.onNodeWithTag(ProfilesTags.dials("builtin_nudge")).performClick()
        compose.onNodeWithTag(ProfilesTags.handshake("builtin_command")).performClick()
        compose.onNodeWithTag(ProfilesTags.RULES).performClick()

        assertEquals(listOf("builtin_nudge"), openedDials)
        assertEquals(listOf("builtin_command"), openedHandshake)
        assertEquals(1, openedRules)
        assertEquals("opening does not change who is active", "\"builtin_steady\"", activeSetting())
    }

    @Test
    fun `a rule written on the screen is stored, listed, and what the ring reads for a mission it fits`() {
        showRules()
        waitFor(RulesTags.EMPTY)

        compose.onNodeWithTag(RulesTags.ADD).performClick()
        waitFor(RulesTags.FORM)
        compose.onNodeWithTag(RulesTags.CATEGORY).performTextInput("Work")
        compose.onNodeWithTag(RulesTags.profile("builtin_command")).performClick()
        compose.onNodeWithTag(RulesTags.SAVE).performClick()

        waitFor(RulesTags.LIST)
        val row = storedRules().single()
        assertEquals("Work", row.matchCategory)
        assertEquals("builtin_command", row.profileId)
        waitFor(RulesTags.rule(row.id))
        assertEquals("builtin_command", runBlocking { ProfileResolver(db).resolve(testMission()) }?.id) // testMission's category is Work
    }

    @Test
    fun `a rule that cannot be saved stays on the form with what is wrong, and leaves when it is put right`() {
        showRules()
        compose.onNodeWithTag(RulesTags.ADD).performClick()
        waitFor(RulesTags.FORM)

        compose.onNodeWithTag(RulesTags.SAVE).performClick()

        waitFor(RulesTags.PROBLEM_NOTHING)
        waitFor(RulesTags.PROBLEM_PROFILE)
        assertTrue(storedRules().isEmpty())

        compose.onNodeWithTag(RulesTags.WORDS).performTextInput("run")
        waitGone(RulesTags.PROBLEM_NOTHING)
        compose.onNodeWithTag(RulesTags.profile("builtin_nudge")).performClick()
        waitGone(RulesTags.PROBLEM_PROFILE)
        compose.onNodeWithTag(RulesTags.SAVE).performClick()

        waitFor(RulesTags.LIST)
        assertEquals("run", storedRules().single().matchTitleContains)
    }

    @Test
    fun `a rule that asks for the same as another says so`() {
        runBlocking { db.activityProfiles().upsert(ActivityProfileRow("r", "Work", null, "builtin_nudge")) }
        showRules()
        compose.onNodeWithTag(RulesTags.ADD).performClick()
        waitFor(RulesTags.FORM)
        compose.onNodeWithTag(RulesTags.CATEGORY).performTextInput("work")
        compose.onNodeWithTag(RulesTags.profile("builtin_command")).performClick()

        compose.onNodeWithTag(RulesTags.SAVE).performClick()

        waitFor(RulesTags.PROBLEM_SAME)
        assertEquals(1, storedRules().size)
    }

    @Test
    fun `editing a rule shows what it asks for and writes the change to that rule`() {
        runBlocking { db.activityProfiles().upsert(ActivityProfileRow("r", "Work", "report", "builtin_nudge")) }
        showRules()
        waitFor(RulesTags.edit("r"))

        compose.onNodeWithTag(RulesTags.edit("r")).performClick()
        waitFor(RulesTags.FORM)
        compose.onNodeWithTag(RulesTags.profile("builtin_nudge")).assertIsSelected()
        compose.onNodeWithTag(RulesTags.WORDS).performTextReplacement("minutes")
        compose.onNodeWithTag(RulesTags.SAVE).performClick()

        waitFor(RulesTags.LIST)
        val row = storedRules().single()
        assertEquals("r", row.id)
        assertEquals("Work", row.matchCategory)
        assertEquals("minutes", row.matchTitleContains)
    }

    @Test
    fun `leaving the form writes nothing`() {
        val vm = showRules()
        compose.onNodeWithTag(RulesTags.ADD).performClick()
        waitFor(RulesTags.FORM)
        compose.onNodeWithTag(RulesTags.CATEGORY).performTextInput("Work")

        vm.cancel()

        waitFor(RulesTags.LIST)
        assertTrue(storedRules().isEmpty())
    }

    @Test
    fun `deleting a rule asks first, keeping it is the safe answer, and the rule goes only when it is confirmed`() {
        runBlocking { db.activityProfiles().upsert(ActivityProfileRow("r", "Work", null, "builtin_nudge")) }
        showRules()
        waitFor(RulesTags.delete("r"))

        compose.onNodeWithTag(RulesTags.delete("r")).performClick()
        waitFor(RulesTags.DELETE_DIALOG)
        compose.onNodeWithTag(RulesTags.DELETE_KEEP).performClick()
        waitGone(RulesTags.DELETE_DIALOG)
        assertEquals(1, storedRules().size)

        compose.onNodeWithTag(RulesTags.delete("r")).performClick()
        waitFor(RulesTags.DELETE_DIALOG)
        compose.onNodeWithTag(RulesTags.DELETE_CONFIRM).performClick()

        waitGone(RulesTags.rule("r"))
        assertTrue(storedRules().isEmpty())
    }

    @Test
    fun `the answer about a draft that was left says nothing about the next one`() {
        val vm = showRules()
        compose.onNodeWithTag(RulesTags.ADD).performClick()
        waitFor(RulesTags.FORM)

        vm.save() // nothing asked: it will be refused
        vm.cancel()
        vm.startNew()
        Thread.sleep(500)
        compose.waitForIdle()

        assertFalse("the refusal was of the draft that was left", exists(RulesTags.PROBLEM_NOTHING))

        vm.setCategory("Work")
        vm.setProfile("builtin_nudge")
        vm.save() // this one will be written
        vm.cancel()
        vm.startNew()
        compose.waitUntil(timeoutMillis = 45_000) { compose.waitForIdle(); storedRules().size == 1 }
        Thread.sleep(500)
        compose.waitForIdle()

        assertTrue("and that save does not close the new form", exists(RulesTags.FORM))
    }

    @Test
    fun `a rule saved twice in a moment is written once with what was shown, and the form closes`() {
        val vm = showRules()
        compose.onNodeWithTag(RulesTags.ADD).performClick()
        waitFor(RulesTags.FORM)
        vm.setCategory("Work")
        vm.setProfile("builtin_nudge")
        var writes = 0
        vm.beforeWrite = { writes++ }

        vm.save()
        vm.save()
        vm.setCategory("Home") // too late: this is what is being written

        waitFor(RulesTags.LIST)
        assertEquals("the second tap writes nothing", 1, writes)
        assertEquals("Work", storedRules().single().matchCategory)
    }

    @Test
    fun `a rule whose profile is gone says it does nothing`() {
        runBlocking { db.activityProfiles().upsert(ActivityProfileRow("r", "Work", null, "gone")) }
        showRules()

        waitFor(RulesTags.gone("r"))
        assertFalse("and it does nothing", runBlocking { ProfileResolver(db).resolve(testMission()) }?.id == "gone")
        assertNull(runBlocking { db.profiles().get("gone") })
    }
}
