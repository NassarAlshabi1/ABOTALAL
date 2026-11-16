package com.example

import androidx.core.content.edit

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

fun installAppCheck(context: Context) {
    // Seed the pre-registered debug token so local/dev builds pass App Check.
    val debugToken = BuildConfig.FIREBASE_APP_CHECK_DEBUG_TOKEN
    if (debugToken.isNotBlank()) {
        context.getSharedPreferences("com.google.firebase.appcheck.debug.store", Context.MODE_PRIVATE)
            .edit { putString("debugSecret", debugToken) }
    }
    FirebaseAppCheck.getInstance()
        .installAppCheckProviderFactory(DebugAppCheckProviderFactory.getInstance())
}
