package com.command.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.command.editor.MissionEditorRoute
import com.command.schedule.ScheduleRoute

object Routes {
    const val SCHEDULE = "schedule"
    const val ARG_ID = "id"
    private const val EDITOR = "mission"

    /** The editor for a new mission, or for the mission with this id. */
    const val EDITOR_PATTERN = "$EDITOR?$ARG_ID={$ARG_ID}"

    fun editor(missionId: String? = null): String = if (missionId == null) EDITOR else "$EDITOR?$ARG_ID=$missionId"
}

/** The screens of the app and the ways between them. The lock-screen flow is a separate activity and is not part of this. */
@Composable
fun CommandNav() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SCHEDULE) {
        composable(Routes.SCHEDULE) {
            ScheduleRoute(
                onAdd = { navController.navigate(Routes.editor()) },
                onOpen = { missionId -> navController.navigate(Routes.editor(missionId)) },
            )
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
