package com.command.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.command.consents.ConsentCenterRoute
import com.command.editor.MissionEditorRoute
import com.command.followup.FollowUpRoute
import com.command.onboarding.FirstMissionRoute
import com.command.onboarding.PermissionsRoute
import com.command.onboarding.WelcomeScreen
import com.command.reliability.ReliabilityRoute
import com.command.schedule.ScheduleRoute

object Routes {
    const val WELCOME = "welcome"
    const val PERMISSIONS = "permissions"
    const val FIRST_MISSION = "first_mission"
    const val SCHEDULE = "schedule"
    const val RELIABILITY = "reliability"
    const val CONSENTS = "consents"
    const val ARG_ID = "id"
    private const val EDITOR = "mission"
    private const val FOLLOW_UP = "followup"

    /** The follow-up questions of the occurrence with this id. */
    const val FOLLOW_UP_PATTERN = "$FOLLOW_UP/{$ARG_ID}"

    fun followUp(occurrenceId: String): String = "$FOLLOW_UP/$occurrenceId"

    /** The editor for a new mission, or for the mission with this id. */
    const val EDITOR_PATTERN = "$EDITOR?$ARG_ID={$ARG_ID}"

    fun editor(missionId: String? = null): String = if (missionId == null) EDITOR else "$EDITOR?$ARG_ID=$missionId"
}

/** The screens of the app and the ways between them. The lock-screen flow is a separate activity and is not part of this. */
@Composable
fun CommandNav(openOccurrence: String? = null, onOpenedOccurrence: () -> Unit = {}, entry: AppEntryViewModel = hiltViewModel()) {
    val start by entry.start.collectAsStateWithLifecycle()
    val destination = start
    if (destination == null) {
        // The setting is read in a moment; until then show the app's own background rather than a screen that is about to change.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
    } else {
        CommandNavHost(startDestination = destination, openOccurrence = openOccurrence, onOpenedOccurrence = onOpenedOccurrence)
    }
}

@Composable
private fun CommandNavHost(
    startDestination: String,
    openOccurrence: String?,
    onOpenedOccurrence: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    // A check-in notification's Open action asks for one occurrence's questions. It waits here until the host exists, then goes.
    LaunchedEffect(openOccurrence) {
        if (openOccurrence != null) {
            navController.navigate(Routes.followUp(openOccurrence))
            onOpenedOccurrence()
        }
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.WELCOME) {
            WelcomeScreen(onContinue = { navController.navigate(Routes.PERMISSIONS) })
        }
        composable(Routes.PERMISSIONS) {
            PermissionsRoute(
                onContinue = {
                    navController.navigate(Routes.FIRST_MISSION) { popUpTo(Routes.WELCOME) { inclusive = true } }
                },
            )
        }
        composable(Routes.FIRST_MISSION) {
            val toSchedule = { navController.navigate(Routes.SCHEDULE) { popUpTo(Routes.FIRST_MISSION) { inclusive = true } } }
            FirstMissionRoute(
                onCreate = {
                    // The schedule goes underneath, so saving the mission lands there.
                    toSchedule()
                    navController.navigate(Routes.editor())
                },
                onSkip = { toSchedule() },
            )
        }
        composable(Routes.SCHEDULE) {
            ScheduleRoute(
                onAdd = { navController.navigate(Routes.editor()) },
                onOpen = { missionId -> navController.navigate(Routes.editor(missionId)) },
                onOpenOccurrence = { occurrenceId -> navController.navigate(Routes.followUp(occurrenceId)) },
                onOpenReliability = { navController.navigate(Routes.RELIABILITY) },
                onOpenConsents = { navController.navigate(Routes.CONSENTS) },
            )
        }
        composable(Routes.RELIABILITY) { ReliabilityRoute(onBack = { navController.popBackStack() }) }
        composable(Routes.CONSENTS) { ConsentCenterRoute(onBack = { navController.popBackStack() }) }
        composable(
            Routes.FOLLOW_UP_PATTERN,
            arguments = listOf(navArgument(Routes.ARG_ID) { type = NavType.StringType }),
        ) { entry ->
            FollowUpRoute(occurrenceId = entry.arguments?.getString(Routes.ARG_ID).orEmpty(), onClose = { navController.popBackStack() })
        }
        composable(
            Routes.EDITOR_PATTERN,
            arguments = listOf(
                navArgument(Routes.ARG_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            MissionEditorRoute(onDone = { navController.popBackStack() })
        }
    }
}
