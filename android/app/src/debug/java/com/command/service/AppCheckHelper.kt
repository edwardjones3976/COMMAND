package com.command.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

private const val TAG = "AppCheckInit"
private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"

/**
 * Debug builds only: installs the App Check debug provider. The token comes from an Intent extra and is never
 * stored in the repository. The release source set has its own copy that uses reCAPTCHA Enterprise, so the
 * debug provider is not on the release classpath.
 */
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val debugToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)
        if (!debugToken.isNullOrEmpty()) {
            val persistenceKey = FirebaseApp.getInstance().persistenceKey
            val prefs = context.getSharedPreferences("$DEBUG_STORE_PREFIX$persistenceKey", Context.MODE_PRIVATE)
            // Must be commit(): the provider reads the secret synchronously on install.
            prefs.edit().putString(DEBUG_SECRET_KEY, debugToken).commit()
            Log.d(TAG, "App Check debug secret configured.")
        } else {
            Log.w(TAG, "No debug token found in Intent extra.")
        }
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
        Log.d(TAG, "DebugAppCheckProviderFactory installed.")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
