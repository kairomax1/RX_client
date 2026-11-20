package com.v2ray.ang

import com.v2ray.ang.handler.UpdateNotifier
import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.v2ray.ang.AppConfig.ANG_PACKAGE
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.BackgroundSyncManager
import com.v2ray.ang.handler.DailyMaintenance
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.PublicConfigs
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.XboxDns
import com.v2ray.ang.ui.compose.ThemeManager

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
        ThemeManager.refresh()

        // Keep the daily maintenance job in sync with the preference, and ensure
        // Public Configs (every 15m + daily) and DNS Scanner (daily) refresh automatically.
        DailyMaintenance.sync(this)
        PublicConfigs.schedule(this)
        XboxDns.scheduleDaily(this)
        BackgroundSyncManager.schedule(this)
        BackgroundSyncManager.kickIfDue(this)
        UpdateNotifier.scheduleWatch(this)
    }
}
