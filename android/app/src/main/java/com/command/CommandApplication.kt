package com.command

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Hilt entry point. The object graph is built here; nothing else constructs its own collaborators. */
@HiltAndroidApp
class CommandApplication : Application()
