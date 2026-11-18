package com.uhflogger.analytics

import android.content.Context
import android.os.Build
import android.util.Log
import com.mixpanel.android.mpmetrics.MixpanelAPI
import com.uhflogger.BuildConfig
import com.uhflogger.backend.DeviceIdentity
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fachada única do Mixpanel. É o mesmo projeto do app de campo (spacevis-mobile);
 * a super property `app = uhf_logger` separa os dois.
 *
 * Regras que todo chamador assume:
 *  - NUNCA lança exceção e NUNCA bloqueia: tudo vai para uma thread própria.
 *    Ela é chamada da thread do serial e dos loops de reconexão, e uma
 *    exceção ou espera de disco ali mudaria o comportamento da conexão.
 *  - Sem token (flavor hml) é no-op: o SDK nem é inicializado.
 *  - A fila offline é a do próprio SDK (persistida em disco, enviada quando
 *    houver rede), então evento disparado no campo sem sinal não se perde.
 */
object Analytics {
    private const val TAG = "Analytics"

    private val enabled: Boolean = BuildConfig.MIXPANEL_TOKEN.isNotEmpty()

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "analytics").apply { isDaemon = true }
    }

    @Volatile private var mixpanel: MixpanelAPI? = null

    fun init(context: Context) {
        if (!enabled) return
        val app = context.applicationContext
        // Fila de thread única: os track() enfileirados antes do fim do init
        // rodam depois dele, em ordem.
        submit {
            val mp = MixpanelAPI.getInstance(app, BuildConfig.MIXPANEL_TOKEN, false)
            val deviceId = DeviceIdentity.readerId(app)
            if (deviceId.isNotEmpty()) mp.identify(deviceId)
            mp.registerSuperProperties(JSONObject().apply {
                put("app", "uhf_logger")
                put("app_version", BuildConfig.VERSION_NAME)
                put("app_build", BuildConfig.VERSION_CODE)
                put("device_model", Build.MODEL.orEmpty())
                put("device_manufacturer", Build.MANUFACTURER.orEmpty())
                put("os_version", Build.VERSION.RELEASE.orEmpty())
                put("environment", BuildConfig.FLAVOR)
                put("device_id", deviceId)
            })
            mixpanel = mp
        }
    }

    fun track(event: String, properties: Map<String, Any?> = emptyMap()) {
        if (!enabled) return
        submit {
            val json = JSONObject()
            for ((key, value) in properties) {
                if (value != null) json.put(key, value)
            }
            mixpanel?.track(event, json)
        }
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    Log.w(TAG, "analytics falhou (ignorado): ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            // RejectedExecutionException ou OOM criando a tarefa: telemetria
            // nunca derruba quem chamou.
            Log.w(TAG, "analytics não enfileirou (ignorado): ${t.javaClass.simpleName}")
        }
    }
}
