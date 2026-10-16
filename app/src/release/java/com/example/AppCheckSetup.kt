package com.example

import android.content.Context
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

fun installAppCheck(context: Context) {
    FirebaseAppCheck.getInstance()
        .installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
}
