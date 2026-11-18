package com.uhflogger.smartbox.collect

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.uhflogger.MainActivity
import com.uhflogger.R
import com.uhflogger.smartbox.ble.displayName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano da coleta da smartbox, tipo `connectedDevice`
 * (seção 2.1 do guia; `dataSync` tem limite de 6 h/dia no Android 15).
 *
 * Convive com o UHFReaderService: canal, notificação e ID próprios. Nunca
 * derruba o app nem o leitor: se o Android recusar o startForeground
 * (permissão, início em segundo plano), registra e para sozinho. Só é
 * iniciado com a coleta habilitada ([SmartboxCollector.isEnabled]) e com o app
 * na frente (o Android 12+ não deixa subir FGS do fundo).
 */
class SmartboxCollectService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val ok = try {
            val notification = buildNotification("Smartbox: procurando…")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (e: Exception) {
            // SecurityException (sem BLUETOOTH_CONNECT no Android 14) ou
            // ForegroundServiceStartNotAllowedException: desiste quieto.
            Log.w(TAG, "startForeground recusado: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
        if (!ok) {
            stopSelf()
            return
        }
        acquireWakeLock()
        SmartboxCollector.start(this)
        watchJob = scope.launch {
            SmartboxCollector.state.collect { s ->
                if (s.phase == SmartboxCollector.Phase.OFF && !SmartboxCollector.isEnabled(this@SmartboxCollectService)) {
                    stopSelf()
                } else {
                    updateNotification(notificationText(s))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Morto pelo sistema, volta ao abrir o app (não dá para subir FGS do fundo de forma confiável).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        watchJob?.cancel()
        scope.cancel()
        SmartboxCollector.stop()
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "uhflogger:smartbox").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock falhou: ${e.message}")
        }
    }

    private fun notificationText(s: SmartboxCollector.State): String {
        val name = s.moduleId?.let { displayName(it) }
        return when (s.phase) {
            SmartboxCollector.Phase.WAITING_READER -> "Smartbox: aguardando a leitura parar"
            SmartboxCollector.Phase.PAUSED_FOR_TECHNICIAN -> "Smartbox: pausada (tela de técnico aberta)"
            SmartboxCollector.Phase.CONNECTING -> "Smartbox: conectando em $name"
            SmartboxCollector.Phase.DOWNLOADING ->
                "Smartbox $name: baixando ${fmt(s.received)}" + (s.total?.let { " de ${fmt(it)}" } ?: "")
            SmartboxCollector.Phase.DONE -> "Smartbox $name: ${fmt(s.doneReadings)} leituras guardadas"
            else -> "Smartbox: procurando…"
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Smartbox (bebedouro)", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UHF Logger")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private var lastText: String? = null

    private fun updateNotification(text: String) {
        if (text == lastText) return
        lastText = text
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SmartboxCollectService"
        private const val CHANNEL_ID = "smartbox_collect"
        /** Diferente do NOTIF_ID do UHFReaderService: as duas notificações convivem. */
        private const val NOTIF_ID = 4201

        private fun fmt(n: Int) = "%,d".format(java.util.Locale("pt", "BR"), n)

        /**
         * Sobe o serviço se a coleta está habilitada. Chamar com o app NA FRENTE
         * (MainActivity.onStart). Sem chave, fazenda ou permissão: não faz nada.
         */
        fun startIfEnabled(context: Context) {
            if (!SmartboxCollector.isEnabled(context)) return
            try {
                context.startForegroundService(Intent(context, SmartboxCollectService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "não subiu o serviço da smartbox: ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, SmartboxCollectService::class.java))
            } catch (_: Exception) {
            }
        }
    }
}
