package com.uhflogger.serial

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Implementação de ISerialPort via Bluetooth SPP (Serial Port Profile), usando RFCOMM com UUID padrão SPP.
 *
 * @SuppressLint("MissingPermission") é seguro aqui porque:
 * - A permissão BLUETOOTH_CONNECT é verificada em UHFReaderService.startBtConnection() antes de instanciar esta classe.
 * - Todas as chamadas também estão envoltas em try/catch SecurityException como defesa em profundidade.
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
     * Conecta ao dispositivo via RFCOMM seguro.
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
        // Tentativa única via RFCOMM seguro padrão.
        // Anteriormente tentava 3 métodos sequenciais (seguro → inseguro → reflection),
        // cada connect() podendo levar ~12s para timeout quando fora de alcance — isso
        // totalizava até 36s antes do loop externo de reconexão agir.
        // Os loops externos (startBtConnection + startBtReconnectLoop) já cobrem retentativas,
        // então falhar rápido aqui é mais responsivo.
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        try {
            s.connect()
            Log.d(TAG, "Connected via secure RFCOMM")
            return s
        } catch (e: Exception) {
            try { s.close() } catch (_: Exception) {}
            val name = try { device.name } catch (_: SecurityException) { device.address }
            Log.w(TAG, "Connect failed: ${e.message}")
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
     * Leitura bloqueante — bloqueia até dados chegarem OU lança IOException ao desconectar.
     * Importante: available() polling nunca lança exceção na desconexão, o que impede o IOManager
     * de detectá-la. A leitura bloqueante lança — propagando para onRunError → reconexão.
     */
    override fun read(buf: ByteArray, timeout: Int): Int {
        val stream = inputStream ?: return 0
        return if (timeout == 0) {
            // Leitura bloqueante — usada pelo BluetoothInputOutputManager para dados contínuos.
            stream.read(buf, 0, buf.size)
        } else {
            // Leitura temporizada: spawn de thread com leitura bloqueante, interrompida após timeout.
            // Mais confiável que polling de available() em sockets BT.
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