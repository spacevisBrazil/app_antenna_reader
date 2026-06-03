////package com.uhflogger
////
////import android.Manifest
////import android.app.PendingIntent
////import android.content.BroadcastReceiver
////import android.content.ComponentName
////import android.content.Context
////import android.content.Intent
////import android.content.IntentFilter
////import android.content.ServiceConnection
////import android.content.pm.PackageManager
////import android.hardware.Sensor
////import android.hardware.SensorEvent
////import android.hardware.SensorEventListener
////import android.hardware.SensorManager
////import android.hardware.usb.UsbDevice
////import android.hardware.usb.UsbManager
////import android.location.Location
////import android.location.LocationListener
////import android.location.LocationManager
////import android.os.Build
////import android.os.Bundle
////import android.os.Handler
////import android.os.IBinder
////import android.os.Looper
////import android.widget.ArrayAdapter
////import android.widget.Toast
////import androidx.appcompat.app.AppCompatActivity
////import androidx.core.app.ActivityCompat
////import androidx.core.content.ContextCompat
////import com.hoho.android.usbserial.driver.UsbSerialProber
////import com.uhflogger.databinding.ActivityMainBinding
////import com.uhflogger.service.UHFReaderService
////import com.uhflogger.SettingsManager
////import com.uhflogger.SettingsActivity
////
////class MainActivity : AppCompatActivity(), SensorEventListener {
////
////    private lateinit var binding: ActivityMainBinding
////    private var readerService: UHFReaderService? = null
////    private var serviceBound = false
////    private var currentToast: Toast? = null
////    private var stoppedByError = false
////
////    // GPS
////    private lateinit var locationManager: LocationManager
////    private var currentLocation: Location? = null
////    private val locationListener = object : LocationListener {
////        override fun onLocationChanged(location: Location) { currentLocation = location }
////        override fun onProviderEnabled(provider: String)   {}
////        override fun onProviderDisabled(provider: String)  {}
////        @Deprecated("Deprecated in Java")
////        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
////    }
////
////    // Bússola
////    private lateinit var sensorManager: SensorManager
////    private var accelerometerData = FloatArray(3)
////    private var magnetometerData  = FloatArray(3)
////    private var currentBearing    = Float.NaN
////
////    private val uiHandler = Handler(Looper.getMainLooper())
////    private val tagCountUpdater = object : Runnable {
////        override fun run() {
////            val count = readerService?.tagCount() ?: 0
////            binding.tvTagCount.text = "%,d".format(count)
////            uiHandler.postDelayed(this, 1000L)
////        }
////    }
////
////    private val serviceConnection = object : ServiceConnection {
////        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
////            val localBinder = binder as? UHFReaderService.LocalBinder ?: return
////            readerService = localBinder.getService()
////            serviceBound  = true
////            readerService?.mainActivity = this@MainActivity
////            readerService?.onStatusChanged = { capturing ->
////                runOnUiThread { setCapturingState(capturing) }
////            }
////            readerService?.onCaptureError = {
////                runOnUiThread {
////                    stoppedByError = true
////                    setCapturingState(false)
////                    binding.btnStart.isEnabled = false   // Start desabilitado até salvar
////                    binding.btnStop.isEnabled  = true    // Stop habilitado para salvar
////                    updateButtonColors()
////                    toast("Sinal da antena perdido — clique em STOP para salvar os dados")
////                }
////            }
////
////            readerService?.onAutoSaved = { total ->
////                runOnUiThread {
////                    toast("Auto-save: $total tags gravadas")
////                }
////            }
////            setCapturingState(readerService?.isCapturing() == true)
////        }
////        override fun onServiceDisconnected(name: ComponentName?) {
////            readerService = null
////            serviceBound  = false
////        }
////    }
////
////    private val ACTION_USB_PERMISSION = "com.uhflogger.USB_PERMISSION"
////
////    private val usbReceiver = object : BroadcastReceiver() {
////        override fun onReceive(context: Context, intent: Intent) {
////            when (intent.action) {
////                ACTION_USB_PERMISSION -> {
////                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
////                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
////                        device?.let { startReaderService(it.deviceName) }
////                    } else {
////                        toast("Permissão USB negada")
////                    }
////                }
////                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
////                    refreshDeviceList()
////                    // Se estava pausado (antena desconectou no meio da leitura),
////                    // retoma automaticamente quando a antena é reconectada
////                    val deviceName = binding.spinnerDevices.tag?.toString()
////                    if (readerService?.isPaused() == true && deviceName != null) {
////                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
////                        val device = usbManager.deviceList.values
////                            .firstOrNull { it.deviceName == deviceName }
////                        if (device != null) {
////                            if (usbManager.hasPermission(device)) {
////                                stoppedByError = false
////                                readerService?.startCapture(deviceName)
////                                toast("Antena reconectada — retomando leitura")
////                            } else {
////                                requestUsbPermission(usbManager, device)
////                            }
////                        }
////                    }
////                }
////                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
////                    refreshDeviceList()
////                    // onRunError já tratou a desconexão
////                }
////            }
////        }
////    }
////
////    override fun onCreate(savedInstanceState: Bundle?) {
////        super.onCreate(savedInstanceState)
////        binding = ActivityMainBinding.inflate(layoutInflater)
////        setContentView(binding.root)
////
////        sensorManager   = getSystemService(SENSOR_SERVICE)  as SensorManager
////        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
////
////        requestNotificationPermission()
////        requestLocationPermission()
////        registerUsbReceiver()
////        bindToService()
////        setupButtons()
////        refreshDeviceList()
////    }
////
////    override fun onResume() {
////        super.onResume()
////        uiHandler.post(tagCountUpdater)
////        refreshDeviceList()
////        startLocationUpdates()
////        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
////            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
////        }
////        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
////            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
////        }
////    }
////
////    override fun onPause() {
////        super.onPause()
////        uiHandler.removeCallbacks(tagCountUpdater)
////        sensorManager.unregisterListener(this)
////    }
////
////    override fun onDestroy() {
////        super.onDestroy()
////        locationManager.removeUpdates(locationListener)
////        unregisterReceiver(usbReceiver)
////        if (serviceBound) {
////            readerService?.mainActivity = null
////            unbindService(serviceConnection)
////        }
////    }
////
////    // Bússola
////    override fun onSensorChanged(event: SensorEvent) {
////        when (event.sensor.type) {
////            Sensor.TYPE_ACCELEROMETER  -> accelerometerData = event.values.clone()
////            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerData  = event.values.clone()
////        }
////        val rotationMatrix    = FloatArray(9)
////        val orientationAngles = FloatArray(3)
////        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerData, magnetometerData)) {
////            SensorManager.getOrientation(rotationMatrix, orientationAngles)
////            currentBearing = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
////            if (currentBearing < 0) currentBearing += 360f
////        }
////    }
////    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
////
////    // GPS
////    private fun requestLocationPermission() {
////        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
////        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
////        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LOCATION)
////    }
////
////    private fun startLocationUpdates() {
////        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
////            != PackageManager.PERMISSION_GRANTED) return
////        try {
////            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 1f, locationListener)
////            val gnssOnly = SettingsManager.getLocationMode(this) == SettingsManager.LOCATION_MODE_GNSS
////            if (currentLocation == null) {
////                currentLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
////                if (currentLocation == null && !gnssOnly) {
////                    currentLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
////                }
////            }
////        } catch (_: Exception) {}
////    }
////
////    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
////        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
////        if (requestCode == REQ_LOCATION) startLocationUpdates()
////    }
////
////    fun getCurrentLatitude()  = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.latitude) }  ?: ""
////    fun getCurrentLongitude() = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.longitude) } ?: ""
////    fun getCurrentBearing()   = if (!currentBearing.isNaN()) "%.1f".format(java.util.Locale.US, currentBearing) else ""
////
////    private fun setupButtons() {
////        binding.btnStart.setOnClickListener    { onStartClicked() }
////        binding.btnStop.setOnClickListener     { onStopClicked() }
////        binding.btnSettings.setOnClickListener { onSettingsClicked() }
////    }
////
////    private fun onSettingsClicked() {
////        startActivity(Intent(this, SettingsActivity::class.java))
////    }
////
////    private fun onStartClicked() {
////        val deviceName = binding.spinnerDevices.tag?.toString()
////            ?: run { toast("Nenhum dispositivo USB selecionado"); return }
////        val usbManager = getSystemService(USB_SERVICE) as UsbManager
////        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
////            ?: run { toast("Dispositivo não encontrado"); return }
////        if (!usbManager.hasPermission(device)) requestUsbPermission(usbManager, device)
////        else startReaderService(deviceName)
////    }
////
////    private fun onStopClicked() {
////        val fileName: String?
////        val total: Int
////
////        if (stoppedByError) {
////            stoppedByError = false
////            fileName = readerService?.saveAfterError()
////            total    = readerService?.tagCount() ?: 0
////            setCapturingState(false)  // saveAfterError não dispara callback, força aqui
////        } else {
////            fileName = readerService?.stopCapture()
////            total    = readerService?.tagCount() ?: 0
////            // stopCapture já chama onStatusChanged → setCapturingState via callback
////        }
////
////        if (fileName != null) toast("CSV salvo em Downloads/$fileName\n($total tags)")
////        else toast("Nenhuma tag para exportar")
////    }
////
////    private fun bindToService() {
////        try {
////            val intent = Intent(this, UHFReaderService::class.java)
////            startForegroundService(intent)
////            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
////        } catch (e: Exception) {
////            toast("Erro ao iniciar serviço: ${e.message}")
////        }
////    }
////
////    private fun startReaderService(deviceName: String) {
////        stoppedByError = false
////        try {
////            readerService?.startCapture(deviceName)
////        } catch (e: Exception) {
////            toast("Erro ao iniciar captura: ${e.message}")
////        }
////    }
////
////    private fun refreshDeviceList() {
////        val usbManager = getSystemService(USB_SERVICE) as UsbManager
////        val drivers    = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
////
////        val labels = if (drivers.isEmpty()) listOf("Nenhum dispositivo USB")
////        else drivers.map { driver ->
////            val dev = driver.device
////            val vid = dev.vendorId.toString(16).uppercase().padStart(4, '0')
////            val pid = dev.productId.toString(16).uppercase().padStart(4, '0')
////            val mfr = dev.manufacturerName ?: "Desconhecido"
////            "VID:$vid / PID:$pid — $mfr"
////        }
////
////        // Adapter com texto preto forçado — independente do tema do celular
////        val adapter = object : ArrayAdapter<String>(this,
////            android.R.layout.simple_spinner_item, labels) {
////            override fun getView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
////                val view = super.getView(pos, v, parent)
////                (view as? android.widget.TextView)?.setTextColor(android.graphics.Color.parseColor("#111111"))
////                return view
////            }
////            override fun getDropDownView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
////                val view = super.getDropDownView(pos, v, parent)
////                (view as? android.widget.TextView)?.apply {
////                    setTextColor(android.graphics.Color.parseColor("#111111"))
////                    setBackgroundColor(android.graphics.Color.WHITE)
////                }
////                return view
////            }
////        }
////        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
////        binding.spinnerDevices.adapter = adapter
////        binding.spinnerDevices.setBackgroundResource(R.drawable.bg_spinner_white)
////        binding.spinnerDevices.tag = if (drivers.isEmpty()) null else drivers[0].device.deviceName
////    }
////
////    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
////        val permissionIntent = PendingIntent.getBroadcast(
////            this, 0,
////            Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
////            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
////        )
////        usbManager.requestPermission(device, permissionIntent)
////    }
////
////    private fun registerUsbReceiver() {
////        val filter = IntentFilter().apply {
////            addAction(ACTION_USB_PERMISSION)
////            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
////            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
////        }
////        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
////    }
////
////    private fun setCapturingState(capturing: Boolean) {
////        binding.btnStart.isEnabled       = !capturing
////        binding.btnStop.isEnabled        = capturing
////        binding.btnSettings.isEnabled    = !capturing
////        binding.spinnerDevices.isEnabled = !capturing
////
////        binding.tvStatus.text = if (capturing) "Lendo" else "Parado"
////        binding.tvStatus.setBackgroundResource(
////            if (capturing) R.drawable.bg_badge_green else R.drawable.bg_badge_gray
////        )
////        updateButtonColors()
////    }
////
////    /** Cores sempre derivadas do isEnabled — fonte única de verdade */
////    private fun updateButtonColors() {
////        binding.btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
////            if (binding.btnStart.isEnabled) 0xFF2E7D32.toInt() else 0xFFA5D6A7.toInt()
////        )
////        binding.btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
////            if (binding.btnStop.isEnabled) 0xFFC62828.toInt() else 0xFFEF9A9A.toInt()
////        )
////    }
////
////    private fun requestNotificationPermission() {
////        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
////            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
////                != PackageManager.PERMISSION_GRANTED) {
////                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
////            }
////        }
////    }
////
////    private fun toast(msg: String) {
////        currentToast?.cancel()
////        currentToast = Toast.makeText(this, msg, Toast.LENGTH_LONG).also {
////            it.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 120)
////            it.show()
////        }
////    }
////
////    companion object {
////        private const val REQ_LOCATION = 101
////    }
////}
//
//
//package com.uhflogger
//
//import android.Manifest
//import android.app.PendingIntent
//import android.content.BroadcastReceiver
//import android.content.ComponentName
//import android.content.Context
//import android.content.Intent
//import android.content.IntentFilter
//import android.content.ServiceConnection
//import android.content.pm.PackageManager
//import android.hardware.Sensor
//import android.hardware.SensorEvent
//import android.hardware.SensorEventListener
//import android.hardware.SensorManager
//import android.hardware.usb.UsbDevice
//import android.hardware.usb.UsbManager
//import android.location.Location
//import android.location.LocationListener
//import android.location.LocationManager
//import android.os.Build
//import android.os.Bundle
//import android.os.Handler
//import android.os.IBinder
//import android.os.Looper
//import android.widget.ArrayAdapter
//import android.widget.Toast
//import androidx.appcompat.app.AppCompatActivity
//import androidx.core.app.ActivityCompat
//import androidx.core.content.ContextCompat
//import com.hoho.android.usbserial.driver.UsbSerialProber
//import com.uhflogger.databinding.ActivityMainBinding
//import com.uhflogger.service.UHFReaderService
//import com.uhflogger.SettingsManager
//import com.uhflogger.SettingsActivity
//
//class MainActivity : AppCompatActivity(), SensorEventListener {
//
//    companion object {
//        const val GPS_UPDATE_INTERVAL_MS = 1000L   // intervalo mínimo em milissegundos
//        const val GPS_UPDATE_MIN_METERS  = 0.0f    // deslocamento mínimo em metros
//        private const val REQ_LOCATION   = 101
//    }
//
//    private lateinit var binding: ActivityMainBinding
//    private var readerService: UHFReaderService? = null
//    private var serviceBound = false
//    private var currentToast: Toast? = null
//    private var stoppedByError = false
//
//    // GPS
//    private lateinit var locationManager: LocationManager
//    private var currentLocation: Location? = null
//    private val locationListener = object : LocationListener {
//        override fun onLocationChanged(location: Location) { currentLocation = location }
//        override fun onProviderEnabled(provider: String)   {}
//        override fun onProviderDisabled(provider: String)  {}
//        @Deprecated("Deprecated in Java")
//        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
//    }
//
//    // Bússola
//    private lateinit var sensorManager: SensorManager
//    private var accelerometerData = FloatArray(3)
//    private var magnetometerData  = FloatArray(3)
//    private var currentBearing    = Float.NaN
//
//    private val uiHandler = Handler(Looper.getMainLooper())
//    private val tagCountUpdater = object : Runnable {
//        override fun run() {
//            val count = readerService?.tagCount() ?: 0
//            binding.tvTagCount.text = "%,d".format(count)
//            uiHandler.postDelayed(this, 1000L)
//        }
//    }
//
//    private val serviceConnection = object : ServiceConnection {
//        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
//            val localBinder = binder as? UHFReaderService.LocalBinder ?: return
//            readerService = localBinder.getService()
//            serviceBound  = true
//            readerService?.mainActivity = this@MainActivity
//            readerService?.onStatusChanged = { capturing ->
//                runOnUiThread { setCapturingState(capturing) }
//            }
//            readerService?.onCaptureError = {
//                runOnUiThread {
//                    stoppedByError = true
//                    setCapturingState(false)
//                    binding.btnStart.isEnabled = false   // Start desabilitado até salvar
//                    binding.btnStop.isEnabled  = true    // Stop habilitado para salvar
//                    updateButtonColors()
//                    toast("Sinal da antena perdido — clique em STOP para salvar os dados")
//                }
//            }
//
//            readerService?.onAutoSaved = { total ->
//                runOnUiThread {
//                    toast("Auto-save: $total tags gravadas")
//                }
//            }
//            setCapturingState(readerService?.isCapturing() == true)
//        }
//        override fun onServiceDisconnected(name: ComponentName?) {
//            readerService = null
//            serviceBound  = false
//        }
//    }
//
//    private val ACTION_USB_PERMISSION = "com.uhflogger.USB_PERMISSION"
//
//    private val usbReceiver = object : BroadcastReceiver() {
//        override fun onReceive(context: Context, intent: Intent) {
//            when (intent.action) {
//                ACTION_USB_PERMISSION -> {
//                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
//                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
//                        device?.let { startReaderService(it.deviceName) }
//                    } else {
//                        toast("Permissão USB negada")
//                    }
//                }
//                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
//                    refreshDeviceList()
//                    // Se estava pausado (antena desconectou no meio da leitura),
//                    // retoma automaticamente quando a antena é reconectada
//                    val deviceName = binding.spinnerDevices.tag?.toString()
//                    if (readerService?.isPaused() == true && deviceName != null) {
//                        val usbManager = getSystemService(USB_SERVICE) as UsbManager
//                        val device = usbManager.deviceList.values
//                            .firstOrNull { it.deviceName == deviceName }
//                        if (device != null) {
//                            if (usbManager.hasPermission(device)) {
//                                stoppedByError = false
//                                readerService?.startCapture(deviceName)
//                                toast("Antena reconectada — retomando leitura")
//                            } else {
//                                requestUsbPermission(usbManager, device)
//                            }
//                        }
//                    }
//                }
//                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
//                    refreshDeviceList()
//                    // onRunError já tratou a desconexão
//                }
//            }
//        }
//    }
//
//    override fun onCreate(savedInstanceState: Bundle?) {
//        super.onCreate(savedInstanceState)
//        binding = ActivityMainBinding.inflate(layoutInflater)
//        setContentView(binding.root)
//
//        sensorManager   = getSystemService(SENSOR_SERVICE)  as SensorManager
//        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
//
//        requestNotificationPermission()
//        requestLocationPermission()
//        registerUsbReceiver()
//        bindToService()
//        setupButtons()
//        refreshDeviceList()
//    }
//
//    override fun onResume() {
//        super.onResume()
//        uiHandler.post(tagCountUpdater)
//        refreshDeviceList()
//        startLocationUpdates()
//        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
//            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
//        }
//        sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
//            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
//        }
//    }
//
//    override fun onPause() {
//        super.onPause()
//        uiHandler.removeCallbacks(tagCountUpdater)
//        sensorManager.unregisterListener(this)
//        // Remove GPS quando não está capturando — economiza bateria
//        // Durante captura mantém ativo para registrar posição nas tags
//        if (readerService?.isCapturing() != true && readerService?.isPaused() != true) {
//            locationManager.removeUpdates(locationListener)
//        }
//    }
//
//    override fun onDestroy() {
//        super.onDestroy()
//        locationManager.removeUpdates(locationListener)
//        unregisterReceiver(usbReceiver)
//        if (serviceBound) {
//            readerService?.mainActivity = null
//            unbindService(serviceConnection)
//        }
//    }
//
//    // Bússola
//    override fun onSensorChanged(event: SensorEvent) {
//        when (event.sensor.type) {
//            Sensor.TYPE_ACCELEROMETER  -> accelerometerData = event.values.clone()
//            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerData  = event.values.clone()
//        }
//        val rotationMatrix    = FloatArray(9)
//        val orientationAngles = FloatArray(3)
//        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerData, magnetometerData)) {
//            SensorManager.getOrientation(rotationMatrix, orientationAngles)
//            currentBearing = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
//            if (currentBearing < 0) currentBearing += 360f
//        }
//    }
//    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
//
//    // GPS
//    private fun requestLocationPermission() {
//        val perms = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
//        val missing = perms.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
//        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQ_LOCATION)
//    }
//
//    private fun startLocationUpdates() {
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
//            != PackageManager.PERMISSION_GRANTED) return
//        val gnssOnly = SettingsManager.getLocationMode(this) == SettingsManager.LOCATION_MODE_GNSS
//        try {
//            // GNSS — usa as constantes configuráveis
//            locationManager.requestLocationUpdates(
//                LocationManager.GPS_PROVIDER, GPS_UPDATE_INTERVAL_MS, GPS_UPDATE_MIN_METERS, locationListener)
//
//            // Modo híbrido — registra também o NETWORK_PROVIDER
//            if (!gnssOnly) {
//                locationManager.requestLocationUpdates(
//                    LocationManager.NETWORK_PROVIDER, GPS_UPDATE_INTERVAL_MS, GPS_UPDATE_MIN_METERS, locationListener)
//            }
//
//            // Fallback inicial enquanto GPS ainda não fixou
//            if (currentLocation == null) {
//                currentLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
//                if (currentLocation == null && !gnssOnly) {
//                    currentLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
//                }
//            }
//        } catch (_: Exception) {}
//    }
//
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == REQ_LOCATION) startLocationUpdates()
//    }
//
//    fun getCurrentLatitude()  = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.latitude) }  ?: ""
//    fun getCurrentLongitude() = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.longitude) } ?: ""
//    fun getCurrentBearing()   = if (!currentBearing.isNaN()) "%.1f".format(java.util.Locale.US, currentBearing) else ""
//
//    private fun setupButtons() {
//        binding.btnStart.setOnClickListener    { onStartClicked() }
//        binding.btnStop.setOnClickListener     { onStopClicked() }
//        binding.btnSettings.setOnClickListener { onSettingsClicked() }
//    }
//
//    private fun onSettingsClicked() {
//        startActivity(Intent(this, SettingsActivity::class.java))
//    }
//
//    private fun onStartClicked() {
//        val deviceName = binding.spinnerDevices.tag?.toString()
//            ?: run { toast("Nenhum dispositivo USB selecionado"); return }
//        val usbManager = getSystemService(USB_SERVICE) as UsbManager
//        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
//            ?: run { toast("Dispositivo não encontrado"); return }
//        if (!usbManager.hasPermission(device)) requestUsbPermission(usbManager, device)
//        else startReaderService(deviceName)
//    }
//
//    private fun onStopClicked() {
//        val fileName: String?
//        val total: Int
//
//        if (stoppedByError) {
//            stoppedByError = false
//            total    = readerService?.tagCount() ?: 0   // lê ANTES de zerar
//            fileName = readerService?.saveAfterError()
//            setCapturingState(false)
//        } else {
//            total    = readerService?.tagCount() ?: 0   // lê ANTES de stopCapture zerar
//            fileName = readerService?.stopCapture()
//        }
//
//        if (fileName != null) toast("CSV salvo em Downloads/$fileName\n($total tags)")
//        else toast("Nenhuma tag para exportar")
//    }
//
//    private fun bindToService() {
//        try {
//            val intent = Intent(this, UHFReaderService::class.java)
//            startForegroundService(intent)
//            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
//        } catch (e: Exception) {
//            toast("Erro ao iniciar serviço: ${e.message}")
//        }
//    }
//
//    private fun startReaderService(deviceName: String) {
//        stoppedByError = false
//        try {
//            readerService?.startCapture(deviceName)
//        } catch (e: Exception) {
//            toast("Erro ao iniciar captura: ${e.message}")
//        }
//    }
//
//    private fun refreshDeviceList() {
//        val usbManager = getSystemService(USB_SERVICE) as UsbManager
//        val drivers    = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
//
//        val labels = if (drivers.isEmpty()) listOf("Nenhum dispositivo USB")
//        else drivers.map { driver ->
//            val dev = driver.device
//            val vid = dev.vendorId.toString(16).uppercase().padStart(4, '0')
//            val pid = dev.productId.toString(16).uppercase().padStart(4, '0')
//            val mfr = dev.manufacturerName ?: "Desconhecido"
//            "VID:$vid / PID:$pid — $mfr"
//        }
//
//        // Adapter com texto preto forçado — independente do tema do celular
//        val adapter = object : ArrayAdapter<String>(this,
//            android.R.layout.simple_spinner_item, labels) {
//            override fun getView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
//                val view = super.getView(pos, v, parent)
//                (view as? android.widget.TextView)?.setTextColor(android.graphics.Color.parseColor("#111111"))
//                return view
//            }
//            override fun getDropDownView(pos: Int, v: android.view.View?, parent: android.view.ViewGroup): android.view.View {
//                val view = super.getDropDownView(pos, v, parent)
//                (view as? android.widget.TextView)?.apply {
//                    setTextColor(android.graphics.Color.parseColor("#111111"))
//                    setBackgroundColor(android.graphics.Color.WHITE)
//                }
//                return view
//            }
//        }
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
//        binding.spinnerDevices.adapter = adapter
//        binding.spinnerDevices.setBackgroundResource(R.drawable.bg_spinner_white)
//        binding.spinnerDevices.tag = if (drivers.isEmpty()) null else drivers[0].device.deviceName
//    }
//
//    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
//        val permissionIntent = PendingIntent.getBroadcast(
//            this, 0,
//            Intent(ACTION_USB_PERMISSION).apply { setPackage(packageName) },
//            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
//        )
//        usbManager.requestPermission(device, permissionIntent)
//    }
//
//    private fun registerUsbReceiver() {
//        val filter = IntentFilter().apply {
//            addAction(ACTION_USB_PERMISSION)
//            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
//            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
//        }
//        registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
//    }
//
//    private fun setCapturingState(capturing: Boolean) {
//        binding.btnStart.isEnabled       = !capturing
//        binding.btnStop.isEnabled        = capturing
//        binding.btnSettings.isEnabled    = !capturing
//        binding.spinnerDevices.isEnabled = !capturing
//
//        binding.tvStatus.text = if (capturing) "Lendo" else "Parado"
//        binding.tvStatus.setBackgroundResource(
//            if (capturing) R.drawable.bg_badge_green else R.drawable.bg_badge_gray
//        )
//        updateButtonColors()
//    }
//
//    /** Cores sempre derivadas do isEnabled — fonte única de verdade */
//    private fun updateButtonColors() {
//        binding.btnStart.backgroundTintList = android.content.res.ColorStateList.valueOf(
//            if (binding.btnStart.isEnabled) 0xFF2E7D32.toInt() else 0xFFA5D6A7.toInt()
//        )
//        binding.btnStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
//            if (binding.btnStop.isEnabled) 0xFFC62828.toInt() else 0xFFEF9A9A.toInt()
//        )
//    }
//
//    private fun requestNotificationPermission() {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
//                != PackageManager.PERMISSION_GRANTED) {
//                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
//            }
//        }
//    }
//
//    private fun toast(msg: String) {
//        currentToast?.cancel()
//        currentToast = Toast.makeText(this, msg, Toast.LENGTH_LONG).also {
//            it.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL, 0, 120)
//            it.show()
//        }
//    }
//
//}


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
import com.uhflogger.SettingsManager
import com.uhflogger.SettingsActivity

