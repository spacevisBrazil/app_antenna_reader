package com.uhflogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.uhflogger.CsvExporter
import com.uhflogger.MainActivity
import com.uhflogger.SettingsManager
import com.uhflogger.decoder.ProtocolDecoder
import com.uhflogger.model.TagRecord
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UHFReaderService : Service() {

    // =========================================================================
    // Configurações de auto-save — valores padrão, ajustáveis via SettingsActivity
    // =========================================================================
    companion object {
        private const val TAG        = "UHFReaderService"
        private const val CHANNEL_ID = "uhf_logger_channel"
        private const val NOTIF_ID   = 1001
        private const val BAUD_RATE  = 115200

        const val ACTION_START      = "com.uhflogger.START"
        const val ACTION_STOP       = "com.uhflogger.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
    }

    // Lidos do SettingsManager no início de cada sessão
    private var autoSaveTagCount   : Int  = SettingsManager.DEFAULT_AUTO_SAVE_TAGS
    private var autoSaveIntervalMin: Long = SettingsManager.DEFAULT_AUTO_SAVE_MINUTES.toLong()

    // =========================================================================
    // Binder
    // =========================================================================
    inner class LocalBinder : Binder() {
        fun getService(): UHFReaderService = this@UHFReaderService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // =========================================================================
    // Estado interno
    // =========================================================================
    private val decoder    = ProtocolDecoder()
    private val tagBuffer  = ConcurrentLinkedQueue<TagRecord>() // lock-free, thread-safe
    private val totalCount = AtomicInteger(0)
    private val isRunning  = AtomicBoolean(false)
    private val isPaused   = AtomicBoolean(false)  // parado por erro, dados preservados

    private var usbPort       : UsbSerialPort? = null
    private var usbConnection : UsbDeviceConnection? = null
    private var ioManager     : SerialInputOutputManager? = null

    // Auto-save scheduler
    private var autoSaveExecutor: ScheduledExecutorService? = null
    private var autoSaveTimerJob: ScheduledFuture<*>?       = null

    // Contexto salvo para o CsvExporter (necessário fora da Activity)
    private var appContext: Context? = null

    // Callbacks para a Activity
    var onStatusChanged : ((Boolean) -> Unit)? = null
    var onCaptureError  : (() -> Unit)?        = null
    var onAutoSaved     : ((Int) -> Unit)?     = null  // notifica a UI do auto-save
    var mainActivity    : MainActivity?        = null

    // =========================================================================
    // Lifecycle
    // =========================================================================
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Aguardando conexão USB…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return START_NOT_STICKY
                startCapture(deviceName)
            }
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        mainActivity = null
        super.onDestroy()
    }

    // =========================================================================
    // API pública
    // =========================================================================

    fun startCapture(deviceName: String) {
        if (isRunning.get()) return
        val resuming = isPaused.getAndSet(false)
        decoder.reset()

        val ctx = appContext ?: return

        if (!resuming) {
            // Nova sessão — zera contador e cria novo arquivo
            totalCount.set(0)
            autoSaveTagCount    = SettingsManager.getAutoSaveTags(ctx)
            autoSaveIntervalMin = SettingsManager.getAutoSaveMinutes(ctx)
            Log.i(TAG, "Nova sessão — auto-save a cada $autoSaveTagCount tags ou $autoSaveIntervalMin min")
            val fileName = CsvExporter.startSession(ctx)
            if (fileName == null) {
                Log.e(TAG, "Falha ao criar arquivo CSV da sessão")
                return
            }
            Log.i(TAG, "Arquivo da sessão: $fileName")
        } else {
            Log.i(TAG, "Retomando sessão — ${totalCount.get()} tags já capturadas")
        }

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        val driver = availableDrivers.firstOrNull { it.device.deviceName == deviceName }
            ?: run { Log.e(TAG, "Device not found: $deviceName"); if (!resuming) CsvExporter.cancelSession(); isPaused.set(resuming); return }

        val connection = usbManager.openDevice(driver.device)
            ?: run { Log.e(TAG, "USB permission denied"); if (!resuming) CsvExporter.cancelSession(); isPaused.set(resuming); return }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            port.close(); connection.close()
            if (!resuming) CsvExporter.cancelSession()
            isPaused.set(resuming)
            return
        }

        usbPort       = port
        usbConnection = connection
        isRunning.set(true)

        // Inicia auto-save por tempo
        startAutoSaveTimer()

        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val lat = mainActivity?.getCurrentLatitude()  ?: ""
                val lon = mainActivity?.getCurrentLongitude() ?: ""
                val brg = mainActivity?.getCurrentBearing()   ?: ""
                val tags = decoder.feed(data, lat, lon, brg)
                if (tags.isNotEmpty()) {
                    tagBuffer.addAll(tags)
                    val total = totalCount.addAndGet(tags.size)
                    // Auto-save por contagem — sem lock, apenas drena a fila
                    if (total % autoSaveTagCount < tags.size) {
                        autoSaveExecutor?.submit { flushBufferToDisk() }
                    }
                }
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial read error", e)
                if (!isRunning.compareAndSet(true, false)) return
                isPaused.set(true)
                stopAutoSaveTimer()
                ioManager?.stop(); ioManager = null
                try { usbPort?.close() }       catch (_: Exception) {}
                try { usbConnection?.close() } catch (_: Exception) {}
                usbPort = null; usbConnection = null
                updateNotification("Sinal perdido — ${totalCount.get()} tags aguardando salvamento")
                onCaptureError?.invoke()
            }
        }).also {
            it.readTimeout  = 0
            it.writeTimeout = 2000
            Executors.newSingleThreadExecutor().submit(it)
        }

        updateNotification("Capturando…")
        onStatusChanged?.invoke(true)
        Log.i(TAG, "Capture started on $deviceName @ $BAUD_RATE baud")
    }

    fun stopCapture(): String? {
        if (!isRunning.compareAndSet(true, false)) return null
        isPaused.set(false)

        stopAutoSaveTimer()
        ioManager?.stop(); ioManager = null
        try { usbPort?.close() }       catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        usbPort = null; usbConnection = null

        val fileName = drainAndFinalize()
        updateNotification("Captura encerrada — ${totalCount.get()} tags salvas")
        onStatusChanged?.invoke(false)
        Log.i(TAG, "Capture stopped. Total tags: ${totalCount.get()}, file: $fileName")
        totalCount.set(0)
        return fileName
    }

    /**
     * Salva o que estiver na fila e fecha o arquivo.
     * Usado quando a captura já foi parada por erro (onRunError)
     * e o usuário clica em STOP para salvar os dados.
     */
    fun saveAfterError(): String? {
        isPaused.set(false)
        val fileName = drainAndFinalize()
        totalCount.set(0)
        Log.i(TAG, "saveAfterError: file=$fileName")
        return fileName
    }

    private fun drainAndFinalize(): String? {
        val lastBatch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) {
            tagBuffer.poll()?.let { lastBatch.add(it) }
        }
        return CsvExporter.finalizeSession(lastBatch)
    }

    fun isCapturing(): Boolean = isRunning.get()
    fun isPaused(): Boolean    = isPaused.get()

    /** Total de tags na sessão atual (gravadas + ainda no buffer) */
    fun tagCount(): Int = totalCount.get()

    /**
     * Mantido para compatibilidade com o onStopClicked da Activity.
     * Como os dados já foram gravados incrementalmente, retorna lista vazia
     * sinalizando que o arquivo já foi salvo.
     */
    fun flushTags(): List<TagRecord> = emptyList()

    // =========================================================================
    // Auto-save interno
    // =========================================================================

    private fun startAutoSaveTimer() {
        autoSaveExecutor = Executors.newSingleThreadScheduledExecutor()
        autoSaveTimerJob = autoSaveExecutor?.scheduleAtFixedRate(
            { timerAutoSave() },
            autoSaveIntervalMin,
            autoSaveIntervalMin,
            TimeUnit.MINUTES
        )
    }

    private fun stopAutoSaveTimer() {
        autoSaveTimerJob?.cancel(false)
        autoSaveTimerJob = null
        autoSaveExecutor?.shutdown()
        autoSaveExecutor = null
    }

    private fun timerAutoSave() {
        if (!isRunning.get()) return
        Log.i(TAG, "Auto-save por tempo (${autoSaveIntervalMin}min)")
        flushBufferToDisk()
    }

    /** Grava o buffer atual no disco e limpa. Deve ser chamado com lock em tagBuffer. */
    private fun flushBufferToDisk() {
        if (!isRunning.get()) return
        // Drena a fila lock-free — thread de leitura continua sem bloqueio
        val batch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) {
            tagBuffer.poll()?.let { batch.add(it) }
        }
        if (batch.isEmpty()) return

        val ok = CsvExporter.appendTags(batch)
        if (ok) {
            Log.i(TAG, "Auto-save: ${batch.size} tags gravadas, total: ${totalCount.get()}")
            updateNotification("Capturando… (${totalCount.get()} tags)")
            onAutoSaved?.invoke(totalCount.get())
        } else {
            Log.e(TAG, "Auto-save falhou — tags reinseridas na fila")
            tagBuffer.addAll(batch)
        }
    }

    // =========================================================================
    // Notificação
    // =========================================================================
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "UHF Logger", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Serviço de captura RFID UHF" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("UHF Data Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }
}