package com.uhflogger

import android.Manifest
import android.app.AlertDialog
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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.uhflogger.databinding.ActivityMainBinding
import com.uhflogger.service.UHFReaderService
import com.uhflogger.SettingsManager
import com.uhflogger.SettingsActivity
import com.uhflogger.drive.DriveHelper
import com.uhflogger.drive.DriveMonitorService

// NOTA: GNSS e bússola foram movidos para dentro do UHFReaderService (foreground
// service). Antes viviam aqui na Activity e travavam sempre que a tela apagava
// ou o app ia para background — a MainActivity só cuida de UI/permissões agora.
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_LOCATION       = 101
        private const val REQ_BT_PERMISSION  = 102
    }

    private lateinit var binding: ActivityMainBinding
    private var readerService: UHFReaderService? = null
    private var serviceBound = false
    private var currentToast: Toast? = null
    private var stoppedByError = false

    private val uiHandler = Handler(Looper.getMainLooper())
    private val tagCountUpdater = object : Runnable {
        override fun run() {
            val count = readerService?.tagCount() ?: 0
            binding.tvTagCount.text = "%,d".format(count)
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? UHFReaderService.LocalBinder ?: return
            readerService = localBinder.getService()
            serviceBound  = true
            readerService?.mainActivity = this@MainActivity
            readerService?.onStatusChanged = { capturing ->
                runOnUiThread {
                    if (capturing) stoppedByError = false  // reconnect succeeded — normal stop from now on
                    setCapturingState(capturing)
                }
            }
            readerService?.onCaptureError = {
                runOnUiThread {
                    stoppedByError = true
                    setCapturingState(false)
                    binding.btnStart.isEnabled = false
                    binding.btnStop.isEnabled  = true
                    updateButtonColors()
                    toast("Sinal da antena perdido — clique em STOP para salvar os dados")
                }
            }
            readerService?.onWrongAntennaType = { msg ->
                runOnUiThread {
                    // Probe failed — antenna type mismatch or not responding
                    // Do NOT change stoppedByError or button state — no capture was started
                    toast(msg)
                }
            }
            readerService?.onAutoSaved = { total ->
                runOnUiThread {
                    toast("Auto-save: $total tags gravadas")
                }
            }
            readerService?.onStopComplete = { fileName, tagCount ->
                runOnUiThread {
                    if (fileName != null) toast("CSV salvo: $fileName\n($tagCount tags)")
                    else toast("Nenhuma tag para exportar")
                }
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDeviceList()
                    val deviceName = binding.spinnerDevices.tag?.toString()
                    if (readerService?.isPaused() == true && deviceName != null) {
                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
                        val device = usbManager.deviceList.values
                            .firstOrNull { it.deviceName == deviceName }
                        if (device != null) {
                            if (usbManager.hasPermission(device)) {
                                stoppedByError = false
                                readerService?.startCapture(deviceName)
                                toast("Antena reconectada — retomando leitura")
                            } else {
                                requestUsbPermission(usbManager, device)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDeviceList()
                }
            }
        }
    }

    /**
     * Bluetooth state receiver:
     * - STATE_ON: Bluetooth activated → refresh list to show Winnix_BT if paired
     * - STATE_OFF: Bluetooth deactivated → refresh list to remove BT device
     * - ACL_CONNECTED: Winnix_BT reconnected while paused → resume capture
     * - ACL_DISCONNECTED: Winnix_BT disconnected → handleRunError already handled it
     */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(
                        android.bluetooth.BluetoothAdapter.EXTRA_STATE,
                        android.bluetooth.BluetoothAdapter.ERROR
                    )
                    if (state == android.bluetooth.BluetoothAdapter.STATE_ON ||
                        state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                        refreshDeviceList()
                    }
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val device = intent.getParcelableExtra<android.bluetooth.BluetoothDevice>(
                        android.bluetooth.BluetoothDevice.EXTRA_DEVICE
                    )
                    val deviceName = try { device?.name } catch (_: SecurityException) { null }
                    if (deviceName == UHFReaderService.BT_DEVICE_NAME &&
                        readerService?.isPaused() == true) {
                        android.os.Handler(mainLooper).postDelayed({
                            if (readerService?.isPaused() == true) {
                                stoppedByError = false
                                readerService?.startCapture(UHFReaderService.BT_DEVICE_NAME)
                                toast("Winnix_BT reconectado — retomando leitura")
                            }
                        }, 2000)
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
        requestLocationPermission()
        requestBluetoothPermission()
        ensureBatteryOptimizationExemption()
        registerUsbReceiver()
        bindToService()
        setupButtons()
        refreshDeviceList()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(tagCountUpdater)
        refreshDeviceList()
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tagCountUpdater)
        // GNSS/bússola NÃO são mais tocados aqui — vivem no UHFReaderService e
        // continuam ativos com a tela apagada, independente do onPause da Activity.
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        if (serviceBound) {
            readerService?.mainActivity = null
            unbindService(serviceConnection)
        }
    }

    private fun requestBluetoothPermission() {
        // BLUETOOTH_CONNECT required on Android 12+ to access paired device names
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(
                        android.Manifest.permission.BLUETOOTH_CONNECT,
                        android.Manifest.permission.BLUETOOTH_SCAN
                    ),
                    REQ_BT_PERMISSION
                )
            }
        }
    }

    /**
     * Pede ACCESS_FINE_LOCATION/COARSE (necessária pro Service usar GPS) e, no
     * Android 10+, também ACCESS_BACKGROUND_LOCATION em uma segunda etapa — o
     * sistema exige que ela seja pedida separadamente, depois da foreground.
     */
    private fun requestLocationPermission() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LOCATION)
        } else {
            requestBackgroundLocationIfNeeded()
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_LOCATION
            )
        }
    }

    /**
     * Sem isso, o Android (Doze/App Standby) pode suspender GPS, sensores e até
     * a thread de leitura serial quando a tela fica apagada por muito tempo —
     * foi exatamente o que causou o congelamento de GNSS/bearing que investigamos.
     * Pede a isenção automaticamente ao abrir o app, explicando o motivo.
     */
    private fun ensureBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle("Otimização de bateria")
            .setMessage(
                "Este app registra GPS, bússola e tags continuamente, inclusive " +
                        "com a tela desligada por longos períodos. Para evitar falhas, " +
                        "permita que ele rode sem restrições de bateria na próxima tela."
            )
            .setCancelable(false)
            .setPositiveButton("Permitir") { _, _ ->
                try {
                    startActivity(Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    ))
                } catch (_: Exception) {
                    // Alguns fabricantes (Xiaomi, Huawei, Samsung...) bloqueiam este
                    // intent — nesse caso oriente o usuário a liberar manualmente
                    // nas configurações de bateria específicas do aparelho.
                    toast("Abra as configurações de bateria do aparelho e libere o app manualmente")
                }
            }
            .setNegativeButton("Agora não", null)
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION)      requestBackgroundLocationIfNeeded()
        if (requestCode == REQ_BT_PERMISSION) refreshDeviceList()  // show Winnix_BT if now paired
    }

    private fun setupButtons() {
        binding.btnStart.setOnClickListener    { onStartClicked() }
        binding.btnStop.setOnClickListener     { onStopClicked() }
        binding.btnSettings.setOnClickListener { onSettingsClicked() }
    }

    private fun onSettingsClicked() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun onStartClicked() {
        val deviceName = binding.spinnerDevices.tag?.toString()
            ?: run { toast("Nenhum dispositivo selecionado"); return }

        // Bluetooth device — no USB permission needed, connect directly
        if (deviceName == UHFReaderService.BT_DEVICE_NAME) {
            startReaderService(deviceName)
            return
        }

        // USB device — check permission as before
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
            ?: run { toast("Dispositivo não encontrado"); return }
        if (!usbManager.hasPermission(device)) requestUsbPermission(usbManager, device)
        else startReaderService(deviceName)
    }

    private fun onStopClicked() {
        if (stoppedByError) {
            stoppedByError = false
            setCapturingState(false)
            readerService?.saveAfterError()
        } else {
            readerService?.stopCapture()
            // stopCapture runs on background thread
            // UI update (setCapturingState) comes via onStatusChanged callback
            // Toast comes via onStopComplete callback
        }
    }

    private fun bindToService() {
        try {
            val intent = Intent(this, UHFReaderService::class.java)
            startForegroundService(intent)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            toast("Erro ao iniciar serviço: ${e.message}")
        }
    }

    private fun startReaderService(deviceName: String) {
        stoppedByError = false
        try {
            readerService?.startCapture(deviceName)
        } catch (e: Exception) {
            toast("Erro ao iniciar captura: ${e.message}")
        }
    }

    private fun refreshDeviceList() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers    = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)

        // Build device name list: USB devices first, then BT if paired
        val deviceNames = mutableListOf<String>()
        val labels      = mutableListOf<String>()

        // USB devices
        for (driver in drivers) {
            val dev = driver.device
            val vid = dev.vendorId.toString(16).uppercase().padStart(4, '0')
            val pid = dev.productId.toString(16).uppercase().padStart(4, '0')
            val mfr = dev.manufacturerName ?: "Desconhecido"
            deviceNames.add(dev.deviceName)
            labels.add("USB — VID:$vid / PID:$pid — $mfr")
        }

        // Bluetooth — check if Winnix_BT is paired
        try {
            val btManager = getSystemService(BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter   = btManager?.adapter
            @Suppress("DEPRECATION")
            val btDevice  = adapter?.bondedDevices?.firstOrNull {
                it.name == UHFReaderService.BT_DEVICE_NAME
            }
            if (btDevice != null) {
                deviceNames.add(UHFReaderService.BT_DEVICE_NAME)
                labels.add("BT — ${UHFReaderService.BT_DEVICE_NAME} (${btDevice.address})")
            }
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT permission not granted yet — BT device won't appear
        } catch (_: Exception) {}

        if (deviceNames.isEmpty()) {
            labels.add("Nenhum dispositivo")
        }

        val adapter = object : ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_item, labels) {
            override fun getView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getView(pos, v, parent)
                (view as? android.widget.TextView)?.setTextColor(android.graphics.Color.parseColor("#111111"))
                return view
            }
            override fun getDropDownView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val view = super.getDropDownView(pos, v, parent)
                (view as? android.widget.TextView)?.apply {
                    setTextColor(android.graphics.Color.parseColor("#111111"))
                    setBackgroundColor(android.graphics.Color.WHITE)
                }
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDevices.adapter = adapter
        binding.spinnerDevices.setBackgroundResource(R.drawable.bg_spinner_white)
        binding.spinnerDevices.tag = if (deviceNames.isEmpty()) null else deviceNames[0]

        // Keep spinner selection in sync with deviceNames list
        binding.spinnerDevices.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                binding.spinnerDevices.tag = deviceNames.getOrNull(pos)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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

        // Bluetooth receiver — no export restriction needed for system BT broadcasts
        val btFilter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        registerReceiver(btReceiver, btFilter)
    }

    private fun setCapturingState(capturing: Boolean) {
        binding.btnStart.isEnabled       = !capturing
        binding.btnStop.isEnabled        = capturing
        binding.btnSettings.isEnabled    = !capturing
        binding.spinnerDevices.isEnabled = !capturing

        binding.tvStatus.text = if (capturing) "Lendo" else "Parado"
        binding.tvStatus.setBackgroundResource(
            if (capturing) R.drawable.bg_badge_green else R.drawable.bg_badge_gray
        )
        updateButtonColors()
    }

    /** Cores sempre derivadas do isEnabled — fonte única de verdade */
    private fun updateButtonColors() {
        binding.btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (binding.btnStart.isEnabled) 0xFF2E7D32.toInt() else 0xFFA5D6A7.toInt()
        )
        binding.btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (binding.btnStop.isEnabled) 0xFFC62828.toInt() else 0xFFEF9A9A.toInt()
        )
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun toast(msg: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_LONG).also {
            it.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 120)
            it.show()
        }
    }

}