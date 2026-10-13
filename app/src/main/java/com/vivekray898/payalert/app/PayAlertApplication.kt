package com.vivekray898.payalert

import android.app.Application
import androidx.multidex.MultiDex
import com.vivekray898.payalert.di.provideAppContext
import com.vivekray898.payalert.di.provideSupabase
import com.vivekray898.payalert.di.registerRepositories
import com.vivekray898.payalert.di.registerUseCases
import com.vivekray898.payalert.di.setupDebugLogging

/**
 * Application class: initialise DI on create.
 * All "global" singletons (Supabase client, repos, use cases) are registered here.
 */
class PayAlertApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Must call before any DI usage
        setupDebugLogging("PayAlert")

        // Initialise dependency graph
        MultiDex.install(this)

        provideAppContext(this)
        registerRepositories(this)
        registerUseCases(this)
        provideSupabase(
            supabaseUrl = System.getenv("SUPABASE_PUBLISHABLE_URL") ?: "",
            supabaseAnonKey = System.getenv("SUPABASE_PUBLISHABLE_KEY") ?: ""
        )
    }
}