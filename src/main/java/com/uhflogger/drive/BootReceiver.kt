package com.uhflogger.drive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed — starting DriveMonitorService")
        // O serviço monitora a PASTA de CSV, que serve aos dois destinos — sobe
        // se qualquer um deles estiver configurado. Amarrá-lo só ao login do
        // Google deixaria um aparelho que usa apenas o backend sem o
        // FileObserver e sem a agenda periódica, ou seja, sem enviar nada.
        if (DriveHelper.isSignedIn(context) || com.uhflogger.backend.BackendSettings.isEnabled(context)) {
            DriveMonitorService.start(context)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
