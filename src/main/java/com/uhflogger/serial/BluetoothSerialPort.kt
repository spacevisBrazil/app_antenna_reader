package com.uhflogger.serial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth SPP (Serial Port Profile) implementation of ISerialPort.
 * Uses RFCOMM with the standard SPP UUID.
 *
 * @SuppressLint("MissingPermission") is safe here because:
 * - BLUETOOTH_CONNECT permission is checked in UHFReaderService.startBtConnection()
 *   before this class is instantiated or connect() is called.
 * - All calls are also wrapped in try/catch SecurityException as defense in depth.
 */
@SuppressLint("MissingPermission")
class BluetoothSerialPort(private val device: BluetoothDevice) : ISerialPort {

    private var socket      : BluetoothSocket? = null
    private var inputStream : InputStream?     = null
    private var outputStream: OutputStream?    = null

    @Volatile private var _connected = false
    override val isConnected: Boolean get() = _connected
    override val portName   : String  get() = try {
        "BT:${device.name ?: device.address}"
    } catch (_: SecurityException) {
        "BT:${device.address}"
    }

    companion object {
        private const val TAG = "BluetoothSerialPort"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    /**
     * Connect to the device.
     * Tries secure RFCOMM first, then insecure, then fallback via reflection
     * (port 1 directly) which works when the ACL link is already established
     * by the remote device (ESP32 auto-reconnect scenario).
     */
    fun connect() {
        val name = try { device.name } catch (_: SecurityException) { device.address }
        Log.i(TAG, "Connecting to $name (${device.address})")
        val s = tryConnect()
        socket       = s
        inputStream  = s.inputStream
        outputStream = s.outputStream
        _connected   = true
        Log.i(TAG, "Connected to $name")
    }

    private fun tryConnect(): BluetoothSocket {
        // Attempt 1: standard secure RFCOMM
        try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            Log.d(TAG, "Connected via secure RFCOMM")
            return s
        } catch (e: Exception) {
            Log.w(TAG, "Secure RFCOMM failed: ${e.message} — trying insecure")
        }

        // Attempt 2: insecure RFCOMM (no authentication)
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            Log.d(TAG, "Connected via insecure RFCOMM")
            return s
        } catch (e: Exception) {
            Log.w(TAG, "Insecure RFCOMM failed: ${e.message} — trying reflection fallback")
        }

        // Attempt 3: reflection fallback — uses channel 1 directly
        // Works when ACL link is already established by the remote device
        try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.java)
            val s = method.invoke(device, 1) as BluetoothSocket
            s.connect()
            Log.d(TAG, "Connected via reflection (channel 1)")
            return s
        } catch (e: Exception) {
            Log.e(TAG, "All connection attempts failed: ${e.message}")
            val name = try { device.name } catch (_: SecurityException) { device.address }
            throw Exception("Failed to connect to $name: ${e.message}")
        }
    }

    override fun write(data: ByteArray, timeout: Int): Int {
        return try {
            outputStream?.write(data)
            outputStream?.flush()
            data.size
        } catch (e: Exception) {
            Log.e(TAG, "Write error: ${e.message}")
            throw e
        }
    }

    /**
     * Blocking read — blocks until data arrives OR throws IOException on disconnect.
     * Critical: available() polling never throws on disconnect so the IOManager
     * never detects it. Blocking read() does — propagates to onRunError → reconnect.
     */
    override fun read(buf: ByteArray, timeout: Int): Int {
        val stream = inputStream ?: return 0
        return if (timeout == 0) {
            // Blocking read — used by BluetoothInputOutputManager for continuous data.
            // Blocks until data arrives OR throws IOException on disconnect.
            stream.read(buf, 0, buf.size)
        } else {
            // Timed read: spawn a thread that does blocking read, interrupt after timeout.
            // More reliable than available() polling on BT sockets.
            var result = 0
            var exception: Exception? = null
            val readerThread = Thread {
                try {
                    result = stream.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    exception = e
                }
            }
            readerThread.isDaemon = true
            readerThread.start()
            readerThread.join(timeout.toLong())
            if (readerThread.isAlive) {
                readerThread.interrupt()
                return 0  // timeout — no data
            }
            exception?.let { throw it }
            result
        }
    }

    override fun close() {
        _connected = false
        try { inputStream?.close() }  catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() }       catch (_: Exception) {}
        socket = null; inputStream = null; outputStream = null
        val name = try { device.name } catch (_: SecurityException) { device.address }
        Log.i(TAG, "Disconnected from $name")
    }

    override fun purgeHwBuffers(input: Boolean, output: Boolean) {
        if (!input) return
        try {
            val stream = inputStream ?: return
            val avail  = stream.available()
            if (avail > 0) {
                val dummy = ByteArray(avail)
                stream.read(dummy, 0, avail)
                Log.d(TAG, "Purged $avail bytes from BT input buffer")
            }
        } catch (_: Exception) {}
    }
}