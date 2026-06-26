package com.uhflogger.serial

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Replicates the behavior of SerialInputOutputManager but for Bluetooth streams.
 * Runs a blocking read loop on a dedicated thread, calling onNewData() when
 * data arrives — exactly the same contract as SerialInputOutputManager.Listener.
 *
 * Usage:
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
        private const val READ_TIMEOUT  = 200   // ms per read attempt
        private const val IDLE_SLEEP_MS = 20L   // yield when no data — prevents busy-loop
    }

    fun stop() {
        running.set(false)
    }

    override fun run() {
        Log.i(TAG, "BT read loop started")
        try {
            while (running.get()) {
                val n = try {
                    port.read(buf, 0)  // blocking — returns when data arrives or throws on disconnect
                } catch (e: Exception) {
                    if (running.get()) {
                        Log.e(TAG, "Read error (disconnect detected): ${e.message}")
                        listener.onRunError(e)
                    }
                    break
                }
                if (n > 0) {
                    listener.onNewData(buf.copyOf(n))
                } else if (n < 0) {
                    // stream.read() returns -1 on end-of-stream (clean disconnect)
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