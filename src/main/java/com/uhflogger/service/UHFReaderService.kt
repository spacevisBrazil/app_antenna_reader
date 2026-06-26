package com.uhflogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
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
import com.uhflogger.serial.BluetoothInputOutputManager
import com.uhflogger.serial.BluetoothSerialPort
import com.uhflogger.serial.ISerialPort
import com.uhflogger.serial.UsbSerialPortWrapper
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
    private var activePort     : ISerialPort? = null   // USB or Bluetooth — single active port
    private var ioManager      : SerialInputOutputManager? = null
    private var btIoManager    : BluetoothInputOutputManager? = null  // BT equivalent of ioManager

    private val stopExecutor   = Executors.newSingleThreadExecutor()
    private val configExecutor = Executors.newSingleThreadExecutor()
    // Dedicated executor for BT reconnect loop — separate so it never blocks stop/config ops
    @Volatile private var btReconnectExecutor: java.util.concurrent.ExecutorService? = null

    // Auto-save
    private var autoSaveTagCount   : Int  = SettingsManager.DEFAULT_AUTO_SAVE_TAGS
    private var autoSaveIntervalMin: Long = SettingsManager.DEFAULT_AUTO_SAVE_MINUTES.toLong()
    private var autoSaveMode       : Int  = SettingsManager.DEFAULT_AUTO_SAVE_MODE
    private var autoSaveExecutor   : ScheduledExecutorService? = null
    private var autoSaveTimerJob   : ScheduledFuture<*>? = null
    // Tags accumulated since the last auto-save — resets after each save (req 1)
    private val tagsSinceLastSave  = AtomicInteger(0)

    private var appContext: Context? = null
    private var activeAntennaType : String  = SettingsManager.ANTENNA_TYPE_JIETONG
    private var activeIsBluetooth : Boolean = false  // true=BT session, false=USB session

    // Winnix temperature tracking
    @Volatile private var winnixStartTemp     : String = ""
    // Generic temperature read via onNewData — used for both start and stop temp on BT
    @Volatile private var winnixTempResult    : String = ""
    private val winnixTempLatch     = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch?>(null)
    // Flag set by onNewData when 0x8D (stop confirmation) is received
    private val winnixStopConfirmed = java.util.concurrent.atomic.AtomicBoolean(false)

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
        // Se Winnix estava ativo e app fecha sem Stop, envia 0x8C para parar o módulo
        if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX && activePort != null) {
            try {
                activePort?.write(winnixBuildStopInventory(), 1000)
                Thread.sleep(200)
            } catch (_: Exception) {}
        }
        stopCapture()
        mainActivity = null
        btReconnectExecutor?.shutdownNow(); btReconnectExecutor = null
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
            tagsSinceLastSave.set(0)
            autoSaveTagCount    = SettingsManager.getAutoSaveTags(ctx)
            autoSaveIntervalMin = SettingsManager.getAutoSaveMinutes(ctx)
            autoSaveMode        = SettingsManager.getAutoSaveMode(ctx)

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

        activeIsBluetooth = (deviceName == BT_DEVICE_NAME)
        winnixResponseBuffer.clear()  // clear stale bytes from previous session

        if (deviceName == BT_DEVICE_NAME) {
            startBtConnection(deviceName, resuming)
        } else {
            startUsbConnection(deviceName, resuming)
        }
    }

    // ── USB connection ─────────────────────────────────────────────────────
    private fun startUsbConnection(deviceName: String, resuming: Boolean) {
        val ctx = appContext ?: return
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
            Log.e(TAG, "Error opening USB port", e)
            port.close(); connection.close()
            if (!resuming) CsvExporter.cancelSession()
            isPausedState.set(resuming)
            return
        }

        usbPort       = port
        usbConnection = connection
        val wrapper   = UsbSerialPortWrapper(port, connection)
        activePort    = wrapper

        if (!probeAntennaType(wrapper, activeAntennaType)) {
            val label = if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) "Winnix" else "Jietong"
            val msg   = "Antena $label não detectada. Verifique a conexão e o tipo configurado."
            Log.w(TAG, msg)
            isRunning.set(false)
            if (resuming) isPausedState.set(true)
            closePort()
            onWrongAntennaType?.invoke(msg)
            return
        }

        isRunning.set(true)
        if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
            startWinnixCapture(wrapper, ctx)
        } else {
            startJietongCapture(wrapper)
        }
        startAutoSaveTimer()
        updateNotification("Capturando…")
        onStatusChanged?.invoke(true)
        Log.i(TAG, "USB capture started ($activeAntennaType) on $deviceName @ $BAUD_RATE baud")
    }

    // ── Bluetooth connection ────────────────────────────────────────────────
    private fun startBtConnection(deviceName: String, resuming: Boolean, retryCount: Int = 0) {
        val ctx = appContext ?: return
        Log.i(TAG, "BT_CONNECT: startBtConnection called resuming=$resuming retryCount=$retryCount")
        configExecutor.submit {
            try {
                // Check BLUETOOTH_CONNECT permission (required on Android 12+)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    if (ctx.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        Log.e(TAG, "BT_CONNECT: BLUETOOTH_CONNECT permission not granted")
                        if (!resuming) CsvExporter.cancelSession()
                        isPausedState.set(resuming)
                        onWrongAntennaType?.invoke("Permissão Bluetooth não concedida.")
                        return@submit
                    }
                }

                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter   = btManager.adapter
                    ?: run { Log.e(TAG, "BT_CONNECT: Bluetooth not available"); if (!resuming) CsvExporter.cancelSession(); isPausedState.set(resuming); return@submit }

                Log.i(TAG, "BT_CONNECT: searching for paired device '$BT_DEVICE_NAME'")
                @Suppress("DEPRECATION")
                val btDevice = try {
                    adapter.bondedDevices?.firstOrNull { it.name == BT_DEVICE_NAME }
                } catch (se: SecurityException) {
                    Log.e(TAG, "BT_CONNECT: SecurityException on bondedDevices: ${se.message}")
                    if (!resuming) CsvExporter.cancelSession()
                    isPausedState.set(resuming)
                    return@submit
                } ?: run {
                    Log.e(TAG, "BT_CONNECT: device '$BT_DEVICE_NAME' not found in paired devices")
                    if (!resuming) CsvExporter.cancelSession()
                    isPausedState.set(resuming)
                    return@submit
                }

                Log.i(TAG, "BT_CONNECT: found device ${btDevice.address}, attempting socket connect")
                val btPort = BluetoothSerialPort(btDevice)
                try {
                    try { adapter.cancelDiscovery() } catch (_: SecurityException) {}
                    btPort.connect()
                    Log.i(TAG, "BT_CONNECT: socket connected successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "BT_CONNECT: socket connect FAILED (attempt ${retryCount+1}): ${e.message}")
                    btPort.close()
                    if (retryCount < BT_MAX_RETRIES) {
                        Log.i(TAG, "BT_CONNECT: retrying in ${BT_RETRY_DELAY_MS}ms (${retryCount+1}/$BT_MAX_RETRIES)")
                        Thread.sleep(BT_RETRY_DELAY_MS)
                        startBtConnection(deviceName, resuming, retryCount + 1)
                    } else {
                        Log.e(TAG, "BT_CONNECT: all $BT_MAX_RETRIES retries exhausted")
                        if (!resuming) CsvExporter.cancelSession()
                        isPausedState.set(resuming)
                        onWrongAntennaType?.invoke("Winnix_BT: falha ao conectar após $BT_MAX_RETRIES tentativas")
                    }
                    return@submit
                }

                activePort = btPort
                Log.i(TAG, "BT_CONNECT: running probe...")
                if (!probeAntennaType(btPort, SettingsManager.ANTENNA_TYPE_WINNIX)) {
                    Log.w(TAG, "BT_CONNECT: probe FAILED (attempt ${retryCount+1})")
                    btPort.close()
                    activePort = null
                    if (retryCount < BT_MAX_RETRIES) {
                        Log.i(TAG, "BT_CONNECT: retrying probe in ${BT_RETRY_DELAY_MS}ms")
                        Thread.sleep(BT_RETRY_DELAY_MS)
                        startBtConnection(deviceName, resuming, retryCount + 1)
                    } else {
                        isRunning.set(false)
                        if (resuming) isPausedState.set(true)
                        onWrongAntennaType?.invoke("Winnix_BT: módulo não respondeu após $BT_MAX_RETRIES tentativas.")
                    }
                    return@submit
                }

                Log.i(TAG, "BT_CONNECT: probe OK — starting Winnix capture")
                isRunning.set(true)
                startWinnixCaptureBt(btPort, ctx)
                startAutoSaveTimer()
                updateNotification("Capturando via BT…")
                onStatusChanged?.invoke(true)
                Log.i(TAG, "BT_CONNECT: capture started successfully on $BT_DEVICE_NAME")

            } catch (e: Exception) {
                Log.e(TAG, "BT_CONNECT: unexpected error: ${e.message}", e)
                if (!resuming) CsvExporter.cancelSession()
                isPausedState.set(resuming)
            }
        }
    }

    fun stopCapture() {
        // Handle two cases:
        // 1. Actively capturing (isRunning=true) — normal stop
        // 2. Paused with BT reconnect loop running (isRunning=false, isPaused=true) — stop loop + finalize
        val wasRunning = isRunning.compareAndSet(true, false)
        val wasPaused  = isPausedState.get()
        if (!wasRunning && !wasPaused) return

        isPausedState.set(false)  // stops the reconnect loop if running
        btReconnectExecutor?.shutdownNow(); btReconnectExecutor = null
        stopAutoSaveTimer()

        stopExecutor.submit {
            // 1. Para o inventário
            sendWinnixStop()

            // 2. Para o btIoManager via flag, lê temperatura, depois fecha porta
            val localBtIo = btIoManager
            val localIo   = ioManager
            btIoManager = null
            ioManager   = null

            // 3. Lê temperatura de encerramento
            // Usa latch via onNewData para AMBOS USB e BT:
            // SerialInputOutputManager (USB) e btIoManager (BT) ambos chamam onNewData
            // que preenche o latch quando 0x35 é detectado no winnixResponseBuffer.
            // Leitura direta (winnixReadTemperature) não funciona pois o IOManager
            // consome os bytes antes — mesmo problema no USB e no BT.
            val winnixStopTemp = if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
                val port = activePort
                if (port != null) winnixReadTemperatureBt(port) else ""
            } else ""

            // 4. Para IOManagers e fecha porta
            if (localBtIo != null) {
                closePort()        // interrompe stream.read() bloqueante → btIoManager sai via IOException
                localBtIo.stop()   // garante flag running=false
            } else {
                localIo?.stop()
                Thread.sleep(200)
                closePort()
            }

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
        isPausedState.set(false)  // stops reconnect loop if running
        btReconnectExecutor?.shutdownNow(); btReconnectExecutor = null
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
    // Jietong capture (USB only)
    // =========================================================================
    private fun startJietongCapture(wrapper: UsbSerialPortWrapper) {
        ioManager = SerialInputOutputManager(wrapper.rawPort(), object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val lat  = mainActivity?.getCurrentLatitude()  ?: ""
                val lon  = mainActivity?.getCurrentLongitude() ?: ""
                val brg  = mainActivity?.getCurrentBearing()   ?: ""
                val tags = jietongDecoder.feed(data, lat, lon, brg)
                if (tags.isNotEmpty()) {
                    tagBuffer.addAll(tags)
                    totalCount.addAndGet(tags.size)
                    val sinceLast = tagsSinceLastSave.addAndGet(tags.size)
                    if (sinceLast >= autoSaveTagCount) {
                        rescheduleTimerJob()
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
    // Winnix capture — USB (uses SerialInputOutputManager)
    // =========================================================================
    private fun startWinnixCapture(wrapper: UsbSerialPortWrapper, ctx: Context) {
        val antCount   = SettingsManager.getWinnixAntCount(ctx)
        val powerDbm   = SettingsManager.getWinnixPowerDbm(ctx)
        val workingMs  = SettingsManager.getWinnixWorkingMs(ctx)
        val invMode    = SettingsManager.getWinnixInventoryMode(ctx)
        val antennas   = (1..antCount).toList()
        val inactiveMs = 300

        winnixStartTemp = ""

        configExecutor.submit {
            try {
                winnixConfigSequence(wrapper, antennas, powerDbm, workingMs, invMode, inactiveMs)

                val startTemp = winnixReadTemperature(wrapper)
                winnixStartTemp = if (startTemp != null) "%.1f".format(java.util.Locale.US, startTemp) else ""
                Log.i(TAG, "Winnix start temperature: $winnixStartTemp°C")

                Thread.sleep(200)
                wrapper.purgeHwBuffers(false, true)
                wrapper.write(winnixBuildStartInventory(), 2000)
                Log.i(TAG, "Winnix USB inventory started")

                ioManager = SerialInputOutputManager(wrapper.rawPort(), makeWinnixListener()).also {
                    it.readTimeout  = 0
                    it.writeTimeout = 2000
                    Executors.newSingleThreadExecutor().submit(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Winnix USB startup error", e)
                handleRunError(e)
            }
        }
    }

    // =========================================================================
    // Winnix capture — Bluetooth (uses BluetoothInputOutputManager)
    // =========================================================================
    private fun startWinnixCaptureBt(btPort: BluetoothSerialPort, ctx: Context) {
        val antCount   = SettingsManager.getWinnixAntCount(ctx)
        val powerDbm   = SettingsManager.getWinnixPowerDbm(ctx)
        val workingMs  = SettingsManager.getWinnixWorkingMs(ctx)
        val invMode    = SettingsManager.getWinnixInventoryMode(ctx)
        val antennas   = (1..antCount).toList()
        val inactiveMs = 300

        winnixStartTemp = ""

        // Config runs on configExecutor — btPort is already connected at this point
        configExecutor.submit {
            try {
                winnixConfigSequence(btPort, antennas, powerDbm, workingMs, invMode, inactiveMs)

                // Start temperature — read BEFORE starting btIoManager (no competition)
                val startTemp = winnixReadTemperature(btPort)
                winnixStartTemp = if (startTemp != null) "%.1f".format(java.util.Locale.US, startTemp) else ""
                Log.i(TAG, "Winnix BT start temperature: $winnixStartTemp°C")

                Thread.sleep(200)
                btPort.purgeHwBuffers(false, true)
                btPort.write(winnixBuildStartInventory(), 2000)
                Log.i(TAG, "Winnix BT inventory started")

                // Guard: only assign btIoManager if still running
                // Prevents race where stopCapture() ran while configExecutor was still starting
                if (!isRunning.get()) {
                    Log.w(TAG, "BT capture aborted — stop was called during startup")
                    btPort.close()
                    return@submit
                }

                btIoManager = BluetoothInputOutputManager(btPort, object : BluetoothInputOutputManager.Listener {
                    override fun onNewData(data: ByteArray) = winnixOnNewData(data)
                    override fun onRunError(e: Exception)   = handleRunError(e)
                })
                Executors.newSingleThreadExecutor().submit(btIoManager)

            } catch (e: Exception) {
                Log.e(TAG, "Winnix BT startup error", e)
                handleRunError(e)
            }
        }
    }

    // =========================================================================
    // Shared Winnix helpers
    // =========================================================================

    /** Sends the full configuration sequence to the Winnix module via any port. */
    private fun winnixConfigSequence(
        port: ISerialPort, antennas: List<Int>,
        powerDbm: Int, workingMs: Int, invMode: Int, inactiveMs: Int
    ) {
        port.write(winnixBuildSetAntennas(antennas), 2000); Thread.sleep(500); port.purgeHwBuffers(false, true)
        antennas.forEach { ant ->
            port.write(winnixBuildSetPower(ant, powerDbm), 2000); Thread.sleep(500); port.purgeHwBuffers(false, true)
        }
        for (ant in 1..4) {
            val ms = if (ant <= antennas.size) workingMs else inactiveMs
            port.write(winnixBuildSetWorkingTime(ant, ms), 2000); Thread.sleep(500); port.purgeHwBuffers(false, true)
        }
        port.write(winnixBuildSetInventoryMode(invMode), 2000); Thread.sleep(500); port.purgeHwBuffers(false, true)
        port.write(winnixBuildSetStatusLed(), 2000); Thread.sleep(500); port.purgeHwBuffers(false, true)
    }

    /** Returns the shared onNewData handler for Winnix (USB and BT use the same logic). */
    private fun makeWinnixListener(): SerialInputOutputManager.Listener =
        object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) = winnixOnNewData(data)
            override fun onRunError(e: Exception)   = handleRunError(e)
        }

    // Accumulation buffer for detecting fragmented 0x8D/0x35 frames from BT
    private val winnixResponseBuffer = java.util.concurrent.CopyOnWriteArrayList<Byte>()

    private fun winnixOnNewData(data: ByteArray) {
        // Accumulate bytes — BT can fragment frames across multiple onNewData calls
        data.forEach { winnixResponseBuffer.add(it) }
        // Keep buffer bounded — max 256 bytes (response frames are small)
        while (winnixResponseBuffer.size > 256) winnixResponseBuffer.removeAt(0)

        val buf = winnixResponseBuffer.toByteArray()

        // Check for stop confirmation (0x8D)
        if (!winnixStopConfirmed.get()) {
            for (i in 0 until buf.size - 4) {
                if (buf[i] == 0xA5.toByte() && buf[i+1] == 0x5A.toByte()
                    && buf[i+4] == 0x8D.toByte()) {
                    Log.i(TAG, "Winnix stop confirmed (0x8D) via onNewData")
                    winnixStopConfirmed.set(true)
                    winnixResponseBuffer.clear()
                    break
                }
            }
        }

        // Check for temperature response (0x35) — fills latch for winnixReadTemperatureBt()
        val tempLatch = winnixTempLatch.get()
        if (tempLatch != null && tempLatch.count > 0) {
            for (i in 0 until buf.size - 7) {
                if (buf[i] == 0xA5.toByte() && buf[i+1] == 0x5A.toByte()
                    && buf[i+4] == 0x35.toByte() && buf[i+5] == 0x01.toByte()) {
                    val raw    = ((buf[i+6].toInt() and 0xFF) shl 8) or (buf[i+7].toInt() and 0xFF)
                    val signed = if (raw > 32767) raw - 65536 else raw
                    val temp   = signed / 100.0f
                    winnixTempResult = "%.1f".format(java.util.Locale.US, temp)
                    Log.i(TAG, "Temperature 0x35 captured via onNewData: $winnixTempResult°C")
                    tempLatch.countDown()
                    winnixResponseBuffer.clear()
                    break
                }
            }
        }

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
            totalCount.addAndGet(processedTags.size)
            val sinceLast = tagsSinceLastSave.addAndGet(processedTags.size)
            if (sinceLast >= autoSaveTagCount) {
                rescheduleTimerJob()
                autoSaveExecutor?.submit { flushBufferToDisk() }
            }
        }
    }

    private fun closePort() {
        synchronized(this) {
            try { activePort?.close() } catch (_: Exception) {}
            activePort    = null
            usbPort       = null
            usbConnection = null
        }
    }

    /**
     * Sends stop inventory (0x8C) and waits for module confirmation (0x8D).
     * The 0x8D response is detected by winnixOnNewData which sets winnixStopConfirmed.
     * Works for both USB and BT — IOManager/btIoManager deliver the response via onNewData.
     * Retries up to 3 times if no confirmation received within 1s per attempt.
     */
    private fun sendWinnixStop() {
        val port = activePort ?: return
        winnixStopConfirmed.set(false)
        try {
            Log.i(TAG, "Sending stop inventory (0x8C)")
            port.write(winnixBuildStopInventory(), 2000)
            // Wait up to 3s for 0x8D confirmation via onNewData
            // Send only ONCE — sending multiple 0x8C confuses the module
            val deadline = System.currentTimeMillis() + 3000L
            while (!winnixStopConfirmed.get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            if (winnixStopConfirmed.get()) {
                Log.i(TAG, "Winnix stop confirmed (0x8D)")
            } else {
                Log.w(TAG, "Winnix stop: no 0x8D within 3s — proceeding anyway")
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendWinnixStop error: ${e.message}")
        }
    }

    private fun probeAntennaType(port: ISerialPort, antennaType: String): Boolean {
        return try {
            if (antennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
                port.write(winnixBuildStopInventory(), 1000)
                Thread.sleep(300)
                port.purgeHwBuffers(false, true)
                Thread.sleep(100)
            }

            val (probeCmd, expectedHeader) = if (antennaType == SettingsManager.ANTENNA_TYPE_WINNIX) {
                byteArrayOf(0xA5.toByte(), 0x5A.toByte(), 0x00, 0x08, 0x02, 0x0A, 0x0D, 0x0A) to
                        byteArrayOf(0xA5.toByte(), 0x5A.toByte())
            } else {
                byteArrayOf(0x43, 0x4D, 0x01, 0x02, 0x02, 0x00, 0x00, 0x00, 0x00) to
                        byteArrayOf(0x43, 0x4D, 0x01, 0x03)
            }

            port.write(probeCmd, 2000)

            val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
            val readBuf  = ByteArray(64)
            val response = mutableListOf<Byte>()

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
            Log.i(TAG, "Probe OK — $antennaType via ${port.portName}")
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

    private fun winnixBuildSetStatusLed(): ByteArray {
        return winnixBuildFrame(0x7A, byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
    }

    /**
     * mode: table mode 1-5 (as defined in SettingsManager WINNIX_INV_MODE_*).
     * Protocol DByte0 values are 0-4, with a -1 offset from the table numbering.
     * Verified against doc example: Fast read (table Mode 2) = DByte0 0x01.
     *   Multi-tag (1) → DByte0=0x00
     *   Fast read (2) → DByte0=0x01
     *   Ultra Low Power (3) → DByte0=0x02
     *   Adaptive (5) → DByte0=0x04
     */
    private fun winnixBuildSetInventoryMode(mode: Int): ByteArray {
        val dbyte0 = (mode - 1) and 0xFF
        return winnixBuildFrame(0x76, byteArrayOf(0x00, dbyte0.toByte()))
    }

    /**
     * Read temperature for BT sessions — uses latch filled by onNewData.
     * The btIoManager delivers all bytes via onNewData, so we cannot use
     * winnixReadTemperature (available() polling) which competes with btIoManager.
     * Instead: set latch, send 0x34, wait for onNewData to detect 0x35 and signal.
     * Called ONLY when btIoManager is active (during capture or stop temp before port close).
     */
    private fun winnixReadTemperatureBt(port: ISerialPort): String {
        winnixTempResult = ""
        val latch = java.util.concurrent.CountDownLatch(1)
        winnixTempLatch.set(latch)
        return try {
            port.write(winnixBuildGetTemperature(), 2000)
            val received = latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (received && winnixTempResult.isNotEmpty()) {
                Log.i(TAG, "BT temperature via latch: $winnixTempResult°C")
                winnixTempResult
            } else {
                Log.w(TAG, "BT temperature: no response within 2s")
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "BT temperature read error: ${e.message}")
            ""
        } finally {
            winnixTempLatch.set(null)
        }
    }
    private fun winnixReadTemperature(port: ISerialPort): Float? {
        return try {
            try { port.purgeHwBuffers(true, false) } catch (_: Exception) {}
            Thread.sleep(100)

            port.write(winnixBuildGetTemperature(), 2000)

            val collected = mutableListOf<Byte>()
            val deadline  = System.currentTimeMillis() + 2000L  // 2s timeout
            val buf       = ByteArray(64)
            while (System.currentTimeMillis() < deadline) {
                val n = try { port.read(buf, 200) } catch (_: Exception) { 0 }
                for (i in 0 until n) collected.add(buf[i])
                // Search for 0x35 response anywhere in collected bytes
                val data = collected.toByteArray()
                for (idx in 0 until data.size - 7) {
                    if (data[idx]   == 0xA5.toByte() &&
                        data[idx+1] == 0x5A.toByte() &&
                        data[idx+4] == 0x35.toByte() &&
                        data[idx+5] == 0x01.toByte()) {
                        val raw    = ((data[idx+6].toInt() and 0xFF) shl 8) or (data[idx+7].toInt() and 0xFF)
                        val signed = if (raw > 32767) raw - 65536 else raw
                        val temp   = signed / 100.0f
                        Log.i(TAG, "Temperature read: $temp°C")
                        return temp
                    }
                }
            }
            Log.w(TAG, "Temperature read: 0x35 not found in ${collected.size} bytes within timeout")
            null
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
        ioManager?.stop();   ioManager   = null
        btIoManager?.stop(); btIoManager = null
        closePort()
        updateNotification("Sinal perdido — ${totalCount.get()} tags aguardando salvamento")
        onCaptureError?.invoke()

        // Only start BT reconnect loop if this was a BT session
        // USB sessions reconnect via ACTION_USB_DEVICE_ATTACHED in MainActivity
        if (activeIsBluetooth && activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX &&
            btIoManager == null && activePort == null) {
            Log.i(TAG, "BT_RECONNECT: BT session lost — starting reconnect loop")
            startBtReconnectLoop()
        } else {
            Log.i(TAG, "BT_RECONNECT: not starting loop — isBT=$activeIsBluetooth antenna=$activeAntennaType btIo=$btIoManager port=$activePort")
        }
    }

    /**
     * Retry loop that periodically calls startCapture() with BT_DEVICE_NAME.
     * Conditions to keep running:
     *   - isPausedState == true  (not stopped by user)
     *   - isRunning == false     (not already capturing)
     * Stops automatically when:
     *   - User clicks Stop → stopCapture() → isPausedState = false
     *   - Reconnect succeeds → startCapture() → isRunning = true
     *   - onDestroy() → btReconnectExecutor.shutdownNow()
     */
    private fun startBtReconnectLoop() {
        btReconnectExecutor?.shutdownNow()
        btReconnectExecutor = Executors.newSingleThreadExecutor()
        btReconnectExecutor?.submit {
            Log.i(TAG, "BT_RECONNECT: loop started, will retry every ${BT_RECONNECT_INTERVAL_MS}ms")
            var attempt = 0
            while (isPausedState.get() && !isRunning.get()) {
                attempt++
                Log.i(TAG, "BT_RECONNECT: waiting ${BT_RECONNECT_INTERVAL_MS}ms before attempt #$attempt")
                try { Thread.sleep(BT_RECONNECT_INTERVAL_MS) } catch (_: InterruptedException) { break }

                if (!isPausedState.get() || isRunning.get()) {
                    Log.i(TAG, "BT_RECONNECT: loop exiting — isPaused=${isPausedState.get()} isRunning=${isRunning.get()}")
                    break
                }

                // Call startCapture() exactly like the Start button does.
                // startCapture() handles isPausedState.getAndSet(false) → resuming=true internally.
                // This is the exact same path that works when the user clicks Start.
                Log.i(TAG, "BT_RECONNECT: attempt #$attempt — calling startCapture (same as Start button)")
                startCapture(BT_DEVICE_NAME)

                // Wait for startCapture to complete (runs on configExecutor)
                Log.i(TAG, "BT_RECONNECT: waiting for result...")
                try { Thread.sleep(BT_RECONNECT_INTERVAL_MS * 2) } catch (_: InterruptedException) { break }
                Log.i(TAG, "BT_RECONNECT: after attempt #$attempt — isPaused=${isPausedState.get()} isRunning=${isRunning.get()}")
            }
            Log.i(TAG, "BT_RECONNECT: loop ended after $attempt attempts")
        }
    }

    // =========================================================================
    // Auto-save
    // =========================================================================
    private fun startAutoSaveTimer() {
        autoSaveExecutor = Executors.newSingleThreadScheduledExecutor()
        rescheduleTimerJob()
    }

    /**
     * (Re)schedules the time-based auto-save job starting from now.
     * Called on initial start AND whenever a tag-count-triggered save happens,
     * so the time counter resets — preventing a near-immediate duplicate
     * save right after a count-triggered one.
     */
    private fun rescheduleTimerJob() {
        autoSaveTimerJob?.cancel(false)
        autoSaveTimerJob = autoSaveExecutor?.scheduleWithFixedDelay(
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

        // Req 4: skip if no new tags to save
        if (batch.isEmpty()) {
            Log.d(TAG, "Auto-save skipped — no new tags")
            return
        }

        // Reset the per-save counter (Req 1: resets the other counter)
        tagsSinceLastSave.set(0)

        val ctx = appContext ?: return

        if (autoSaveMode == SettingsManager.AUTO_SAVE_MODE_NEW_FILE) {
            // Req 2: new file mode — finalize current session and start a new one
            val fileName = CsvExporter.finalizeSession(batch)
            Log.i(TAG, "Auto-save (new file): $fileName — ${batch.size} tags")
            // Start a new session for the next batch
            val prefix   = if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX) "winnix" else "jietong"
            val newFile  = CsvExporter.startSession(ctx, prefix)
            Log.i(TAG, "New session started: $newFile")
            updateNotification("Capturando… (${totalCount.get()} tags)")
            onAutoSaved?.invoke(totalCount.get())
        } else {
            // Req 2: append mode — default, existing behavior
            val ok = CsvExporter.appendTags(batch)
            if (ok) {
                Log.i(TAG, "Auto-save (append): ${batch.size} tags, total: ${totalCount.get()}")
                updateNotification("Capturando… (${totalCount.get()} tags)")
                onAutoSaved?.invoke(totalCount.get())
            } else {
                Log.e(TAG, "Auto-save failed — reinserting ${batch.size} tags")
                tagBuffer.addAll(batch)
                tagsSinceLastSave.addAndGet(batch.size)  // restore counter on failure
            }
        }
    }

    private fun drainAndFinalize(winnixStopTemp: String = ""): String? {
        val lastBatch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) tagBuffer.poll()?.let { lastBatch.add(it) }

        // Req 4: if no tags at all this session, cancel — don't create empty CSV
        if (totalCount.get() == 0 && lastBatch.isEmpty()) {
            Log.i(TAG, "Session cancelled — no tags read")
            CsvExporter.cancelSession()
            return null
        }

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
        const val BT_DEVICE_NAME    = "Winnix_BT"
        private const val BT_MAX_RETRIES          = 3
        private const val BT_RETRY_DELAY_MS       = 2000L
        private const val BT_RECONNECT_INTERVAL_MS= 5000L  // retry interval when session is paused
    }
}