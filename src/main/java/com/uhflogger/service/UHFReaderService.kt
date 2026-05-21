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
import com.uhflogger.MainActivity
import com.uhflogger.decoder.ProtocolDecoder
import com.uhflogger.model.TagRecord
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class UHFReaderService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): UHFReaderService = this@UHFReaderService
    }

    private val binder    = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val decoder   = ProtocolDecoder()
    private val tagBuffer = mutableListOf<TagRecord>()
    private val isRunning = AtomicBoolean(false)

    private var usbPort       : UsbSerialPort? = null
    private var usbConnection : UsbDeviceConnection? = null
    private var ioManager     : SerialInputOutputManager? = null

    var onStatusChanged: ((Boolean) -> Unit)? = null
    var onCaptureError : (() -> Unit)?        = null
    var mainActivity   : MainActivity?        = null

    override fun onCreate() {
        super.onCreate()
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

    fun startCapture(deviceName: String) {
        if (isRunning.get()) return
        decoder.reset()

        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        val driver = availableDrivers.firstOrNull { it.device.deviceName == deviceName }
            ?: run { Log.e(TAG, "Device not found: $deviceName"); return }

        val connection = usbManager.openDevice(driver.device)
            ?: run { Log.e(TAG, "USB permission denied"); return }

        val port = driver.ports[0]
        try {
            port.open(connection)
            port.setParameters(BAUD_RATE, UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port.dtr = true
            port.rts = true
        } catch (e: Exception) {
            Log.e(TAG, "Error opening serial port", e)
            port.close()
            connection.close()
            return
        }

        usbPort       = port
        usbConnection = connection
        isRunning.set(true)

        ioManager = SerialInputOutputManager(port, object : SerialInputOutputManager.Listener {
            override fun onNewData(data: ByteArray) {
                val lat = mainActivity?.getCurrentLatitude()  ?: ""
                val lon = mainActivity?.getCurrentLongitude() ?: ""
                val brg = mainActivity?.getCurrentBearing()   ?: ""
                val tags = decoder.feed(data, lat, lon, brg)
                if (tags.isNotEmpty()) {
                    synchronized(tagBuffer) { tagBuffer.addAll(tags) }
                }
            }

            override fun onRunError(e: Exception) {
                Log.e(TAG, "Serial read error", e)
                // Se isRunning já é false, o stop foi intencional — não dispara onCaptureError
                if (!isRunning.compareAndSet(true, false)) return
                ioManager?.stop()
                ioManager = null
                try { usbPort?.close() }       catch (_: Exception) {}
                try { usbConnection?.close() } catch (_: Exception) {}
                usbPort       = null
                usbConnection = null
                updateNotification("Sinal perdido — ${tagBuffer.size} tags aguardando salvamento")
                onCaptureError?.invoke()
            }
        }).also {
            it.readTimeout  = 0
            it.writeTimeout = 2000
            Executors.newSingleThreadExecutor().submit(it)
        }

        updateNotification("Capturando… (${tagBuffer.size} tags)")
        onStatusChanged?.invoke(true)
        Log.i(TAG, "Capture started on $deviceName @ $BAUD_RATE baud")
    }

    fun stopCapture() {
        if (!isRunning.compareAndSet(true, false)) return

        ioManager?.stop()
        ioManager = null
        try { usbPort?.close() }       catch (_: Exception) {}
        try { usbConnection?.close() } catch (_: Exception) {}
        usbPort       = null
        usbConnection = null

        updateNotification("Captura encerrada — ${tagBuffer.size} tags")
        onStatusChanged?.invoke(false)
        Log.i(TAG, "Capture stopped. Total tags: ${tagBuffer.size}")
    }

    fun isCapturing(): Boolean = isRunning.get()

    fun flushTags(): List<TagRecord> = synchronized(tagBuffer) {
        val copy = tagBuffer.toList()
        tagBuffer.clear()
        copy
    }

    fun tagCount(): Int = synchronized(tagBuffer) { tagBuffer.size }

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
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val TAG        = "UHFReaderService"
        private const val CHANNEL_ID = "uhf_logger_channel"
        private const val NOTIF_ID   = 1001
        private const val BAUD_RATE  = 115200

        const val ACTION_START      = "com.uhflogger.START"
        const val ACTION_STOP       = "com.uhflogger.STOP"
        const val EXTRA_DEVICE_NAME = "device_name"
    }
}