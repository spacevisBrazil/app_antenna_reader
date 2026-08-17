package com.uhflogger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import com.uhflogger.CsvExporter
import com.uhflogger.MainActivity
import com.uhflogger.SettingsManager
import com.uhflogger.decoder.ProtocolDecoder
import com.uhflogger.decoder.WinnixProtocolDecoder
import com.uhflogger.filter.TagFilterEngine
import com.uhflogger.model.TagRecord
import com.uhflogger.serial.BluetoothInputOutputManager
import com.uhflogger.serial.BluetoothSerialPort
import com.uhflogger.serial.ISerialPort
import com.uhflogger.serial.UsbSerialPortWrapper
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import com.uhflogger.drive.DriveHelper
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class UHFReaderService : Service(), SensorEventListener {

    // =========================================================================
    // Binder
    // =========================================================================
    inner class LocalBinder : Binder() {
        fun getService(): UHFReaderService = this@UHFReaderService
    }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // =========================================================================
    // GNSS + bússola — vivem inteiramente no Service, independentes da Activity/tela.
    // Antes dependiam da MainActivity (via mainActivity?.getCurrentLatitude() etc.),
    // então travavam sempre que a tela apagava/travava (onPause desregistrava os
    // sensores e o Android throttla o GPS de apps sem Activity visível). Agora tudo
    // roda direto no foreground service, então continua com a tela desligada.
    // =========================================================================
    private var locationManager: LocationManager? = null
    private var sensorManager  : SensorManager?   = null
    private var wakeLock       : PowerManager.WakeLock? = null

    // Thread dedicada exclusiva para GNSS + bússola — os callbacks onLocationChanged
    // e onSensorChanged rodam SOMENTE aqui, nunca na thread de leitura serial (USB
    // ou BT, que têm suas próprias threads em SerialInputOutputManager /
    // BluetoothInputOutputManager) nem na UI thread. Isso garante que:
    //   1. Um GPS/sensor lento nunca atrasa/bloqueia a leitura de tags.
    //   2. Uma leitura de tags pesada nunca atrasa a atualização de posição.
    //   3. Registro funciona non importa qual thread chama startCapture()
    //      (main thread, ou a thread de reconexão BT) — sem risco do
    //      "Looper.prepare()" crash.
    private var locationSensorThread : android.os.HandlerThread? = null
    private var locationSensorHandler: android.os.Handler? = null

    @Volatile private var currentLocation: Location? = null
    // Bearing "de fusão de sensores" (acelerômetro+magnetômetro) — fallback
    // sempre disponível, usado quando não há GNSS confiável pra essa amostra.
    @Volatile private var currentSensorBearing: Float = Float.NaN
    // Bearing vindo do GNSS (direção de deslocamento real, imune a interferência
    // magnética do trator) — só é confiável em movimento.
    @Volatile private var currentGnssBearing: Float = Float.NaN
    // Qual fonte está "ativa" agora (histerese) — escrito SÓ dentro de
    // onLocationChanged (roda na locationSensorThread), lido pela thread de
    // RFID em getCurrentBearing(). @Volatile garante visibilidade sem lock.
    @Volatile private var bearingSourceIsGnss: Boolean = false
    @Volatile private var currentGnssSpeed: Float? = null           // null = hasSpeed()==false
    @Volatile private var currentLocationTimeMs: Long = 0L          // 0 = nenhum fix ainda
    @Volatile private var currentLocationProviderLabel: String = "" // "GNSS" ou "NETWORK"
    private var accelerometerData = FloatArray(3)
    private var magnetometerData  = FloatArray(3)

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // Speed e bearing via Doppler são extraídos de TODO fix entrante,
            // independente de a posição ser aceita ou não pelo isBetterLocation.
            // Doppler (variação de frequência) é calculado pelo chip separadamente
            // do pseudorange (posição) — permanece confiável mesmo quando o fix
            // tem acurácia degradada (ex: multipath de telhado metálico entrega
            // 25m de erro de posição mas Doppler de velocidade ainda é preciso).
            // Sem essa extração antecipada, speed e bearing ficavam congelados
            // durante qualquer janela de rejeição de fix por acurácia.
            // Nota: bearingSourceIsGnss (histerese) só é atualizado em
            // applyLocation() — a flag não muda para fixes rejeitados, apenas os
            // valores brutos são pré-populados aqui.
            if (location.hasSpeed()) currentGnssSpeed = location.speed
            if (location.hasBearing() && location.hasSpeed()
                    && location.speed > BEARING_SPEED_HIGH_MS) {
                currentGnssBearing = location.bearing
            }
            val accepted = isBetterLocation(location, currentLocation)
            // Log de diagnóstico: mostra TODA chegada de fix, aceito ou não, com
            // motivo. Sem isso, não dá pra distinguir "Android nunca entregou
            // nada" de "entregou mas foi rejeitado por precisão" — os dois
            // parecem iguais de fora (CSV vazio), mas são causas bem diferentes.
            Log.i(TAG, "LOCATION_RX: provider=${location.provider} " +
                    "accuracy=${if (location.hasAccuracy()) "%.1f".format(java.util.Locale.US, location.accuracy) else "n/a"}m " +
                    "speed=${if (location.hasSpeed()) "%.1f".format(java.util.Locale.US, location.speed) else "n/a"}m/s " +
                    "age=${System.currentTimeMillis() - location.time}ms " +
                    "aceito=$accepted" +
                    (if (!accepted) " (motivo: degradou demais fix bom, provider pior, fix velho, ou acima do teto absoluto em movimento)" else ""))
            if (accepted) applyLocation(location)
        }
        override fun onProviderEnabled(provider: String)  {
            Log.i(TAG, "LOCATION_PROVIDER_ENABLED: $provider")
        }
        override fun onProviderDisabled(provider: String) {
            Log.w(TAG, "LOCATION_PROVIDER_DISABLED: $provider")
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /**
     * Adota `location` como a posição atual — atualiza lat/lon, speed,
     * timestamp, provider e decide a histerese do bearing híbrido. Chamado
     * tanto pelo listener de updates novos quanto pelo fallback de
     * getLastKnownLocation() no início da captura, pra nunca duplicar essa
     * lógica em dois lugares (e arriscar um ficar desatualizado).
     */
    private fun applyLocation(location: Location) {
        currentLocation = location
        // System.currentTimeMillis() capturado AQUI — no exato instante em que
        // o app recebe/aceita este fix — em vez de location.time (relógio
        // interno do chip GNSS, que pode divergir do relógio do Android em
        // algumas centenas de ms, inclusive "no futuro"). Mesmo domínio de
        // relógio que o Timestamp do EPC, então Location Timestamp nunca pode
        // vir maior que o Timestamp — é sequencial por construção.
        currentLocationTimeMs        = System.currentTimeMillis()
        currentLocationProviderLabel = if (location.provider == LocationManager.GPS_PROVIDER) "GNSS" else "NETWORK"
        currentGnssSpeed = if (location.hasSpeed()) location.speed else null

        // Histerese do bearing híbrido: só troca de fonte quando cruza a borda
        // apropriada, pra não "piscar" entre GNSS e sensores quando a
        // velocidade oscila perto do limiar (ex: fazendo curva devagar).
        val speed = currentGnssSpeed
        if (speed != null && location.hasBearing()) {
            currentGnssBearing = location.bearing
            if (!bearingSourceIsGnss && speed > BEARING_SPEED_HIGH_MS) {
                bearingSourceIsGnss = true
            } else if (bearingSourceIsGnss && speed < BEARING_SPEED_LOW_MS) {
                bearingSourceIsGnss = false
            }
        } else {
            // Sem speed ou sem bearing confiável neste fix — não dá pra avaliar
            // o GNSS, cai pra fusão de sensores.
            bearingSourceIsGnss = false
        }
    }

    /**
     * Mesma lógica de antes, com uma mudança: em vez de exigir precisão
     * igual-ou-melhor que a atual, aceita qualquer fix dentro do teto de 50m
     * que não seja MUITO pior (>3x) que o atual. Isso prioriza recência (dado
     * mais "casado" com cada tag) sem aceitar degradação absurda — importante
     * porque o trator passa por áreas de sinal fraco e a posição real precisa
     * continuar atualizando, não "congelar" só porque um fix é um pouco pior.
     */
    private fun isBetterLocation(newLoc: Location, current: Location?): Boolean {
        val now = System.currentTimeMillis()
        if (now - newLoc.time > GPS_MAX_LOCATION_AGE_MS) return false
        // Sem posição nenhuma ainda — aceita mesmo que a precisão seja ruim.
        // "Algo é melhor que nada": nunca deixar o dado em branco quando existe
        // QUALQUER fix disponível (ex: só NETWORK_PROVIDER funcionando, com erro
        // de centenas de metros, dentro de um galpão sem GNSS).
        if (current == null) return true
        // Regra 3: fix em cache expirou — force-aceita qualquer coisa para não
        // manter uma posição velha indefinidamente. Checa nos dois domínios de
        // relógio possíveis: current.time (relógio interno do chip GNSS) e
        // currentLocationTimeMs (System.currentTimeMillis() gravado no momento
        // do aceite, mesmo domínio dos timestamps do CSV e dos EPCs). O chip
        // GNSS pode divergir centenas de ms do relógio do Android — o OR garante
        // que o timeout de 30s dispara corretamente independente de qualquer deriva.
        if (now - current.time > GPS_MAX_LOCATION_AGE_MS ||
                now - currentLocationTimeMs > GPS_MAX_LOCATION_AGE_MS) return true
        val newIsGps     = newLoc.provider == LocationManager.GPS_PROVIDER
        val currentIsGps = current.provider == LocationManager.GPS_PROVIDER
        if (newIsGps && !currentIsGps) return true
        if (!newIsGps && currentIsGps) return false
        if (!newLoc.hasAccuracy()) return false
        if (!current.hasAccuracy()) return true
        // Mesma posição GPS → aceita independente de acurácia, só para atualizar
        // o timestamp. Resolve o congelamento causado pelo stationary filter do
        // chip: chip trava coordenadas quando parado mas continua variando a
        // acurácia reportada — sem isso o cache de 30s expirava e um fix NETWORK
        // errado entrava no lugar.
        if (newIsGps && newLoc.latitude == current.latitude
                     && newLoc.longitude == current.longitude) return true
        // O teto de 50m só faz sentido pra PROTEGER um fix que já é bom — se o
        // atual já é ruim (aceito por falta de opção melhor), qualquer melhora
        // serve, mesmo que continue acima do teto. Sem essa distinção, o
        // primeiro fix ruim aceito ficava "preso" mesmo quando fixes bem
        // melhores (mas ainda acima de 50m) chegavam depois.
        return if (current.accuracy <= GPS_MAX_ACCURACY_M) {
            // Caminho 1 — relativo (comportamento original, inalterado):
            // aceita se o novo fix não for muito pior que o atual (fator 3×).
            val passesRelative = newLoc.accuracy <= current.accuracy * GPS_ACCURACY_DEGRADE_FACTOR
            // Caminho 2 — absoluto confirmado por Doppler:
            // aceita se o fix entrante indica via Doppler que estamos em
            // movimento E a acurácia está dentro do teto absoluto. Resolve o
            // "fix-âncora": parado 5 min → fix de 3m → threshold relativo = 9m
            // → multipath de telhado metálico (20-30m) rejeitado em cadeia.
            // Usa newLoc.speed (Doppler do fix ENTRANTE) em vez de
            // currentGnssSpeed (cache do último aceito) para evitar dependência
            // circular — se a posição estiver congelando, o cache de speed
            // também estaria, mas o Doppler do fix entrante reflete estado atual.
            // Nenhum dos dois caminhos aceita tudo sozinho: o caminho 2 exige
            // confirmação de movimento via Doppler E acurácia dentro do teto.
            val incomingSpeed = if (newLoc.hasSpeed()) newLoc.speed else 0f
            val passesAbsolute = incomingSpeed > BEARING_SPEED_HIGH_MS
                    && newLoc.accuracy <= GPS_MOVING_ABSOLUTE_MAX_M
            passesRelative || passesAbsolute
        } else {
            newLoc.accuracy <= current.accuracy
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER  -> accelerometerData = event.values.clone()
            Sensor.TYPE_MAGNETIC_FIELD -> magnetometerData  = event.values.clone()
        }
        val rotationMatrix    = FloatArray(9)
        val orientationAngles = FloatArray(3)
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerData, magnetometerData)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            var bearing = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (bearing < 0) bearing += 360f
            currentSensorBearing = bearing
        }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Trava dos SENSORES (bússola) — não precisa de permissão, então uma vez
    // registrado, fica registrado pro resto da vida do serviço.
    private val sensorsRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
    // Trava da LOCALIZAÇÃO — separada de propósito. Antes, uma única trava
    // cobria as duas coisas: se a permissão de localização ainda não tivesse
    // sido concedida na primeira chamada (ex: app acabou de abrir, diálogo de
    // permissão ainda não respondido), a trava já ficava "true" e o GPS NUNCA
    // MAIS era tentado de novo — nem quando startCapture() chamava essa
    // função de novo já com a permissão concedida. Resultado: sessão inteira
    // sem nenhum dado de localização, mesmo com permissão OK depois. Agora,
    // enquanto isso não tiver sucesso pelo menos uma vez, toda chamada tenta
    // de novo.
    @Volatile private var locationRegistered = false

    /** Inicia GPS + bússola numa thread própria. Chamado a partir de startCapture —
     *  roda mesmo com tela apagada, e não compartilha thread com a leitura RFID. */
    private fun startLocationAndSensors() {
        val ctx = appContext ?: return

        // Se a permissão foi concedida DEPOIS do onCreate (ex: usuário acabou de
        // tocar em "Permitir"), promove o foreground service pra incluir o tipo
        // "location" agora — chamar startForeground() de novo com o tipo
        // atualizado é seguro e é a forma documentada de "upgrade" o tipo.
        startForegroundWithSafeType()

        // Thread + Looper dedicados — só cria uma vez, reaproveita nas
        // chamadas seguintes (não recria a cada retry de localização).
        val handler = locationSensorHandler ?: run {
            val thread = android.os.HandlerThread("UHFLogger-LocationSensor").also { it.start() }
            locationSensorThread = thread
            android.os.Handler(thread.looper).also { locationSensorHandler = it }
        }

        if (!locationRegistered) {
            if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "ACCESS_FINE_LOCATION não concedida — GNSS não será registrado (vai tentar de novo na próxima chamada)")
            } else {
                try {
                    val lm = (locationManager ?: (ctx.getSystemService(LOCATION_SERVICE) as LocationManager)
                        .also { locationManager = it })
                    val gnssOnly = SettingsManager.getLocationMode(ctx) == SettingsManager.LOCATION_MODE_GNSS
                    // Passando `handler` explicitamente: os callbacks chegam na
                    // locationSensorThread, não na thread que chamou startCapture().
                    lm.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, GPS_UPDATE_INTERVAL_MS, GPS_UPDATE_MIN_METERS, locationListener, handler.looper)
                    if (!gnssOnly) {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, GPS_UPDATE_INTERVAL_MS, GPS_UPDATE_MIN_METERS, locationListener, handler.looper)
                    }
                    locationRegistered = true
                    Log.i(TAG, "LOCATION_REGISTERED: modo=${if (gnssOnly) "GNSS puro" else "híbrido (GNSS+NETWORK)"} — GPS_PROVIDER e${if (gnssOnly) "" else " NETWORK_PROVIDER"} registrados sem exceção")
                    if (currentLocation == null) {
                        // Usa a última posição conhecida MAIS RECENTE entre GPS e
                        // NETWORK — sem limite de idade ("algo é melhor que nada"),
                        // mas também sem preferir GPS cegamente: se o cache do GPS
                        // tem 2h e o do NETWORK tem 10min, o NETWORK é o certo aqui.
                        // Preferir provider sem olhar a idade só faz sentido quando
                        // os dois são comparavelmente frescos — não é o caso ao
                        // comparar dois caches parados, potencialmente muito
                        // diferentes em idade um do outro.
                        val lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        val lastNet = if (!gnssOnly) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
                        val best = when {
                            lastGps != null && lastNet != null -> if (lastGps.time >= lastNet.time) lastGps else lastNet
                            lastGps != null -> lastGps
                            lastNet != null -> lastNet
                            else -> null
                        }
                        if (best != null) {
                            applyLocation(best)
                            val label = if (best.provider == LocationManager.GPS_PROVIDER) "GPS" else "NETWORK"
                            Log.i(TAG, "LOCATION_CACHE: usando última posição $label conhecida (mais recente entre as disponíveis), idade=${System.currentTimeMillis()-best.time}ms")
                        } else {
                            Log.i(TAG, "LOCATION_CACHE: nenhuma posição em cache disponível (GPS nem NETWORK) — aguardando fix novo")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Falha ao registrar location updates", e)
                }
            }
        }

        if (sensorsRegistered.compareAndSet(false, true)) {
            val sm = (sensorManager ?: (ctx.getSystemService(SENSOR_SERVICE) as SensorManager)
                .also { sensorManager = it })
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
            }
        }
    }

    private fun stopLocationAndSensors() {
        try { locationManager?.removeUpdates(locationListener) } catch (_: Exception) {}
        try { sensorManager?.unregisterListener(this) } catch (_: Exception) {}
        // quitSafely: deixa mensagens já enfileiradas (ex: um onLocationChanged
        // em trânsito) terminarem de processar antes de matar a thread.
        try { locationSensorThread?.quitSafely() } catch (_: Exception) {}
        locationSensorThread  = null
        locationSensorHandler = null
        locationRegistered = false
        sensorsRegistered.set(false)
    }

    fun getCurrentLatitude()  = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.latitude) }  ?: ""
    fun getCurrentLongitude() = currentLocation?.let { "%.6f".format(java.util.Locale.US, it.longitude) } ?: ""

    /** Bearing híbrido: GNSS quando em movimento (histerese em onLocationChanged
     *  decide isso), fusão de sensores caso contrário — ou se o GNSS não tiver
     *  valor válido ainda mesmo com bearingSourceIsGnss=true (defensivo). */
    fun getCurrentBearing(): String {
        val value = if (bearingSourceIsGnss && !currentGnssBearing.isNaN()) currentGnssBearing
        else currentSensorBearing
        return if (!value.isNaN()) "%.1f".format(java.util.Locale.US, value) else ""
    }

    fun getCurrentGnssSpeed(): String =
        currentGnssSpeed?.let { "%.2f".format(java.util.Locale.US, it) } ?: ""

    fun getCurrentLocationTimestamp(): String =
        if (currentLocationTimeMs > 0) currentLocationTimeMs.toString() else ""

    fun getCurrentLocationProvider(): String = currentLocationProviderLabel

    // =========================================================================
    // WakeLock — impede o CPU de dormir (Doze) enquanto a captura estiver ativa,
    // já que o app precisa continuar lendo tags/GPS com a tela apagada por dias.
    // Renovado periodicamente (ver rescheduleTimerJob) em vez de acquire() sem
    // timeout, para nunca deixar um wakelock "eterno" preso caso algo trave.
    // =========================================================================
    private var wakeLockRenewExecutor: ScheduledExecutorService? = null

    private fun acquireCaptureWakeLock() {
        try {
            if (wakeLock?.isHeld != true) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UHFLogger:CaptureWakeLock").apply {
                    setReferenceCounted(false)
                    acquire(WAKELOCK_RENEW_MS)
                }
                Log.i(TAG, "WakeLock adquirido")
            }
            // Renova o wakelock periodicamente em vez de segurar um acquire()
            // sem timeout — assim, se o Service travar por algum motivo, o
            // wakelock expira sozinho em WAKELOCK_RENEW_MS em vez de ficar
            // preso "para sempre" drenando a bateria.
            if (wakeLockRenewExecutor == null) {
                wakeLockRenewExecutor = Executors.newSingleThreadScheduledExecutor()
                wakeLockRenewExecutor?.scheduleWithFixedDelay(
                    { renewCaptureWakeLock() },
                    WAKELOCK_RENEW_MS / 2, WAKELOCK_RENEW_MS / 2, TimeUnit.MILLISECONDS
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adquirir WakeLock", e)
        }
    }

    private fun renewCaptureWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.acquire(WAKELOCK_RENEW_MS) }
        } catch (_: Exception) {}
    }

    private fun releaseCaptureWakeLock() {
        wakeLockRenewExecutor?.shutdownNow()
        wakeLockRenewExecutor = null
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }

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
    // @Volatile garante visibilidade entre threads (leitura serial, config,
    // stop). closePort() já usa synchronized(this) como seção crítica — os
    // pontos de write que adicionamos abaixo usam o MESMO lock (this), não um
    // lock separado, senão um write() e um closePort() concorrentes não se
    // excluiriam mutuamente de verdade (locks diferentes não protegem nada
    // entre si).
    @Volatile private var activePort: ISerialPort? = null   // USB or Bluetooth — single active port
    private var ioManager      : SerialInputOutputManager? = null
    private var btIoManager    : BluetoothInputOutputManager? = null  // BT equivalent of ioManager

    // Nome do dispositivo em uso na sessão atual — necessário pro loop de
    // reconexão USB (o BT já tem um nome fixo, BT_DEVICE_NAME).
    @Volatile private var activeDeviceName: String? = null

    private val stopExecutor   = Executors.newSingleThreadExecutor()
    private val configExecutor = Executors.newSingleThreadExecutor()
    // Executor dedicado ao loop de reconexão BT — separado para nunca bloquear stop/config
    @Volatile private var btReconnectExecutor: java.util.concurrent.ExecutorService? = null
    // Flag que controla exclusivamente o ciclo de vida do loop de reconexão — independente
    // de isPausedState, que oscila durante cada tentativa de conexão.
    private val btReconnectActive = java.util.concurrent.atomic.AtomicBoolean(false)

    // Mesma ideia, agora pro USB: antes disso, a reconexão USB dependia 100% do
    // usbReceiver (ACTION_USB_DEVICE_ATTACHED) registrado na MainActivity — se a
    // Activity fosse destruída com a tela apagada por muito tempo, o app nunca
    // mais retomava a leitura USB sozinho. Agora o próprio Service tenta de novo
    // periodicamente, igual já acontecia com BT.
    @Volatile private var usbReconnectExecutor: java.util.concurrent.ExecutorService? = null
    private val usbReconnectActive = java.util.concurrent.atomic.AtomicBoolean(false)

    // Auto-save
    private var autoSaveTagCount   : Int  = SettingsManager.DEFAULT_AUTO_SAVE_TAGS
    private var autoSaveIntervalMin: Long = SettingsManager.DEFAULT_AUTO_SAVE_MINUTES.toLong()
    private var autoSaveMode       : Int  = SettingsManager.DEFAULT_AUTO_SAVE_MODE
    private var autoSaveExecutor   : ScheduledExecutorService? = null
    private var autoSaveTimerJob   : ScheduledFuture<*>? = null
    // Tags acumuladas desde o último auto-save — reinicia após cada gravação
    private val tagsSinceLastSave  = AtomicInteger(0)

    // =========================================================================
    // Filtro de 3 camadas — configuração recarregada uma vez por sessão nova
    // (igual autoSaveTagCount/autoSaveIntervalMin acima), NÃO em cada
    // resume de reconexão BT/USB, pra não resetar a consolidação em
    // andamento só porque a antena piscou.
    // =========================================================================
    private var tagFilterEngine: TagFilterEngine? = null
    private var filterEnabled         = SettingsManager.DEFAULT_FILTER_ENABLED
    private var filterL1Enabled       = SettingsManager.DEFAULT_FILTER_L1_ENABLED
    private var filterL1Patterns      = SettingsManager.DEFAULT_FILTER_L1_PATTERNS
    private var filterL2Enabled       = SettingsManager.DEFAULT_FILTER_L2_ENABLED
    private var filterL2WindowMs      = SettingsManager.DEFAULT_FILTER_L2_WINDOW_MIN * 60_000L
    private var filterSweepIntervalMs = SettingsManager.DEFAULT_FILTER_L2_SWEEP_MIN * 60_000L
    private var filterPersistJob: ScheduledFuture<*>? = null
    private var filterSweepJob  : ScheduledFuture<*>? = null
    // D2: com a Camada 2 ativa, rotação por CONTAGEM de tags brutas deixa de
    // fazer sentido (as tags são consolidadas antes de chegar no arquivo) —
    // a rotação passa a ser só por tempo, com o mesmo intervalo da janela.
    private val filterForcesTimeOnlyRotation get() = filterEnabled && filterL2Enabled

    private var appContext: Context? = null
    private var activeAntennaType : String  = SettingsManager.ANTENNA_TYPE_JIETONG
    private var activeIsBluetooth : Boolean = false  // true=BT session, false=USB session

    // =========================================================================
    // Watchdog de silêncio — detecta "conexão zumbi": o socket continua de pé
    // (nenhum erro é lançado, então o mecanismo normal de reconexão nunca é
    // acionado), mas parou de chegar QUALQUER byte, mesmo com o módulo
    // continuando a escanear normalmente do outro lado. Sem isso, o app fica
    // preso pra sempre (visto num teste real de 12h+).
    //
    // Design: só assume "morto" depois de PERGUNTAR (manda start_inventory,
    // que o Winnix sempre responde, mesmo sem tag nenhuma no campo) — nunca
    // conclui isso só pelo silêncio sozinho, porque silêncio também é normal
    // (trecho do campo sem tag por perto pode durar bastante tempo).
    // =========================================================================
    @Volatile private var lastDataReceivedAt : Long = 0L
    @Volatile private var lastProbeSentAt    : Long = 0L
    private var consecutiveFailedProbes = 0
    private var watchdogExecutor: ScheduledExecutorService? = null

    // Winnix temperature tracking
    @Volatile private var winnixStartTemp     : String = ""
    // Temperatura lida via onNewData — usada tanto para temp. inicial quanto de encerramento em BT
    @Volatile private var winnixTempResult    : String = ""
    private val winnixTempLatch     = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch?>(null)
    // Sinalizado por onNewData quando a confirmação de parada 0x8D é recebida
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
        tagFilterEngine = TagFilterEngine(appContext!!)
        createNotificationChannel()
        startForegroundWithSafeType()

        // GPS/bússola/WakeLock começam a "esquentar" assim que o serviço existe
        // (app aberto), não só quando a captura começa de fato. Sem isso, todo
        // início de captura (principalmente em ambiente fechado, onde o
        // NETWORK_PROVIDER pode levar até ~1-2min pra conseguir o primeiro fix
        // "a frio") ficava com um buraco de dado logo no começo. Como o
        // aparelho fica ligado na energia do trator o tempo todo, manter isso
        // ativo entre sessões de captura tem custo de bateria desprezível.
        acquireCaptureWakeLock()
        startLocationAndSensors()

        // Watchdog de silêncio — roda o tempo todo (igual GPS/WakeLock acima),
        // mas só age de verdade quando isRunning=true (dentro de watchdogTick).
        // Reaproveita o mesmo padrão já validado da renovação do WakeLock:
        // try/catch dentro da tarefa agendada, pra uma exceção numa rodada não
        // derrubar as próximas (scheduleWithFixedDelay suprime execuções
        // futuras se uma delas lançar exceção sem ser capturada).
        watchdogExecutor = Executors.newSingleThreadScheduledExecutor()
        watchdogExecutor?.scheduleWithFixedDelay(
            {
                try { watchdogTick() } catch (e: Exception) { Log.e(TAG, "WATCHDOG: erro inesperado no tick: ${e.message}", e) }
            },
            WATCHDOG_CHECK_INTERVAL_MS, WATCHDOG_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS
        )

        // Retomada automática: se o Service está sendo (re)criado porque o
        // Android o religou sozinho depois de um SIGKILL/OOM-kill — não
        // porque o usuário abriu o app agora —, olhamos o que estava
        // acontecendo ANTES da morte do processo:
        //   - estava capturando → reconecta e retoma sozinho.
        //   - estava parado (app só aberto, sem clicar Iniciar) → fica parado,
        //     do jeito que estava. Não inicia captura por conta própria.
        val ctx = appContext
        if (ctx != null && SettingsManager.wasCapturing(ctx)) {
            val lastDevice = SettingsManager.getLastDeviceName(ctx)
            if (lastDevice != null) {
                Log.i(TAG, "Retomando captura automaticamente após restart do processo: $lastDevice")
                startCapture(lastDevice)
            }
        }
    }

    /**
     * A partir do Android 14 (API 34), iniciar um foreground service com o tipo
     * "location" ATIVO exige que ACCESS_FINE_LOCATION/COARSE já esteja concedida
     * NA HORA da chamada — senão o sistema lança SecurityException e o serviço
     * cai. Isso é um risco real logo na primeira instalação: a Activity ainda
     * está esperando o usuário tocar em "Permitir" enquanto este Service já
     * está subindo. Por isso resolvemos o tipo dinamicamente: só incluímos
     * "location" se a permissão já estiver concedida; caso contrário sobe só
     * com "dataSync", e cada vez que uma nova sessão de captura começa
     * (startLocationAndSensors) tentamos de novo — se a permissão já tiver sido
     * concedida nesse meio tempo, dá pra promover o tipo numa chamada futura.
     */
    private fun startForegroundWithSafeType() {
        val notification = buildNotification("Aguardando conexão USB…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return START_STICKY
                startCapture(deviceName)
            }
            ACTION_STOP -> stopCapture()
        }
        // START_STICKY: o intent que a MainActivity de fato usa (bindToService)
        // não carrega action/extras — startCapture() é chamado direto no objeto
        // do Service via Binder, não por Intent. Por isso não dá pra confiar em
        // START_REDELIVER_INTENT aqui: não há nada útil pra reentregar. Em vez
        // disso, START_STICKY garante que o PRÓPRIO SERVIÇO seja religado pelo
        // Android após ser morto — e o onCreate() acima decide, a partir do
        // estado persistido em SettingsManager, se deve retomar a captura ou
        // ficar parado.
        return START_STICKY
    }

    override fun onDestroy() {
        // Se Winnix estava ativo e app fecha sem Stop, envia 0x8C para parar o módulo
        synchronized(this) {
            if (activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX && activePort != null) {
                try {
                    activePort?.write(winnixBuildStopInventory(), 1000)
                    Thread.sleep(200)
                } catch (_: Exception) {}
            }
        }
        stopCapture(userInitiated = false)
        stopLocationAndSensors()
        releaseCaptureWakeLock()
        watchdogExecutor?.shutdownNow(); watchdogExecutor = null
        mainActivity = null
        btReconnectActive.set(false)
        btReconnectExecutor?.shutdownNow(); btReconnectExecutor = null
        usbReconnectActive.set(false)
        usbReconnectExecutor?.shutdownNow(); usbReconnectExecutor = null
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
        activeDeviceName  = deviceName

        // Persistido ANTES de tentar conectar: se o processo morrer nos
        // próximos milissegundos (ex: durante a própria tentativa de conexão),
        // ainda assim queremos que o próximo onCreate() tente retomar — reflete
        // a intenção do usuário ("deveria estar lendo"), não o sucesso da conexão.
        SettingsManager.setCaptureState(ctx, capturing = true, deviceName = deviceName)

        // GPS/bússola/wakelock ligados assim que a captura começa (ou retoma) —
        // independem da tela/Activity, então continuam ativos com a tela apagada
        // e mesmo com o trator parado por horas.
        acquireCaptureWakeLock()
        startLocationAndSensors()

        if (!resuming) {
            totalCount.set(0)
            tagsSinceLastSave.set(0)
            autoSaveTagCount    = SettingsManager.getAutoSaveTags(ctx)
            autoSaveIntervalMin = SettingsManager.getAutoSaveMinutes(ctx)
            autoSaveMode        = SettingsManager.getAutoSaveMode(ctx)

            filterEnabled         = SettingsManager.isFilterEnabled(ctx)
            filterL1Enabled       = SettingsManager.isFilterL1Enabled(ctx)
            filterL1Patterns      = SettingsManager.getFilterL1Patterns(ctx)
            filterL2Enabled       = SettingsManager.isFilterL2Enabled(ctx)
            filterL2WindowMs      = SettingsManager.getFilterL2WindowMin(ctx) * 60_000L
            filterSweepIntervalMs = SettingsManager.getFilterL2SweepMin(ctx) * 60_000L
            if (filterForcesTimeOnlyRotation) {
                // Mesma janela da Camada 2 — ver D2 no histórico de decisões do filtro.
                autoSaveIntervalMin = SettingsManager.getFilterL2WindowMin(ctx).toLong()
            }
            tagFilterEngine?.start(
                TagFilterEngine.Config(
                    filterEnabled = filterEnabled,
                    l1Enabled = filterL1Enabled,
                    l1PatternsCsv = filterL1Patterns,
                    l2Enabled = filterL2Enabled,
                    l2WindowMs = filterL2WindowMs,
                )
            )

            val prefix   = buildFileIdentifier(ctx, isBluetooth = deviceName == BT_DEVICE_NAME)
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
        winnixRingClear()  // descarta bytes residuais da sessão anterior

        if (deviceName == BT_DEVICE_NAME) {
            startBtConnection(deviceName, resuming)
        } else {
            startUsbConnection(deviceName, resuming)
        }
    }

    // ── USB connection ─────────────────────────────────────────────────────
    /**
     * Identificador usado no nome do arquivo CSV: MAC do módulo (sem os dois
     * pontos, que são inválidos em nome de arquivo no Windows) quando é BT,
     * ou o nome do aparelho Android (já existe pronto e sanitizado em
     * DriveHelper, usado pra organizar as pastas do Drive) quando é USB.
     * A estrutura de pastas do Drive não muda — só o nome do arquivo em si.
     */
    private fun buildFileIdentifier(ctx: Context, isBluetooth: Boolean): String {
        if (!isBluetooth) return DriveHelper.getDeviceName(ctx)
        return try {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            val mac = adapter?.bondedDevices?.firstOrNull { it.name == BT_DEVICE_NAME }?.address
            mac?.replace(":", "") ?: "BT"
        } catch (se: SecurityException) {
            Log.w(TAG, "buildFileIdentifier: sem permissão pra ler MAC do BT (${se.message})")
            "BT"
        }
    }

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
        lastDataReceivedAt = System.currentTimeMillis()
        lastProbeSentAt = 0L
        consecutiveFailedProbes = 0
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
                // Verifica permissão BLUETOOTH_CONNECT (obrigatória no Android 12+)
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
                    // Sem retry interno — o loop externo (startBtReconnectLoop) já retenta
                    // a cada BT_RECONNECT_INTERVAL_MS indefinidamente. Falhar rápido aqui
                    // e deixar o loop externo tentar é mais eficaz.
                    Log.e(TAG, "BT_CONNECT: socket connect FAILED: ${e.message}")
                    btPort.close()
                    if (!resuming) CsvExporter.cancelSession()
                    isPausedState.set(resuming)
                    onWrongAntennaType?.invoke("Winnix_BT: falha ao conectar")
                    return@submit
                }

                activePort = btPort
                Log.i(TAG, "BT_CONNECT: running probe...")
                if (!probeAntennaType(btPort, SettingsManager.ANTENNA_TYPE_WINNIX)) {
                    Log.w(TAG, "BT_CONNECT: probe FAILED")
                    btPort.close()
                    activePort = null
                    // Conexão OK mas módulo não respondeu ao probe — como é rápido (sem timeout de connect()),
                    // uma tentativa local de retry é barata e resolve problemas transitórios.
                    if (retryCount < 1) {
                        Log.i(TAG, "BT_CONNECT: retrying probe once")
                        Thread.sleep(BT_RETRY_DELAY_MS)
                        startBtConnection(deviceName, resuming, retryCount + 1)
                    } else {
                        isRunning.set(false)
                        if (resuming) isPausedState.set(true)
                        onWrongAntennaType?.invoke("Winnix_BT: módulo não respondeu.")
                    }
                    return@submit
                }

                Log.i(TAG, "BT_CONNECT: probe OK — starting Winnix capture")
                isRunning.set(true)
                lastDataReceivedAt = System.currentTimeMillis()
                lastProbeSentAt = 0L
                consecutiveFailedProbes = 0
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

    /**
     * @param userInitiated true = usuário apertou "Parar" (ou erro real) — persiste
     *   capturing=false, então o app NÃO retoma sozinho depois.
     *   false = chamado a partir de onDestroy() como limpeza de recursos (o processo
     *   está sendo derrubado por algum motivo fora do nosso controle) — mantemos o
     *   estado persistido como estava, para que se era pra estar capturando, o
     *   próximo onCreate() retome sozinho.
     */
    fun stopCapture(userInitiated: Boolean = true) {
        val wasRunning = isRunning.compareAndSet(true, false)
        val wasPaused  = isPausedState.get()
        if (!wasRunning && !wasPaused) return

        isPausedState.set(false)  // stops the reconnect loop if running
        btReconnectActive.set(false)
        btReconnectExecutor?.shutdownNow(); btReconnectExecutor = null
        usbReconnectActive.set(false)
        usbReconnectExecutor?.shutdownNow(); usbReconnectExecutor = null
        stopAutoSaveTimer()

        // Só marca "não capturando" se for parada REAL/intencional. Se for
        // onDestroy() sendo chamado porque o Android está derrubando o processo
        // (ex: falta de memória, sem sequer um SIGKILL bruto), o estado
        // persistido continua "capturando=true", e o próximo onCreate() retoma
        // sozinho — esse era exatamente o bug: onDestroy() chamava stopCapture()
        // sem distinção, apagando a intenção de retomar.
        if (userInitiated) {
            appContext?.let { SettingsManager.setCaptureState(it, capturing = false) }
        }

        stopExecutor.submit {
            sendWinnixStop()

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

            if (localBtIo != null) {
                closePort()        // interrompe stream.read() bloqueante → btIoManager sai via IOException
                localBtIo.stop()   // garante flag running=false
            } else {
                localIo?.stop()
                Thread.sleep(200)
                closePort()
            }

            // D1: se foi o usuário quem pediu Parar, trata tudo que ainda está
            // pendente na Camada 2 como se a janela tivesse expirado — nada
            // fica esperando um tempo que não vai mais passar. Numa morte de
            // processo (userInitiated=false) NÃO fazemos isso: o estado
            // persistido continua em disco e é recuperado no próximo start
            // (ver TagFilterEngine.start()).
            if (userInitiated) {
                tagFilterEngine?.flushAllNow()?.let { expired ->
                    if (expired.isNotEmpty()) {
                        tagBuffer.addAll(expired)
                        totalCount.addAndGet(expired.size)
                        Log.i(TAG, "Filter: ${expired.size} EPC(s) pendentes forçados no Stop (D1)")
                    }
                }
            }

            val tagCount = totalCount.get()
            val fileName = drainAndFinalize(winnixStopTemp)
            updateNotification("Captura encerrada — $tagCount tags")
            totalCount.set(0)

            // GPS/bússola/wakelock NÃO são mais desligados aqui — continuam
            // ativos enquanto o serviço existir (ver onCreate), pra não
            // "esfriar" o NETWORK_PROVIDER a cada ciclo Parar/Iniciar. Só
            // param de verdade em onDestroy().

            Log.i(TAG, "Capture stopped. Total: $tagCount, file: $fileName")
            onStatusChanged?.invoke(false)
            onStopComplete?.invoke(fileName, tagCount)
        }
    }

    fun saveAfterError() {
        isPausedState.set(false)  // stops reconnect loop if running
        btReconnectActive.set(false)
        btReconnectExecutor?.shutdownNow(); btReconnectExecutor = null
        usbReconnectActive.set(false)
        usbReconnectExecutor?.shutdownNow(); usbReconnectExecutor = null
        appContext?.let { SettingsManager.setCaptureState(it, capturing = false) }
        stopExecutor.submit {
            tagFilterEngine?.flushAllNow()?.let { expired ->
                if (expired.isNotEmpty()) {
                    tagBuffer.addAll(expired)
                    totalCount.addAndGet(expired.size)
                }
            }
            val tagCount = totalCount.get()
            val fileName = drainAndFinalize("")
            totalCount.set(0)
            // GPS/bússola/wakelock continuam ativos — ver onCreate/onDestroy.
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
                lastDataReceivedAt = System.currentTimeMillis()
                val lat  = getCurrentLatitude()
                val lon  = getCurrentLongitude()
                val brg  = getCurrentBearing()
                val spd  = getCurrentGnssSpeed()
                val locTs = getCurrentLocationTimestamp()
                val prov  = getCurrentLocationProvider()
                val rawTags = jietongDecoder.feed(data, lat, lon, brg, spd, locTs, prov)
                if (rawTags.isNotEmpty()) {
                    val tags = tagFilterEngine?.process(rawTags) ?: rawTags
                    if (tags.isNotEmpty()) {
                        tagBuffer.addAll(tags)
                        totalCount.addAndGet(tags.size)
                        val sinceLast = tagsSinceLastSave.addAndGet(tags.size)
                        if (!filterForcesTimeOnlyRotation && sinceLast >= autoSaveTagCount) {
                            rescheduleTimerJob()
                            autoSaveExecutor?.submit { flushBufferToDisk() }
                        }
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

        configExecutor.submit {
            try {
                winnixConfigSequence(btPort, antennas, powerDbm, workingMs, invMode, inactiveMs)

                // Temperatura inicial — lida ANTES de iniciar o btIoManager (sem concorrência na porta)
                val startTemp = winnixReadTemperature(btPort)
                winnixStartTemp = if (startTemp != null) "%.1f".format(java.util.Locale.US, startTemp) else ""
                Log.i(TAG, "Winnix BT start temperature: $winnixStartTemp°C")

                Thread.sleep(200)
                btPort.purgeHwBuffers(false, true)
                btPort.write(winnixBuildStartInventory(), 2000)
                Log.i(TAG, "Winnix BT inventory started")

                // Garante que stopCapture() não foi chamado enquanto o configExecutor ainda inicializava
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

    // Accumulation buffer for detecting fragmented 0x8D/0x35 frames from BT.
    // Antes: CopyOnWriteArrayList<Byte>, que copia o array inteiro a cada
    // add()/removeAt() — para centenas de tags/seg isso gera lixo e pressão de
    // GC desnecessários. Agora: ring buffer manual de tamanho fixo (256 bytes),
    // sincronizado (é escrito pela thread de leitura serial e lido pela thread
    // de config/temperatura), sem nenhuma alocação por byte.
    private val winnixRingLock  = Any()
    private val winnixRing      = ByteArray(256)
    private var winnixRingHead  = 0   // próxima posição de escrita
    private var winnixRingCount = 0   // quantos bytes válidos (até 256)

    private fun winnixRingAppend(data: ByteArray) {
        synchronized(winnixRingLock) {
            for (b in data) {
                winnixRing[winnixRingHead] = b
                winnixRingHead = (winnixRingHead + 1) % winnixRing.size
                if (winnixRingCount < winnixRing.size) winnixRingCount++
            }
        }
    }

    private fun winnixRingSnapshot(): ByteArray {
        synchronized(winnixRingLock) {
            val out   = ByteArray(winnixRingCount)
            val start = (winnixRingHead - winnixRingCount + winnixRing.size) % winnixRing.size
            for (i in 0 until winnixRingCount) out[i] = winnixRing[(start + i) % winnixRing.size]
            return out
        }
    }

    private fun winnixRingClear() {
        synchronized(winnixRingLock) { winnixRingCount = 0; winnixRingHead = 0 }
    }

    private fun winnixOnNewData(data: ByteArray) {
        lastDataReceivedAt = System.currentTimeMillis()
        // Acumula bytes — o BT pode fragmentar frames em múltiplas chamadas onNewData
        winnixRingAppend(data)

        val buf = winnixRingSnapshot()

        // Verifica confirmação de parada (0x8D)
        if (!winnixStopConfirmed.get()) {
            for (i in 0 until buf.size - 4) {
                if (buf[i] == 0xA5.toByte() && buf[i+1] == 0x5A.toByte()
                    && buf[i+4] == 0x8D.toByte()) {
                    Log.i(TAG, "Winnix stop confirmed (0x8D) via onNewData")
                    winnixStopConfirmed.set(true)
                    winnixRingClear()
                    break
                }
            }
        }

        // Verifica resposta de temperatura (0x35) — preenche o latch para winnixReadTemperatureBt()
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
                    winnixRingClear()
                    break
                }
            }
        }

        val lat  = getCurrentLatitude()
        val lon  = getCurrentLongitude()
        val brg  = getCurrentBearing()
        val spd  = getCurrentGnssSpeed()
        val locTs = getCurrentLocationTimestamp()
        val prov  = getCurrentLocationProvider()
        val rawTags = winnixDecoder.feed(data, lat, lon, brg, spd, locTs, prov)
        if (rawTags.isNotEmpty()) {
            val processedTags = rawTags.toMutableList()
            val temp = winnixStartTemp
            if (temp.isNotEmpty()) {
                winnixStartTemp = ""
                processedTags[0] = processedTags[0].copy(temperature = temp)
            }
            val tags = tagFilterEngine?.process(processedTags) ?: processedTags
            if (tags.isNotEmpty()) {
                tagBuffer.addAll(tags)
                totalCount.addAndGet(tags.size)
                val sinceLast = tagsSinceLastSave.addAndGet(tags.size)
                if (!filterForcesTimeOnlyRotation && sinceLast >= autoSaveTagCount) {
                    rescheduleTimerJob()
                    autoSaveExecutor?.submit { flushBufferToDisk() }
                }
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
        winnixStopConfirmed.set(false)
        try {
            Log.i(TAG, "Sending stop inventory (0x8C)")
            // Só o write() precisa do lock — não a espera de confirmação abaixo,
            // pra não segurar o lock por até 3s à toa.
            val sent = synchronized(this) { activePort?.write(winnixBuildStopInventory(), 2000); activePort != null }
            if (!sent) return
            // Aguarda até 3s pela confirmação 0x8D via onNewData
            // Envia apenas UMA vez — múltiplos 0x8C confundem o módulo
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
     * mode: modo de tabela 1-5 (conforme SettingsManager WINNIX_INV_MODE_*).
     * Valores de DByte0 no protocolo são 0-4, com offset -1 em relação à tabela.
     * Verificado contra doc: Fast read (Mode 2) = DByte0 0x01.
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
     * Lê a temperatura em sessões BT — usa latch preenchido via onNewData.
     * O btIoManager consome todos os bytes via onNewData, então winnixReadTemperature
     * (polling de available()) não funciona — concorreria com o btIoManager pelo mesmo stream.
     * Em vez disso: define o latch, envia 0x34, aguarda onNewData detectar 0x35 e sinalizar.
     * Chamada SOMENTE com btIoManager ativo (durante captura ou na temperatura de encerramento).
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
                // Procura resposta 0x35 em qualquer posição dos bytes coletados
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
    /**
     * Roda a cada WATCHDOG_CHECK_INTERVAL_MS, o tempo todo (mesmo sem
     * capturar — só age quando isRunning=true). Detecta silêncio prolongado
     * e distingue "sem tag por perto" (normal) de "conexão morta de verdade"
     * através de uma sonda ativa, em vez de assumir o pior só pelo silêncio.
     */
    private fun watchdogTick() {
        if (!isRunning.get()) return
        val now     = System.currentTimeMillis()
        val silence = now - lastDataReceivedAt

        if (silence < WATCHDOG_SILENCE_THRESHOLD_MS) {
            if (consecutiveFailedProbes > 0) {
                Log.i(TAG, "WATCHDOG: dado voltou a chegar — só estava quieto (sem tag por perto), não precisa reconfigurar nada")
            }
            consecutiveFailedProbes = 0
            return
        }

        // Silêncio prolongado — só manda a sonda de novo a cada
        // WATCHDOG_PROBE_INTERVAL_MS (não a cada tick de 10s, senão martelaria
        // o módulo). Enquanto o silêncio persistir até o próximo horário de
        // sonda, isso já significa (por construção) que a sonda anterior não
        // trouxe resposta — não precisa de uma máquina de estado separada pra
        // isso, o próprio "silêncio ainda > threshold" já prova.
        if (now - lastProbeSentAt < WATCHDOG_PROBE_INTERVAL_MS) return

        val port = activePort
        if (port == null) return  // sem porta ativa — outro caminho já cuida disso

        lastProbeSentAt = now
        consecutiveFailedProbes++
        Log.w(TAG, "WATCHDOG: ${silence / 1000}s sem nenhum dado — mandando sonda (start_inventory), tentativa #$consecutiveFailedProbes")
        try {
            synchronized(this) { port.write(winnixBuildStartInventory(), 1000) }
        } catch (e: Exception) {
            Log.e(TAG, "WATCHDOG: falha ao mandar sonda: ${e.message}")
        }

        if (consecutiveFailedProbes >= WATCHDOG_MAX_FAILED_PROBES) {
            Log.e(TAG, "WATCHDOG: sem resposta após $consecutiveFailedProbes sondas — conexão zumbi confirmada, forçando reconexão")
            consecutiveFailedProbes = 0
            forceReconnectDueToSilence()
        }
    }

    /**
     * Força o fechamento da porta ativa — isso faz a leitura bloqueada (presa
     * esperando um dado que nunca chega) lançar IOException, disparando
     * handleRunError() normalmente, que já sabe iniciar o loop de reconexão
     * certo (BT ou USB). Não precisamos duplicar lógica de reconexão nenhuma
     * — só destravar o que já existe.
     */
    private fun forceReconnectDueToSilence() {
        try {
            synchronized(this) { activePort?.close() }
        } catch (e: Exception) {
            Log.e(TAG, "WATCHDOG: erro ao forçar fechamento da porta: ${e.message}")
        }
    }

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

        // BT tem loop de reconexão próprio; USB agora também — antes dependia
        // 100% do usbReceiver da MainActivity (ACTION_USB_DEVICE_ATTACHED), que
        // não existe se a Activity foi destruída com a tela apagada por muito
        // tempo. Com os dois loops, a retomada não depende mais da Activity.
        if (activeIsBluetooth && activeAntennaType == SettingsManager.ANTENNA_TYPE_WINNIX &&
            btIoManager == null && activePort == null) {
            Log.i(TAG, "BT_RECONNECT: BT session lost — starting reconnect loop")
            startBtReconnectLoop()
        } else if (!activeIsBluetooth && activePort == null && activeDeviceName != null) {
            Log.i(TAG, "USB_RECONNECT: USB session lost — starting reconnect loop")
            startUsbReconnectLoop()
        } else {
            Log.i(TAG, "RECONNECT: not starting loop — isBT=$activeIsBluetooth antenna=$activeAntennaType btIo=$btIoManager port=$activePort")
        }
    }

    /**
     * Retry loop para sessões USB — espelha startBtReconnectLoop(). Antes disso,
     * uma sessão USB que perdesse o sinal (cabo balançou, hub USB reiniciou,
     * fabricante cortou energia da porta em modo de economia) só voltava a
     * capturar se a MainActivity estivesse viva pra receber o broadcast
     * ACTION_USB_DEVICE_ATTACHED. Com a tela apagada por horas/dias, isso não
     * era garantido. Agora o próprio Service tenta reabrir a porta sozinho,
     * periodicamente, independente da Activity existir ou não.
     */
    private fun startUsbReconnectLoop() {
        val deviceName = activeDeviceName ?: return
        usbReconnectExecutor?.shutdownNow()
        usbReconnectExecutor = Executors.newSingleThreadExecutor()
        usbReconnectActive.set(true)
        usbReconnectExecutor?.submit {
            Log.i(TAG, "USB_RECONNECT: loop started, will retry every ${USB_RECONNECT_INTERVAL_MS}ms")
            var attempt = 0
            while (usbReconnectActive.get() && !isRunning.get()) {
                attempt++
                try { Thread.sleep(USB_RECONNECT_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (!usbReconnectActive.get() || isRunning.get()) break

                if (!isPausedState.get() && !isRunning.get()) continue  // tentativa anterior ainda em andamento

                // try/catch aqui é essencial: sem isso, QUALQUER exceção não
                // prevista em qualquer lugar dentro de startCapture() (GPS,
                // WakeLock, I/O de arquivo, o que for) mata esse loop inteiro
                // silenciosamente pra sempre — sem log, sem crash visível, só
                // parando de tentar reconectar. Foi exatamente isso que
                // aconteceu num teste real de 12h+ sem supervisão.
                try {
                    Log.i(TAG, "USB_RECONNECT: attempt #$attempt — calling startCapture (same as Start button)")
                    startCapture(deviceName)

                    var waited = 0L
                    while (waited < USB_ATTEMPT_SETTLE_MS && !isRunning.get() && usbReconnectActive.get()) {
                        try { Thread.sleep(USB_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                        waited += USB_POLL_INTERVAL_MS
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "USB_RECONNECT: exceção na tentativa #$attempt — loop CONTINUA (não morre): ${e.message}", e)
                }
            }
            Log.i(TAG, "USB_RECONNECT: loop exiting — isRunning=${isRunning.get()}")
        }
    }

    /**
     * Loop de reconexão BT — chama startCapture() periodicamente com BT_DEVICE_NAME.
     * Continua rodando enquanto:
     *   - isPausedState == true  (não parado pelo usuário)
     *   - isRunning == false     (ainda não capturando)
     * Para automaticamente quando:
     *   - Usuário clica Parar → stopCapture() → isPausedState = false
     *   - Reconexão tem sucesso → startCapture() → isRunning = true
     *   - onDestroy() → btReconnectExecutor.shutdownNow()
     */
    private fun startBtReconnectLoop() {
        btReconnectExecutor?.shutdownNow()
        btReconnectExecutor = Executors.newSingleThreadExecutor()
        // Flag dedicada — controla SOMENTE o ciclo de vida deste loop.
        // Independente de isPausedState, que startCapture() altera durante cada tentativa.
        // Evita que o loop saia prematuramente enquanto uma tentativa ainda está em andamento
        // no configExecutor (BluetoothSocket.connect() pode levar 10-12s para timeout).
        btReconnectActive.set(true)
        btReconnectExecutor?.submit {
            Log.i(TAG, "BT_RECONNECT: loop started, will retry every ${BT_RECONNECT_INTERVAL_MS}ms")
            var attempt = 0
            while (btReconnectActive.get() && !isRunning.get()) {
                attempt++
                Log.i(TAG, "BT_RECONNECT: waiting ${BT_RECONNECT_INTERVAL_MS}ms before attempt #$attempt")
                try { Thread.sleep(BT_RECONNECT_INTERVAL_MS) } catch (_: InterruptedException) { break }

                if (!btReconnectActive.get() || isRunning.get()) {
                    Log.i(TAG, "BT_RECONNECT: loop exiting — active=${btReconnectActive.get()} isRunning=${isRunning.get()}")
                    break
                }

                // Só chama startCapture se nenhuma tentativa estiver em andamento.
                // isPausedState fica false durante a tentativa — não sair do loop por isso.
                if (!isPausedState.get() && !isRunning.get()) {
                    Log.i(TAG, "BT_RECONNECT: previous attempt still in flight, skipping this cycle")
                    continue
                }

                Log.i(TAG, "BT_RECONNECT: attempt #$attempt — calling startCapture (same as Start button)")
                // try/catch aqui é essencial: sem isso, QUALQUER exceção não
                // prevista dentro de startCapture() mata esse loop inteiro
                // silenciosamente pra sempre — sem log, sem crash visível, só
                // parando de tentar reconectar. Foi exatamente isso que
                // aconteceu num teste real de 12h+ sem supervisão.
                try {
                    startCapture(BT_DEVICE_NAME)

                    // Polling em pequenos incrementos — sai assim que isRunning se tornar true,
                    // sem esperar o tempo máximo no caso comum (reconexão rápida).
                    Log.i(TAG, "BT_RECONNECT: waiting for result (polling)...")
                    val settleDeadline = System.currentTimeMillis() + BT_ATTEMPT_SETTLE_MS
                    while (System.currentTimeMillis() < settleDeadline) {
                        if (isRunning.get()) {
                            Log.i(TAG, "BT_RECONNECT: reconnected early — exiting wait")
                            break
                        }
                        try { Thread.sleep(BT_POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
                    }
                    Log.i(TAG, "BT_RECONNECT: after attempt #$attempt — isPaused=${isPausedState.get()} isRunning=${isRunning.get()}")
                } catch (e: Exception) {
                    Log.e(TAG, "BT_RECONNECT: exceção na tentativa #$attempt — loop CONTINUA (não morre): ${e.message}", e)
                }
            }
            Log.i(TAG, "BT_RECONNECT: loop ended after $attempt attempts (active=${btReconnectActive.get()} isRunning=${isRunning.get()})")
        }
    }

    // =========================================================================
    // Auto-save
    // =========================================================================
    private fun startAutoSaveTimer() {
        autoSaveExecutor = Executors.newSingleThreadScheduledExecutor()
        rescheduleTimerJob()
        scheduleFilterJobs()
    }

    /**
     * Agenda, no MESMO executor do auto-save (sem thread pool extra), os dois
     * jobs periódicos do filtro: persistência em lote do estado da Camada 2
     * (durabilidade contra SIGKILL, sempre ativa junto com a Camada 2 — ver
     * TagFilterEngine) e o sweep que expira entradas vencidas da Camada 2.
     * Reagendado a cada (re)conexão bem-sucedida, igual startAutoSaveTimer()
     * já fazia com rescheduleTimerJob().
     */
    private fun scheduleFilterJobs() {
        filterPersistJob?.cancel(false); filterPersistJob = null
        filterSweepJob?.cancel(false); filterSweepJob = null
        if (!filterEnabled || !filterL2Enabled) return

        filterPersistJob = autoSaveExecutor?.scheduleWithFixedDelay(
            { try { tagFilterEngine?.persistDirtyNow() } catch (e: Exception) { Log.e(TAG, "Filter persist error", e) } },
            FILTER_PERSIST_INTERVAL_MS, FILTER_PERSIST_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
        filterSweepJob = autoSaveExecutor?.scheduleWithFixedDelay(
            { try { runFilterSweep() } catch (e: Exception) { Log.e(TAG, "Filter sweep error", e) } },
            filterSweepIntervalMs, filterSweepIntervalMs, TimeUnit.MILLISECONDS
        )
    }

    /** Camada 2: entradas expiradas viram tags normais de novo, prontas pro auto-save gravar. */
    private fun runFilterSweep() {
        if (!isRunning.get()) return
        val expired = tagFilterEngine?.sweepExpired() ?: emptyList()
        if (expired.isEmpty()) return
        tagBuffer.addAll(expired)
        totalCount.addAndGet(expired.size)
        tagsSinceLastSave.addAndGet(expired.size)
        Log.i(TAG, "Filter L2: ${expired.size} EPC(s) expiraram — enfileirados para gravação")
    }

    /**
     * (Re)agenda o job de auto-save por tempo a partir de agora.
     * Chamado na inicialização e a cada save disparado por contagem de tags,
     * reiniciando o contador — evita um save duplicado logo após o save por contagem.
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
        filterPersistJob?.cancel(false)
        filterPersistJob = null
        filterSweepJob?.cancel(false)
        filterSweepJob = null
        autoSaveExecutor?.shutdown()
        autoSaveExecutor = null
    }

    private fun flushBufferToDisk() {
        if (!isRunning.get()) return

        val batch = mutableListOf<TagRecord>()
        while (tagBuffer.isNotEmpty()) tagBuffer.poll()?.let { batch.add(it) }

        // Sem tags novas a gravar
        if (batch.isEmpty()) {
            Log.d(TAG, "Auto-save skipped — no new tags")
            return
        }

        // Reinicia o contador de tags por intervalo
        tagsSinceLastSave.set(0)

        val ctx = appContext ?: return

        if (autoSaveMode == SettingsManager.AUTO_SAVE_MODE_NEW_FILE) {
            // Modo arquivo novo: encerra a sessão atual e abre uma nova
            val fileName = CsvExporter.finalizeSession(batch)
            Log.i(TAG, "Auto-save (new file): $fileName — ${batch.size} tags")
            // Abre nova sessão para o próximo lote
            val prefix   = buildFileIdentifier(ctx, isBluetooth = activeIsBluetooth)
            val newFile  = CsvExporter.startSession(ctx, prefix)
            Log.i(TAG, "New session started: $newFile")
            updateNotification("Capturando… (${totalCount.get()} tags)")
            onAutoSaved?.invoke(totalCount.get())
        } else {
            // Modo append: acrescenta ao arquivo corrente (padrão)
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

        // Nenhuma tag lida — cancela sem criar CSV vazio
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
        private const val BT_RETRY_DELAY_MS       = 2000L  // delay before the single probe retry
        private const val BT_RECONNECT_INTERVAL_MS= 5000L  // retry interval when session is paused
        // Deve superar o pior caso: timeout do BluetoothSocket.connect() (~12s) é um
        // timeout do sistema Android, fora do nosso controle. Pior caso por tentativa:
        // 1 connect() (~12s) + 1 retry de probe (~2.5s) = ~14.5s → 18s com margem.
        private const val BT_ATTEMPT_SETTLE_MS     = 18000L
        // Intervalo de polling: pequeno o suficiente para reagir rápido, grande o suficiente para não ser busy-loop.
        private const val BT_POLL_INTERVAL_MS      = 500L

        // USB — abrir a porta é rápido (sem handshake de pareamento/timeout como
        // BT), então intervalos mais curtos são seguros aqui.
        private const val USB_RECONNECT_INTERVAL_MS = 3000L
        private const val USB_ATTEMPT_SETTLE_MS      = 5000L
        private const val USB_POLL_INTERVAL_MS       = 300L

        // GNSS — 500ms/0m: prioriza recência (trator lento, "andou 1m" descartava
        // fixes válidos demais). O chip nunca entrega mais rápido do que consegue
        // de verdade — isso é só o pedido máximo, não uma garantia.
        private const val GPS_UPDATE_INTERVAL_MS = 500L
        private const val GPS_UPDATE_MIN_METERS  = 0f
        private const val GPS_MAX_LOCATION_AGE_MS = 30_000L
        private const val GPS_MAX_ACCURACY_M      = 50f
        // "Meio termo" acordado: aceita o fix mais novo mesmo se um pouco pior
        // que o atual (nunca "congela" a posição por rejeição), mas rejeita se
        // for MUITO pior (>3x) — o teto absoluto de 50m acima já barra o lixo
        // total (galpão fechado etc), isso aqui só evita descartar degradação
        // razoável de sinal em movimento.
        private const val GPS_ACCURACY_DEGRADE_FACTOR = 3.0f
        // Teto absoluto de acurácia aceito quando o fix entrante confirma via
        // Doppler que estamos em movimento — segundo caminho de aceitação em
        // isBetterLocation(), paralelo ao relativo. Evita o "fix-âncora" (fix
        // muito bom obtido parado bloqueando updates degradados por multipath)
        // sem abrir para fixes ruins (>30m ainda são rejeitados mesmo em movimento).
        // Valor de partida conservador — ajustar para 35m se dados de campo
        // mostrarem multipath de telhado metálico excedendo 30m com frequência.
        private const val GPS_MOVING_ABSOLUTE_MAX_M = 30f

        // Bearing híbrido — histerese pra não trocar de fonte a cada oscilação
        // de velocidade perto do limiar (ex: reduzindo numa curva).
        private const val BEARING_SPEED_HIGH_MS = 1.2f  // acima disso, passa a usar GNSS
        private const val BEARING_SPEED_LOW_MS  = 0.8f  // abaixo disso, volta pra sensores

        // WakeLock renovado a cada 15 min — cobre captura de dias sem nunca
        // segurar um acquire() sem timeout.
        private const val WAKELOCK_RENEW_MS = 15 * 60 * 1000L

        // Watchdog de silêncio — checa a cada 10s; considera "quieto" só
        // depois de 30s sem nenhum byte (tempo o suficiente pra não confundir
        // com um trecho normal do campo sem tag); manda a sonda no máximo a
        // cada 30s (não martela o módulo); desiste e força reconexão só
        // depois de 3 sondas seguidas sem resposta (~90s de silêncio
        // confirmado mesmo perguntando ativamente).
        private const val WATCHDOG_CHECK_INTERVAL_MS   = 10_000L
        private const val WATCHDOG_SILENCE_THRESHOLD_MS = 30_000L
        private const val WATCHDOG_PROBE_INTERVAL_MS    = 30_000L
        private const val WATCHDOG_MAX_FAILED_PROBES    = 3

        // Filtro — intervalo do batch write da Camada 2/3 (persistência do
        // estado de consolidação em Room). Pior caso de perda num SIGKILL:
        // as atualizações de RSSI ocorridas só nesta janela — nunca a
        // entrada inteira, já que o batch anterior já está em disco.
        private const val FILTER_PERSIST_INTERVAL_MS = 2_500L
    }
}