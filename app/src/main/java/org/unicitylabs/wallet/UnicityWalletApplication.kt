package org.unicitylabs.wallet

import android.app.Application
import org.unicitylabs.wallet.di.ServiceProvider

class UnicityWalletApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize ServiceProvider with application context
        // This ensures trustbase is loaded from assets on app startup
        ServiceProvider.init(this)
    }
}