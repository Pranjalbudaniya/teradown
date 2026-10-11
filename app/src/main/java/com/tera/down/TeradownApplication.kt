package com.tera.down

import android.app.Application
import com.tera.down.core.di.AppContainer
import com.tera.down.core.di.DefaultAppContainer

class TeradownApplication : Application() {

    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
