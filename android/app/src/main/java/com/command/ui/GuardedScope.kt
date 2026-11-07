package com.command.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

private const val TAG = "ScreenWork"

private val logged = CoroutineExceptionHandler { _, e -> Log.w(TAG, "a screen's work failed, and the app carries on: ${e.javaClass.simpleName}", e) }

/**
 * The one scope a view model does its work in, and the one exception policy of all of them.
 *
 * The ring service lives in this process. What a screen's coroutine throws and nothing catches would end the process, and any alarm sounding in it, so
 * here it is logged and goes no further: the work that failed ends, the view model's other work goes on, and the screen stays as it was until the
 * next time it is opened. A view model that has something to say about a failure (a copy that could not be saved) catches it itself, as before.
 *
 * It is [viewModelScope] with the handler added, so it ends with the view model and runs on the same dispatcher.
 */
val ViewModel.guardedScope: CoroutineScope get() = CoroutineScope(viewModelScope.coroutineContext + logged)
