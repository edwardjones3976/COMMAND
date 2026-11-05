package com.command

import javax.inject.Inject
import javax.inject.Singleton

/** Smallest injectable class: lets `HiltGraphTest` prove the Hilt graph builds and injects under Robolectric. */
@Singleton
class BuildInfo @Inject constructor() {
    val applicationId: String get() = com.example.BuildConfig.APPLICATION_ID
}
