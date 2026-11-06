package com.command.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.command.domain.reliability.Fix
import com.command.reliability.ItemCard
import com.command.reliability.rememberFixHandler
import com.command.R

object OnboardingTags {
    const val WELCOME_CONTINUE = "onboarding_welcome_continue"
    const val PERMISSIONS_LIST = "onboarding_permissions_list"
    const val PERMISSIONS_ALL_SET = "onboarding_permissions_all_set"
    const val PERMISSIONS_CONTINUE = "onboarding_permissions_continue"
    const val FIRST_CREATE = "onboarding_first_create"
    const val FIRST_SKIP = "onboarding_first_skip"
    const val FIRST_DONE = "onboarding_first_done"
}

/** The first thing a new install shows: what COMMAND does, and that the person stays in charge. */
@Composable
fun WelcomeScreen(onContinue: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_welcome_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.onboarding_welcome_lead), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.onboarding_welcome_point_ring), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.onboarding_welcome_point_control), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.onboarding_welcome_point_private), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(OnboardingTags.WELCOME_CONTINUE),
            ) { Text(stringResource(R.string.onboarding_continue), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
fun PermissionsRoute(onContinue: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val state by viewModel.permissions.collectAsStateWithLifecycle()
    val canAsk = (state as? PermissionsUiState.Ready)?.canAskNotifications ?: false
    val onFix = rememberFixHandler(canAsk, viewModel::onNotificationRequestResult)

    // The person can come back from Android's settings with something changed, so look again whenever this screen returns.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    PermissionsScreen(state = state, onFix = onFix, onContinue = { viewModel.finish(onContinue) })
}

/**
 * Asks for what alarms need, one reason at a time. Nothing here is required: Continue is always available, and what a refusal
 * costs is said next to the thing that was refused.
 */
@Composable
fun PermissionsScreen(state: PermissionsUiState, onFix: (Fix) -> Unit, onContinue: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.onboarding_permissions_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.onboarding_permissions_lead), style = MaterialTheme.typography.bodyLarge)
            when (state) {
                PermissionsUiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is PermissionsUiState.Ready -> {
                    if (state.allAllowed) {
                        Text(
                            stringResource(R.string.onboarding_permissions_all_set),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag(OnboardingTags.PERMISSIONS_ALL_SET),
                        )
                    }
                    Column(modifier = Modifier.testTag(OnboardingTags.PERMISSIONS_LIST), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (item in state.items) ItemCard(item, state.canAskNotifications, onFix)
                    }
                }
            }
            Text(stringResource(R.string.onboarding_permissions_later), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(OnboardingTags.PERMISSIONS_CONTINUE),
            ) { Text(stringResource(R.string.onboarding_continue), style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/**
 * The last step: make a first mission, or leave it for later. When missions already exist (they came across from the previous
 * version) it says so instead of asking for one.
 */
@Composable
fun FirstMissionScreen(hasMissions: Boolean?, onCreate: () -> Unit, onSkip: () -> Unit) {
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (hasMissions == null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (hasMissions) {
                Text(stringResource(R.string.onboarding_have_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.onboarding_have_lead), style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(OnboardingTags.FIRST_DONE),
                ) { Text(stringResource(R.string.onboarding_have_done), style = MaterialTheme.typography.labelLarge) }
            } else {
                Text(stringResource(R.string.onboarding_first_title), style = MaterialTheme.typography.headlineLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.onboarding_first_lead), style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = onCreate,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(OnboardingTags.FIRST_CREATE),
                ) { Text(stringResource(R.string.onboarding_first_create), style = MaterialTheme.typography.labelLarge) }
                OutlinedButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(OnboardingTags.FIRST_SKIP),
                ) { Text(stringResource(R.string.onboarding_first_skip), style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable
fun FirstMissionRoute(onCreate: () -> Unit, onSkip: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    val hasMissions by viewModel.hasMissions.collectAsStateWithLifecycle()
    FirstMissionScreen(hasMissions = hasMissions, onCreate = onCreate, onSkip = onSkip)
}
