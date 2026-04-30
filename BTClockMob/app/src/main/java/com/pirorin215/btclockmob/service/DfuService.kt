package com.pirorin215.btclockmob.service

import android.app.Activity
import no.nordicsemi.android.dfu.DfuBaseService
import com.pirorin215.btclockmob.MainActivity

/**
 * Service for Nordic DFU OTA updates.
 */
class DfuService : DfuBaseService() {
    override fun getNotificationTarget(): Class<out Activity> {
        return MainActivity::class.java
    }

    override fun isDebug(): Boolean {
        // Return true to see more logs from the DFU library
        return true
    }
}
