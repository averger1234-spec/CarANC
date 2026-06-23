package com.example.caranc

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class ANCAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        return if (BuildConfig.DEBUG) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.xml.host_validator)
                .build()
        }
    }

    override fun onCreateSession(): Session {
        return ANCAppSession()
    }
}

class ANCAppSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return CarAncAutoScreen(carContext)
    }
}