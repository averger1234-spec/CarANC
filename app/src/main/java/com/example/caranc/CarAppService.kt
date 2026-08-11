package com.example.caranc

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class ANCAppService : CarAppService() {
    override fun createHostValidator(): HostValidator {
        // Store (Play) builds: strict host allow-list. Internal road-test builds: allow all hosts for AA USB/wireless iteration.
        return if (BuildConfig.IS_STORE) {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.xml.host_validator)
                .build()
        } else {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
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