class MainActivity : AppCompatActivity(), SensorEventListener {

    // -------------------------------------------------------------------------
    // Configurações de GPS — ajuste aqui
    // -------------------------------------------------------------------------
    companion object {
        const val GPS_UPDATE_INTERVAL_MS = 1000L   // intervalo mínimo em milissegundos
        const val GPS_UPDATE_MIN_METERS  = 1f      // deslocamento mínimo em metros
        private const val REQ_LOCATION   = 101
    }

    private lateinit var binding: ActivityMainBinding
    private var readerService: UHFReaderService? = null
    private var serviceBound = false
    private var currentToast: Toast? = null
    private var stoppedByError = false

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
                runOnUiThread { setCapturingState(capturing) }
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
                    // Se estava pausado (antena desconectou no meio da leitura),
                    // retoma automaticamente quando a antena é reconectada
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
        // Remove GPS quando não está capturando — economiza bateria
        // Durante captura mantém ativo para registrar posição nas tags
        if (readerService?.isCapturing() != true && readerService?.isPaused() != true) {
            locationManager.removeUpdates(locationListener)
        }
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
        val gnssOnly = SettingsManager.getLocationMode(this) == SettingsManager.LOCATION_MODE_GNSS
        try {
            // GNSS — usa as constantes configuráveis
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, GPS_UPDATE_INTERVAL_MS, GPS_UPDATE_MIN_METERS, locationListener)

