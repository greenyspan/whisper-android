package com.parkerxin.whisper

import android.app.Application

class WhisperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: WhisperApp
            private set
    }
}
