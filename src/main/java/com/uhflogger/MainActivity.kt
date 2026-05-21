package com.uhflogger

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.uhflogger.databinding.ActivityMainBinding
import com.uhflogger.service.UHFReaderService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var readerService: UHFReaderService? = null
    private var serviceBound = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tagCountUpdater = object : Runnable {
        override fun run() {
            val count = readerService?.tagCount() ?: 0
            binding.tvTagCount.text = "Tags capturadas: $count"
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? UHFReaderService.LocalBinder ?: return
            readerService = localBinder.getService()
            serviceBound  = true
            readerService?.onStatusChanged = { capturing ->
                runOnUiThread { setCapturingState(capturing) }
            }
            setCapturingState(readerService?.isCapturing() == true)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            readerService = null
            serviceBound  = false
        }
    }

    private val ACTION_USB_PERMISSION = "com.uhflogger.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { startReaderService(it.deviceName) }
                    } else {
                        toast("Permissão USB negada")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDeviceList()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDeviceList()
                    if (readerService?.isCapturing() == true) {
                        readerService?.stopCapture()
                        toast("Dispositivo USB desconectado")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermission()
        registerUsbReceiver()
        bindToService()
        setupButtons()
        refreshDeviceList()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(tagCountUpdater)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tagCountUpdater)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        if (serviceBound) unbindService(serviceConnection)
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener { onStartClicked() }
        binding.btnStop.setOnClickListener  { onStopClicked() }
    }

    private fun onStartClicked() {
        val deviceName = binding.spinnerDevices.tag?.toString()
            ?: run { toast("Nenhum dispositivo USB selecionado"); return }

        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: run { toast("Dispositivo não encontrado"); return }

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(usbManager, device)
        } else {
            startReaderService(deviceName)
        }
    }

    private fun onStopClicked() {
        readerService?.stopCapture()
        val tags = readerService?.flushTags() ?: emptyList()
        if (tags.isEmpty()) {
            toast("Nenhuma tag para exportar")
            return
        }
        val fileName = CsvExporter.export(this, tags)
        if (fileName != null) {
            toast("CSV salvo em Downloads/$fileName\n(${tags.size} tags)")
        } else {
            toast("Falha ao exportar CSV")
        }
    }

    private fun bindToService() {
        val intent = Intent(this, UHFReaderService::class.java)
        startForegroundService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun startReaderService(deviceName: String) {
        readerService?.startCapture(deviceName)
    }

    private fun refreshDeviceList() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        if (drivers.isEmpty()) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("Nenhum dispositivo USB"))
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerDevices.adapter = adapter
            binding.spinnerDevices.tag = null
            return
        }

        val labels = drivers.map { driver ->
            val dev = driver.device
            val vid = dev.vendorId.toString(16).uppercase().padStart(4, '0')
            val pid = dev.productId.toString(16).uppercase().padStart(4, '0')
            val mfr = dev.manufacturerName ?: "Desconhecido"
            "VID:$vid / PID:$pid — $mfr"
        }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevices.adapter = adapter
        binding.spinnerDevices.tag = drivers[0].device.deviceName
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    private fun setCapturingState(capturing: Boolean) {
        binding.btnStart.isEnabled           = !capturing
        binding.btnStop.isEnabled            = capturing
        binding.spinnerDevices.isEnabled     = !capturing
        binding.ledStatus.setImageResource(
            if (capturing) R.drawable.led_green else R.drawable.led_gray
        )
        binding.tvStatus.text = if (capturing) "LENDO" else "PARADO"
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