            // Modo híbrido — registra também o NETWORK_PROVIDER
            if (!gnssOnly) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, GPS_UPDATE_INTERVAL_MS, GPS_UPDATE_MIN_METERS, locationListener)
            }

            // Fallback inicial enquanto GPS ainda não fixou
            if (currentLocation == null) {
                currentLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                if (currentLocation == null && !gnssOnly) {
                    currentLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                }
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
        binding.btnStart.setOnClickListener    { onStartClicked() }
        binding.btnStop.setOnClickListener     { onStopClicked() }
        binding.btnSettings.setOnClickListener { onSettingsClicked() }
    }

    private fun onSettingsClicked() {
        startActivity(Intent(this, SettingsActivity::class.java))
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
        val fileName: String?
        val total: Int

        if (stoppedByError) {
            stoppedByError = false
            total    = readerService?.tagCount() ?: 0   // lê ANTES de zerar
            fileName = readerService?.saveAfterError()
            setCapturingState(false)
        } else {
            total    = readerService?.tagCount() ?: 0   // lê ANTES de stopCapture zerar
            fileName = readerService?.stopCapture()
        }

        if (fileName != null) toast("CSV salvo em Downloads/$fileName\n($total tags)")
        else toast("Nenhuma tag para exportar")
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

        val labels = if (drivers.isEmpty()) listOf("Nenhum dispositivo USB")
        else drivers.map { driver ->
            val dev = driver.device
            val vid = dev.vendorId.toString(16).uppercase().padStart(4, '0')
            val pid = dev.productId.toString(16).uppercase().padStart(4, '0')
            val mfr = dev.manufacturerName ?: "Desconhecido"
            "VID:$vid / PID:$pid — $mfr"
        }

        // Adapter com texto preto forçado — independente do tema do celular
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
        binding.spinnerDevices.tag = if (drivers.isEmpty()) null else drivers[0].device.deviceName
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