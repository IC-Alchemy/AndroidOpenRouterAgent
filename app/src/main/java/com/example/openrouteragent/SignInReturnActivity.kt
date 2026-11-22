package com.example.openrouteragent

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Brings the app back after OpenRouter sign-in. It carries no data; the key arrives through the loopback listener. */
class SignInReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isTaskRoot) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        finish()
    }
}
