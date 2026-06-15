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
import com.uhflogger.decoder.WinnixProtocolDecoder
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
    // Binder
    // =========================================================================
    inner class LocalBinder : Binder() {
        fun getService(): UHFReaderService = this@UHFReaderService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // =========================================================================
    // State
    // =========================================================================
    private val jietongDecoder = ProtocolDecoder()
    private val winnixDecoder  = WinnixProtocolDecoder()
    private val tagBuffer      = ConcurrentLinkedQueue<TagRecord>()
    private val totalCount     = AtomicInteger(0)
    private val isRunning      = AtomicBoolean(false)
    private val isPausedState  = AtomicBoolean(false)

    private var usbPort        : UsbSerialPort? = null
    private var usbConnection  : UsbDeviceConnection? = null
    private var ioManager      : SerialInputOutputManager? = null

    private val stopExecutor   = Executors.newSingleThreadExecutor()
    private val configExecutor = Executors.newSingleThreadExecutor()

    // Auto-save
    private var autoSaveTagCount   : Int  = SettingsManager.DEFAULT_AUTO_SAVE_TAGS
    private var autoSaveIntervalMin: Long = SettingsManager.DEFAULT_AUTO_SAVE_MINUTES.toLong()
    private var autoSaveExecutor   : ScheduledExecutorService? = null
    private var autoSaveTimerJob   : ScheduledFuture<*>? = null

    private var appContext: Context? = null
    private var activeAntennaType: String = SettingsManager.ANTENNA_TYPE_JIETONG

    // Winnix temperature tracking
    @Volatile private var winnixStartTemp     : String = ""
    @Volatile private var winnixStopTempValue : String = ""
    private var winnixTempLatch: java.util.concurrent.CountDownLatch? = null

    // Callbacks
    var onStatusChanged    : ((Boolean) -> Unit)? = null
    var onCaptureError     : (() -> Unit)?        = null
    var onAutoSaved        : ((Int) -> Unit)?     = null
    var onWrongAntennaType : ((String) -> Unit)?  = null
    var onStopComplete     : ((String?, Int) -> Unit)? = null
    var mainActivity       : MainActivity?        = null

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
        stopExecutor.shutdownNow()
        configExecutor.shutdownNow()
        super.onDestroy()
    }

    // =========================================================================
    // Public API
    // =========================================================================
    fun startCapture(deviceName: String) {
        if (isRunning.get()) return
        val resuming = isPausedState.getAndSet(false)

        val ctx = appContext ?: return
        activeAntennaType = SettingsManager.getAntennaType(ctx)

        if (!resuming) {
            totalCount.set(0)
            autoSaveTagCount    = SettingsManager.getAutoSaveTags(ctx)
            autoSaveIntervalMin = SettingsManager.getAutoSaveMinutes(ctx)

            val prefix   = if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) "winnix" else "jietong"
            val fileName = CsvExporter.startSession(ctx, prefix)
            if (fileName == null) {
                Log.e(TAG, "Failed to create CSV session")
                return
            }
            Log.i(TAG, "Session: $fileName ($activeAntennaType)")
        } else {
            Log.i(TAG, "Resuming session — ${totalCount.get()} tags so far ($activeAntennaType)")
        }

        jietongDecoder.reset()
        winnixDecoder.reset()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers    = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver     = drivers.firstOrNull { it.device.deviceName == deviceName }
            ?: run { Log.e(TAG, "Device not found: $deviceName"); if (!resuming) CsvExporter.cancelSession(); isPausedState.set(resuming); return }

        val connection = usbManager.openDevice(driver.device)
            ?: run { Log.e(TAG, "USB permission denied"); if (!resuming) CsvExporter.cancelSession(); isPausedState.set(resuming); return }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening port", e)
            port.close()
            connection.close()
            if (!resuming) CsvExporter.cancelSession()
            isPausedState.set(resuming)
            return
        }

        usbPort       = port
        usbConnection = connection

        if (!probeAntennaType(port, activeAntennaType)) {
            val label = if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) "Winnix" else "Jietong"
            val msg   = "Antena $label não detectada. Verifique a conexão e o tipo configurado."
            Log.w(TAG, msg)
            isRunning.set(false)
            if (resuming) isPausedState.set(true)
            try { port.close() }       catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            usbPort = null
            usbConnection = null
            onWrongAntennaType?.invoke(msg)
            return
        }

        isRunning.set(true)

        if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
            startWinnixCapture(port, ctx)
        } else {
            startJietongCapture(port)
        }
        startAutoSaveTimer()
        updateNotification("Capturando…")
        onStatusChanged?.invoke(true)
        Log.i(TAG, "Capture started ($activeAntennaType) on $deviceName @ $BAUD_RATE baud")
    }

    fun stopCapture() {
        if (!isRunning.compareAndSet(true, false)) return
        isPausedState.set(false)
        stopAutoSaveTimer()

        stopExecutor.submit {
            // 1. Para o inventário de leitura
            sendWinnixStop()

            // 2. Prepara o gatilho assíncrono antes de requisitar temperatura
            winnixStopTempValue = ""
            winnixTempLatch = java.util.concurrent.CountDownLatch(1)

            // 3. Pede a temperatura de encerramento
            try { usbPort?.write(winnixBuildGetTemperature(), 2000) } catch (_: Exception) {}

            // 4. Aguarda até 2 segundos pela resposta vinda do onNewData
            val received = winnixTempLatch!!.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            winnixTempLatch = null

            val winnixStopTemp = if (received && winnixStopTempValue.isNotEmpty()) {
                Log.i(TAG, "Stop temperature: $winnixStopTempValue°C")
                winnixStopTempValue
            } else {
                Log.w(TAG, "Stop temperature not received within timeout")
                ""
            }

            // 5. Finaliza o IOManager e fecha as portas de comunicação com segurança
            ioManager?.stop()
            ioManager = null
            Thread.sleep(200)

            closePort()

            val tagCount = totalCount.get()
            val fileName = drainAndFinalize(winnixStopTemp)
            updateNotification("Captura encerrada — $tagCount tags")
            totalCount.set(0)

            Log.i(TAG, "Capture stopped. Total: $tagCount, file: $fileName")
            onStatusChanged?.invoke(false)
            onStopComplete?.invoke(fileName, tagCount)
        }
    }

    fun saveAfterError() {
        isPausedState.set(false)
        stopExecutor.submit {
            val tagCount = totalCount.get()
            val fileName = drainAndFinalize("")
            totalCount.set(0)
            Log.i(TAG, "Saved after error. Total: $tagCount, file: $fileName")
            onStopComplete?.invoke(fileName, tagCount)
        }
    }

    fun isCapturing(): Boolean = isRunning.get()
    fun isPaused()   : Boolean = isPausedState.get()
    fun tagCount()   : Int     = totalCount.get()
    fun flushTags(): List<TagRecord> = emptyList()

    // =========================================================================
    // Jietong capture
    // =========================================================================
    private fun startJietongCapture(port: UsbSerialPort) {
        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val lat  = mainActivity?.getCurrentLatitude()  ?: ""
                val lon  = mainActivity?.getCurrentLongitude() ?: ""
                val brg  = mainActivity?.getCurrentBearing()   ?: ""
                val tags = jietongDecoder.feed(data, lat, lon, brg)
                if (tags.isNotEmpty()) {
                    tagBuffer.addAll(tags)
                    val total = totalCount.addAndGet(tags.size)
                    if (total % autoSaveTagCount < tags.size) {
                        autoSaveExecutor?.submit { flushBufferToDisk() }
                    }
                }
            }
            override fun onRunError(e: Exception) = handleRunError(e)
        }).also {
            it.readTimeout  = 0
            it.writeTimeout = 2000
            Executors.newSingleThreadExecutor().submit(it)
        }
    }

    // =========================================================================
    // Winnix capture
    // =========================================================================
    private fun startWinnixCapture(port: UsbSerialPort, ctx: Context) {
        val antCount    = SettingsManager.getWinnixAntCount(ctx)
        val powerDbm    = SettingsManager.getWinnixPowerDbm(ctx)
        val workingMs   = SettingsManager.getWinnixWorkingMs(ctx)
        val invMode     = SettingsManager.getWinnixInventoryMode(ctx)
        val antennas    = (1..antCount).toList()
        val inactiveMs  = 300

        winnixStartTemp = ""

        configExecutor.submit {
            try {
                port.write(winnixBuildSetAntennas(antennas), 2000)
                Thread.sleep(500)
                port.purgeHwBuffers(false, true)

                antennas.forEach { ant ->
                    port.write(winnixBuildSetPower(ant, powerDbm), 2000)
                    Thread.sleep(500)
                    port.purgeHwBuffers(false, true)
                }

                for (ant in 1..4) {
                    val ms = if (ant <= antCount) workingMs else inactiveMs
                    port.write(winnixBuildSetWorkingTime(ant, ms), 2000)
                    Thread.sleep(500)
                    port.purgeHwBuffers(false, true)
                }

                port.write(winnixBuildSetInventoryMode(invMode), 2000)
                Thread.sleep(500)
                port.purgeHwBuffers(false, true)

                port.write(winnixBuildSetStatusLed(enabled = true), 2000)
                Thread.sleep(500)
                port.purgeHwBuffers(false, true)

                val startTemp = winnixReadTemperature(port)
                winnixStartTemp = if (startTemp != null) "%.1f".format(java.util.Locale.US, startTemp) else ""
                Log.i(TAG, "Winnix start temperature: $winnixStartTemp°C")

                Thread.sleep(200)
                port.purgeHwBuffers(false, true)

                port.write(winnixBuildStartInventory(), 2000)
                Log.i(TAG, "Winnix inventory started (ants=$antennas power=${powerDbm}dBm working=${workingMs}ms invMode=$invMode)")

                ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        checkWinnixTempResponse(data)

                        val lat  = mainActivity?.getCurrentLatitude()  ?: ""
                        val lon  = mainActivity?.getCurrentLongitude() ?: ""
                        val brg  = mainActivity?.getCurrentBearing()   ?: ""
                        val tags = winnixDecoder.feed(data, lat, lon, brg)
                        if (tags.isNotEmpty()) {
                            val processedTags = tags.toMutableList()
                            val temp = winnixStartTemp
                            if (temp.isNotEmpty()) {
                                winnixStartTemp = ""
                                processedTags[0] = processedTags[0].copy(temperature = temp)
                            }
                            tagBuffer.addAll(processedTags)
                            val total = totalCount.addAndGet(processedTags.size)
                            if (total % autoSaveTagCount < processedTags.size) {
                                autoSaveExecutor?.submit { flushBufferToDisk() }
                            }
                        }
                    }
                    override fun onRunError(e: Exception) = handleRunError(e)
                }).also {
                    it.readTimeout  = 0
                    it.writeTimeout = 2000
                    Executors.newSingleThreadExecutor().submit(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Winnix startup error", e)
                handleRunError(e)
            }
        }
    }

    private fun closePort() {
        synchronized(this) {
            try { usbPort?.close() }       catch (_: Exception) {}
            try { usbConnection?.close() } catch (_: Exception) {}
            usbPort       = null
            usbConnection = null
        }
    }

    private fun sendWinnixStop() {
        try {
            usbPort?.write(winnixBuildStopInventory(), 2000)
            Thread.sleep(300)
        } catch (_: Exception) {}
    }

    private fun checkWinnixTempResponse(data: ByteArray) {
        val latch = winnixTempLatch ?: return
        if (latch.count == 0L) return

        for (i in 0 until data.size - 8) {
            if (data[i] == 0xA5.toByte() && data[i+1] == 0x5A.toByte() && data[i+4] == 0x35.toByte()) {
                if (data[i+5] == 0x01.toByte()) {
                    val raw    = ((data[i+6].toInt() and 0xFF) shl 8) or (data[i+7].toInt() and 0xFF)
                    val signed = if (raw > 32767) raw - 65536 else raw
                    val temp   = signed / 100.0f
                    winnixStopTempValue = "%.1f".format(java.util.Locale.US, temp)
                    Log.i(TAG, "Winnix stop temperature captured via onNewData: $winnixStopTempValue°C")
                    latch.countDown()
                }
                break
            }
        }
    }

    private fun probeAntennaType(port: UsbSerialPort, antennaType: String): Boolean {
        return try {
            val (probeCmd, expectedHeader) = if (antennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x00, 0x08, 0x02, 0x0A, 0x0D, 0x0A) to byteArrayOf(0xA5.toByte(), 0x5A.toByte())
            } else {
                byteArrayOf(0x43, 0x4D, 0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00) to byteArrayOf(0x43, 0x4D, 0x01, 0x03)
            }

            port.write(probeCmd, 2000)

            val deadline   = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            val readBuf    = ByteArray(64)
            val response   = mutableListOf<Byte>()

            while (System.currentTimeMillis() < deadline && response.size < 16) {
                val n = try { port.read(readBuf, 100) } catch (_: Exception) { 0 }
                if (n > 0) for (i in 0 until n) response.add(readBuf[i])
                if (response.size >= expectedHeader.size) break
            }

            if (response.size < expectedHeader.size) {
                Log.w(TAG, "Probe timeout — no response from $antennaType")
                return false
            }

            val bytes = response.toByteArray()
            for (i in expectedHeader.indices) {
                if (bytes[i] != expectedHeader[i]) {
                    Log.w(TAG, "Probe header mismatch at byte $i")
                    return false
                }
            }
            Log.i(TAG, "Probe OK — $antennaType antenna confirmed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Probe error", e)
            false
        }
    }

    // =========================================================================
    // Winnix frame builders
    // =========================================================================
    private fun winnixCalcCheck(payload: ByteArray): Byte {
        var result = 0
        for (b in payload) result = result xor (b.toInt() and 0xFF)
        return result.toByte()
    }

    private fun winnixBuildFrame(command: Int, data: ByteArray = byteArrayOf()): ByteArray {
        val length  = 8 + data.size
        val payload = byteArrayOf(0x00, length.toByte(), command.toByte()) + data
        val check   = winnixCalcCheck(payload)
        return byteArrayOf(0xA5.toByte(), 0x5A.toByte()) + payload + byteArrayOf(check, 0x0D, 0x0A)
    }

    private fun winnixBuildStartInventory(): ByteArray = winnixBuildFrame(0x82, byteArrayOf(0x00, 0x00))
    private fun winnixBuildStopInventory(): ByteArray = winnixBuildFrame(0x8C)
    private fun winnixBuildGetTemperature(): ByteArray = winnixBuildFrame(0x34)

    private fun winnixBuildSetStatusLed(enabled: Boolean): ByteArray {
        val led = if (enabled) 0x01.toByte() else 0x00.toByte()
        return winnixBuildFrame(0x7A, byteArrayOf(0x00, 0x00, 0x00, led, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    }

    /**
     * mode: table mode 1-5 (as defined in SettingsManager WINNIX_INV_MODE_*).
     * Protocol DByte0 values are 0-4, with a -1 offset from the table numbering.
     * Verified against doc example: Fast read (table Mode 2) = DByte0 0x01.
     */
    private fun winnixBuildSetInventoryMode(mode: Int): ByteArray {
        val dbyte0 = (mode - 1) and 0xFF
        return winnixBuildFrame(0x76, byteArrayOf(0x00, dbyte0.toByte()))
    }

    private fun winnixReadTemperature(port: UsbSerialPort): Float? {
        return try {
            try { port.purgeHwBuffers(false, true) } catch (_: Exception) {}
            Thread.sleep(100)

            port.write(winnixBuildGetTemperature(), 2000)

            val collected = mutableListOf<Byte>()
            val deadline  = System.currentTimeMillis() + 1500L
            val buf       = ByteArray(32)
            while (System.currentTimeMillis() < deadline && collected.size < 11) {
                val n = try { port.read(buf, 200) } catch (_: Exception) { 0 }
                for (i in 0 until n) collected.add(buf[i])
            }

            if (collected.size < 9) return null

            val data = collected.toByteArray()
            val idx  = data.indexOfFirst { it == 0xA5.toByte() }
            if (idx < 0 || idx + 8 >= data.size)          return null
            if (data[idx + 1] != 0x5A.toByte())           return null
            if (data[idx + 4] != 0x35.toByte())           return null
            if (data[idx + 5] != 0x01.toByte())           return null

            val raw    = ((data[idx + 6].toInt() and 0xFF) shl 8) or (data[idx + 7].toInt() and 0xFF)
            val signed = if (raw > 32767) raw - 65536 else raw
            val temp   = signed / 100.0f
            Log.i(TAG, "Temperature read: $temp°C")
            temp
        } catch (e: Exception) {
            Log.w(TAG, "Temperature read failed: ${e.message}")
            null
        }
    }

    private fun winnixBuildSetAntennas(antennas: List<Int>): ByteArray {
        var mask = 0L
        for (ant in antennas) if (ant in 1..64) mask = mask or (1L shl (ant - 1))
        val le        = mask.toBytesLE8()
        val antBytes  = byteArrayOf(le[1], le[0]) + le.copyOfRange(2, 8)
        return winnixBuildFrame(0x28, byteArrayOf(0x00) + antBytes)
    }

    private fun winnixBuildSetPower(antenna: Int, powerDbm: Int): ByteArray {
        val raw = powerDbm * 100
        return winnixBuildFrame(0x10, byteArrayOf(
            0x00, (antenna and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(), (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(), (raw and 0xFF).toByte()
        ))
    }

    private fun winnixBuildSetWorkingTime(antenna: Int, timeMs: Int): ByteArray {
        val dbyte2 = (antenna and 0x0F).toByte()
        return winnixBuildFrame(0x4A, byteArrayOf(dbyte2, ((timeMs shr 8) and 0xFF).toByte(), (timeMs and 0xFF).toByte()))
    }

    private fun Long.toBytesLE8(): ByteArray {
        val result = ByteArray(8)
        for (i in 0..7) result[i] = ((this shr (i * 8)) and 0xFF).toByte()
        return result
    }

    // =========================================================================
    // Shared error handler
    // =========================================================================
    private fun handleRunError(e: Exception) {
        Log.e(TAG, "Serial read error — device likely disconnected", e)
        if (!isRunning.compareAndSet(true, false)) return
        isPausedState.set(true)
        stopAutoSaveTimer()
        ioManager?.stop()
        ioManager = null
        closePort()
        updateNotification("Sinal perdido — ${totalCount.get()} tags aguardando salvamento")
        onCaptureError?.invoke()
    }

    // =========================================================================
    // Auto-save
    // =========================================================================
    private fun startAutoSaveTimer() {
        autoSaveExecutor = Executors.newSingleThreadScheduledExecutor()
        autoSaveTimerJob = autoSaveExecutor?.scheduleAtFixedRate(
            { if (isRunning.get()) flushBufferToDisk() },
            autoSaveIntervalMin, autoSaveIntervalMin, TimeUnit.MINUTES
        )
    }

    private fun stopAutoSaveTimer() {
        autoSaveTimerJob?.cancel(false)
        autoSaveTimerJob = null
        autoSaveExecutor?.shutdown()
        autoSaveExecutor = null
    }

    private fun flushBufferToDisk() {
        if (!isRunning.get()) return
        val batch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) tagBuffer.poll()?.let { batch.add(it) }
        if (batch.isEmpty()) return

        val ok = CsvExporter.appendTags(batch)
        if (ok) {
            Log.i(TAG, "Auto-save: ${batch.size} tags, total: ${totalCount.get()}")
            updateNotification("Capturando… (${totalCount.get()} tags)")
            onAutoSaved?.invoke(totalCount.get())
        } else {
            Log.e(TAG, "Auto-save failed — reinserting")
            tagBuffer.addAll(batch)
        }
    }

    private fun drainAndFinalize(winnixStopTemp: String = ""): String? {
        val lastBatch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) tagBuffer.poll()?.let { lastBatch.add(it) }
        return CsvExporter.finalizeSession(lastBatch, winnixStopTemp)
    }

    // =========================================================================
    // Notification
    // =========================================================================
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "UHF Logger", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Serviço de captura RFID UHF" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("UHF Data Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG        = "UHFReaderService"
        private const val CHANNEL_ID = "uhf_logger_channel"
        private const val NOTIF_ID   = 1001
        private const val BAUD_RATE         = 115200
        private const val PROBE_TIMEOUT_MS  = 250L

        const val ACTION_START      = "com.uhflogger.START"
        const val ACTION_STOP       = "com.uhflogger.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}