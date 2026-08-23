package com.anthony.uberviajes

import android.app.Application

class UberViajesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
    }
}
