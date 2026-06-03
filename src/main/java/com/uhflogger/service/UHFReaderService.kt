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
    private val isPaused       = AtomicBoolean(false)

    private var usbPort        : UsbSerialPort? = null
    private var usbConnection  : UsbDeviceConnection? = null
    private var ioManager      : SerialInputOutputManager? = null

    // Auto-save
    private var autoSaveTagCount   : Int  = SettingsManager.DEFAULT_AUTO_SAVE_TAGS
    private var autoSaveIntervalMin: Long = SettingsManager.DEFAULT_AUTO_SAVE_MINUTES.toLong()
    private var autoSaveExecutor   : ScheduledExecutorService? = null
    private var autoSaveTimerJob   : ScheduledFuture<*>? = null

    private var appContext: Context? = null

    // Active antenna type for current session
    private var activeAntennaType: String = SettingsManager.ANTENNA_TYPE_JIETONG

    // Callbacks
    var onStatusChanged  : ((Boolean) -> Unit)? = null
    var onCaptureError   : (() -> Unit)?        = null
    var onAutoSaved      : ((Int) -> Unit)?     = null
    var onWrongAntennaType: ((String) -> Unit)? = null
    var mainActivity     : MainActivity?        = null

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
    // Public API
    // =========================================================================

    fun startCapture(deviceName: String) {
        if (isRunning.get()) return
        val resuming = isPaused.getAndSet(false)

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

        // Reset decoders
        jietongDecoder.reset()
        winnixDecoder.reset()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers    = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver     = drivers.firstOrNull { it.device.deviceName == deviceName }
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
            Log.e(TAG, "Error opening port", e)
            port.close(); connection.close()
            if (!resuming) CsvExporter.cancelSession()
            isPaused.set(resuming)
            return
        }

        usbPort       = port
        usbConnection = connection

        // Probe: verify that the connected antenna matches the configured type
        // This runs on every startCapture including resume — catches wrong antenna reconnection
        if (!probeAntennaType(port, activeAntennaType)) {
            val label = if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) "Winnix" else "Jietong"
            val msg   = "Antena $label não detectada. Verifique a conexão e o tipo configurado."
            Log.w(TAG, msg)
            isRunning.set(false)
            // Restore paused state if was resuming — data is still safe
            if (resuming) isPaused.set(true)
            try { port.close() }       catch (_: Exception) {}
            try { connection.close() } catch (_: Exception) {}
            usbPort = null; usbConnection = null
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

    fun stopCapture(): String? {
        if (!isRunning.compareAndSet(true, false)) return null
        isPaused.set(false)

        stopAutoSaveTimer()

        if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
            sendWinnixStop()
        }

        ioManager?.stop(); ioManager = null
        try { usbPort?.close() }       catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        usbPort = null; usbConnection = null

        val fileName = drainAndFinalize()
        updateNotification("Captura encerrada — ${totalCount.get()} tags")
        onStatusChanged?.invoke(false)
        Log.i(TAG, "Capture stopped. Total: ${totalCount.get()}, file: $fileName")
        totalCount.set(0)
        return fileName
    }

    fun saveAfterError(): String? {
        isPaused.set(false)
        val fileName = drainAndFinalize()
        totalCount.set(0)
        return fileName
    }

    fun isCapturing(): Boolean = isRunning.get()
    fun isPaused()   : Boolean = isPaused.get()
    fun tagCount()   : Int     = totalCount.get()

    fun flushTags(): List<TagRecord> = emptyList() // data saved incrementally

    // =========================================================================
    // Jietong capture (existing logic)
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
        val antCount   = SettingsManager.getWinnixAntCount(ctx)
        val powerDbm   = SettingsManager.getWinnixPowerDbm(ctx)
        val workingMs  = SettingsManager.getWinnixWorkingMs(ctx)
        val antennas   = (1..antCount).toList()

        // Send config commands synchronously on a background thread before starting IOManager
        Executors.newSingleThreadExecutor().submit {
            try {
                // set_antennas
                port.write(winnixBuildSetAntennas(antennas), 2000)
                Thread.sleep(500)
                port.purgeHwBuffers(false, true)  // discard response

                // set_power for each antenna
                antennas.forEach { ant ->
                    port.write(winnixBuildSetPower(ant, powerDbm), 2000)
                    Thread.sleep(500)
                    port.purgeHwBuffers(false, true)
                }

                // set_working_time for each antenna
                antennas.forEach { ant ->
                    port.write(winnixBuildSetWorkingTime(ant, workingMs), 2000)
                    Thread.sleep(500)
                    port.purgeHwBuffers(false, true)
                }

                // Flush any remaining config responses
                Thread.sleep(200)
                port.purgeHwBuffers(false, true)

                // Send start_inventory
                port.write(winnixBuildStartInventory(), 2000)
                Log.i(TAG, "Winnix inventory started (ants=$antennas power=${powerDbm}dBm working=${workingMs}ms)")

                // Now start the IOManager to read tags
                ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) {
                        val lat  = mainActivity?.getCurrentLatitude()  ?: ""
                        val lon  = mainActivity?.getCurrentLongitude() ?: ""
                        val brg  = mainActivity?.getCurrentBearing()   ?: ""
                        val tags = winnixDecoder.feed(data, lat, lon, brg)
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
            } catch (e: Exception) {
                Log.e(TAG, "Winnix startup error", e)
                handleRunError(e)
            }
        }
    }

    private fun sendWinnixStop() {
        try {
            usbPort?.write(winnixBuildStopInventory(), 2000)
            Thread.sleep(300)
        } catch (_: Exception) {}
    }

    /**
     * Sends a version/status query to the port and checks if the response
     * matches the expected antenna protocol header.
     *
     * Jietong: cmd 0x01 (get version) → response starts with [0x43, 0x4D, 0x01, 0x03]
     * Winnix:  cmd 0x02 (get version) → response starts with [0xA5, 0x5A]
     *
     * Runs synchronously — called before IOManager is started.
     * Timeout: 1000ms.
     */
    private fun probeAntennaType(port: UsbSerialPort, antennaType: String): Boolean {
        return try {
            val (probeCmd, expectedHeader) = if (antennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
                // Winnix: get version — A5 5A 00 08 02 0A 0D 0A
                // BCC = XOR(0x00, 0x08, 0x02) = 0x0A  ✓ (verified against real capture)
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x00, 0x08, 0x02, 0x0A, 0x0D, 0x0A) to
                        byteArrayOf(0xA5.toByte(), 0x5A.toByte())
            } else {
                // Jietong: get version (0x01), eigenvalue=0x02 (host), length=0x0002, devnum=0x0000, BCC=0x00
                byteArrayOf(0x43, 0x4D, 0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00) to
                        byteArrayOf(0x43, 0x4D, 0x01, 0x03)
            }

            port.write(probeCmd, 2000)

            // Wait for response — 1000ms timeout
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

            // Check if response starts with expected header
            val bytes = response.toByteArray()
            for (i in expectedHeader.indices) {
                if (bytes[i] != expectedHeader[i]) {
                    Log.w(TAG, "Probe header mismatch at byte $i: " +
                            "expected 0x${String.format("%02X", expectedHeader[i].toInt() and 0xFF)} " +
                            "got 0x${String.format("%02X", bytes[i].toInt() and 0xFF)}")
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

    private fun winnixBuildStartInventory(): ByteArray =
        winnixBuildFrame(0x82, byteArrayOf(0x00, 0x00))

    private fun winnixBuildStopInventory(): ByteArray =
        winnixBuildFrame(0x8C)

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
        return winnixBuildFrame(0x4A, byteArrayOf(
            dbyte2,
            ((timeMs shr 8) and 0xFF).toByte(),
            (timeMs and 0xFF).toByte()
        ))
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
        isPaused.set(true)
        stopAutoSaveTimer()
        ioManager?.stop(); ioManager = null
        try { usbPort?.close() }       catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        usbPort = null; usbConnection = null
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

    private fun drainAndFinalize(): String? {
        val lastBatch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) tagBuffer.poll()?.let { lastBatch.add(it) }
        return CsvExporter.finalizeSession(lastBatch)
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
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("UHF Data Logger")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG        = "UHFReaderService"
        private const val CHANNEL_ID = "uhf_logger_channel"
        private const val NOTIF_ID   = 1001
        private const val BAUD_RATE         = 115200
        private const val PROBE_TIMEOUT_MS  = 250L    // timeout for antenna type detection probe

        const val ACTION_START      = "com.uhflogger.START"
        const val ACTION_STOP       = "com.uhflogger.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}