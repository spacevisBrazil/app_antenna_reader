package com.uhflogger.serial

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * Wraps UsbSerialPort to implement ISerialPort.
 * Keeps the existing USB behavior exactly as before.
 */
class UsbSerialPortWrapper(
    private val port      : UsbSerialPort,
    private val connection: UsbDeviceConnection
) : ISerialPort {

    override val isConnected: Boolean get() = true  // checked externally
    override val portName   : String  get() = "USB:${port.device.deviceName}"

    override fun write(data: ByteArray, timeout: Int): Int {
        port.write(data, timeout)
        return data.size
    }

    override fun read(buf: ByteArray, timeout: Int): Int =
        port.read(buf, timeout)

    override fun close() {
        try { port.close() }       catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    override fun purgeHwBuffers(input: Boolean, output: Boolean) {
        try { port.purgeHwBuffers(input, output) } catch (_: Exception) {}
    }

    /** Expose raw UsbSerialPort for SerialInputOutputManager (USB only) */
    fun rawPort(): UsbSerialPort = port
}
