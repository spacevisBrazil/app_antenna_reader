package com.uhflogger.serial

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Equivalente ao SerialInputOutputManager para streams Bluetooth.
 * Executa um loop de leitura bloqueante em thread dedicada, chamando onNewData() quando
 * dados chegam — mesmo contrato do SerialInputOutputManager.Listener.
 *
 * Uso:
 *   val manager = BluetoothInputOutputManager(btPort, listener)
 *   Executors.newSingleThreadExecutor().submit(manager)
 *   ...
 *   manager.stop()
 */
class BluetoothInputOutputManager(
    private val port    : BluetoothSerialPort,
    private val listener: Listener
) : Runnable {

    interface Listener {
        fun onNewData(data: ByteArray)
        fun onRunError(e: Exception)
    }

    private val running = AtomicBoolean(true)
    private val buf     = ByteArray(4096)

    companion object {
        private const val TAG          = "BtIOManager"
        private const val READ_TIMEOUT  = 200   // ms por tentativa de leitura
        private const val IDLE_SLEEP_MS = 20L   // yield quando sem dados — evita busy-loop
    }

    fun stop() {
        running.set(false)
    }

    override fun run() {
        Log.i(TAG, "BT read loop started")
        try {
            while (running.get()) {
                val n = try {
                    port.read(buf, 0)  // bloqueante — retorna quando há dados ou lança ao desconectar
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Read error (disconnect detected): ${e.message}")
                        listener.onRunError(e)
                    }
                    break
                }
                if (n > 0) {
                    // Segunda camada de defesa: o listener (winnixOnNewData/
                    // onNewData) já se protege internamente, mas se algo escapar
                    // mesmo assim, não pode matar esta thread — sem isso o loop
                    // morre em silêncio (Runnable submetido a um Executor sem
                    // ninguém chamar .get() no Future), isRunning fica travado em
                    // true, e nem o watchdog nem forceReconnectDueToSilence()
                    // detectam a queda (não há leitura bloqueada pra estourar
                    // IOException). Vira zumbi permanente sem esse catch.
                    try {
                        listener.onNewData(buf.copyOf(n))
                    } catch (e: Exception) {
                        Log.e(TAG, "Listener error on new data — descartado, leitura CONTINUA: ${e.message}", e)
                    }
                } else if (n < 0) {
                    // stream.read() retorna -1 no fim do stream (desconexão limpa)
                    Log.w(TAG, "BT stream ended (read returned -1)")
                    if (running.get()) listener.onRunError(java.io.IOException("BT stream closed"))
                    break
                }
            }
        } finally {
            Log.i(TAG, "BT read loop stopped")
        }
    }
}