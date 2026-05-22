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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private var readerService: UHFReaderService? = null
    private var serviceBound = false
    private var currentToast: Toast? = null

    // GPS
    private lateinit var locationManager: LocationManager
    private var currentLocation: Location? = null
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { currentLocation = location }
        override fun onProviderEnabled(provider: String)   {}
        override fun onProviderDisabled(provider: String)  {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    // Bússola
    private lateinit var sensorManager: SensorManager
    private var accelerometerData = FloatArray(3)
    private var magnetometerData  = FloatArray(3)
    private var currentBearing    = Float.NaN

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
            readerService?.mainActivity = this@MainActivity
            readerService?.onStatusChanged = { capturing ->
                runOnUiThread { setCapturingState(capturing) }
            }
            readerService?.onCaptureError = {
                runOnUiThread {
                    setCapturingState(false)
                    binding.btnStop.isEnabled = true
                    toast("Sinal da antena perdido — clique em STOP para salvar os dados")
                }
            }

            readerService?.onAutoSaved = { total ->
                runOnUiThread {
                    toast("Auto-save: $total tags gravadas")
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
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> refreshDeviceList()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDeviceList()
                    // onRunError já tratou a desconexão
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager   = getSystemService(SENSOR_SERVICE)  as SensorManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        requestNotificationPermission()
        requestLocationPermission()
        registerUsbReceiver()
        bindToService()
        setupButtons()
        refreshDeviceList()
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(tagCountUpdater)
        refreshDeviceList()
        startLocationUpdates()
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tagCountUpdater)
        sensorManager.unregisterListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        locationManager.removeUpdates(locationListener)
        unregisterReceiver(usbReceiver)
        if (serviceBound) {
            readerService?.mainActivity = null
            unbindService(serviceConnection)
        }
    }

    // Bússola
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> accelerometerData = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerData  = event.values.clone()
        }
        val rotationMatrix    = FloatArray(9)
        val orientationAngles = FloatArray(3)
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerData, magnetometerData)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            currentBearing = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (currentBearing < 0) currentBearing += 360f
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // GPS
    private fun requestLocationPermission() {
        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LOCATION)
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, locationListener)
            if (currentLocation == null) {
                currentLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: Exception) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) startLocationUpdates()
    }

    fun getCurrentLatitude()  = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.latitude) }  ?: ""
    fun getCurrentLongitude() = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.longitude) } ?: ""
    fun getCurrentBearing()   = if (!currentBearing.isNaN()) "%.1f".format(java.util.Locale.US, currentBearing) else ""

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
        if (!usbManager.hasPermission(device)) requestUsbPermission(usbManager, device)
        else startReaderService(deviceName)
    }

    private fun onStopClicked() {
        val fileName = readerService?.stopCapture()
        val total    = readerService?.tagCount() ?: 0

        if (fileName != null) {
            toast("CSV salvo em Downloads/$fileName\n($total tags)")
        } else {
            toast("Nenhuma tag para exportar")
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
        try {
            readerService?.startCapture(deviceName)
        } catch (e: Exception) {
            toast("Erro ao iniciar captura: ${e.message}")
        }
    }

    private fun refreshDeviceList() {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val drivers    = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        if (drivers.isEmpty()) {
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, listOf("Nenhum dispositivo USB"))
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerDevices.adapter = adapter
            binding.spinnerDevices.tag     = null
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
        binding.spinnerDevices.tag     = drivers[0].device.deviceName
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
    }

    private fun setCapturingState(capturing: Boolean) {
        binding.btnStart.isEnabled       = !capturing
        binding.btnStop.isEnabled        = capturing
        binding.spinnerDevices.isEnabled = !capturing
        binding.ledStatus.setImageResource(if (capturing) R.drawable.led_green else R.drawable.led_gray)
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

    private fun toast(msg: String) {
        currentToast?.cancel()
        currentToast = Toast.makeText(this, msg, Toast.LENGTH_LONG).also { it.show() }
    }

    companion object {
        private const val REQ_LOCATION = 101
    }
}