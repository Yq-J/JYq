package com.yqj.onesignaltester

import android.app.Application

class OneSignalTesterApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        OneSignalManager.initialize(this)
    }
}
