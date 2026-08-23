package com.anthony.uberviajes

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.Firebase
import com.google.firebase.initialize

class UberViajesApplication : Application() {

    lateinit var remoteConfig: FirebaseRemoteConfig
        private set

    override fun onCreate() {
        super.onCreate()

        Firebase.initialize(this)

        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)

        remoteConfig = Firebase.remoteConfig
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600) // 1h — ajusta en desarrollo si necesitas probar más rápido
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(
            mapOf(
                // TODO: agrega aquí tus valores por defecto, por ejemplo
                // umbrales mínimos de rentabilidad, flags de features, etc.
                "min_fare_mx" to 60.0,
                "min_rate_per_km" to 12.0
            )
        )
        remoteConfig.fetchAndActivate()
    }
}
