package com.uhflogger.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — starting DriveMonitorService")
        if (DriveHelper.isSignedIn(context)) {
            DriveMonitorService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
