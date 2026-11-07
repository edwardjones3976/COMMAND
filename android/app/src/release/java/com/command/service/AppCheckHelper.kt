package com.command.service

import android.content.Context
import android.content.Intent
import android.util.Log
import com.command.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

/**
 * Release builds: installs the reCAPTCHA Enterprise App Check provider. The site key is injected at build time
 * from RECAPTCHA_SITE_KEY (environment or the git-ignored local.properties); with no key no provider is installed
 * and the failure is logged.
 */
@Suppress("UNUSED_PARAMETER")
fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        val siteKey = BuildConfig.RECAPTCHA_SITE_KEY
        if (siteKey.isBlank()) {
            Log.e(TAG, "RECAPTCHA_SITE_KEY was not set at build time; App Check is not installed.")
            return
        }
        FirebaseAppCheck.getInstance().installAppCheckProviderFactory(RecaptchaAppCheckProviderFactory.getInstance(siteKey))
        Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}
