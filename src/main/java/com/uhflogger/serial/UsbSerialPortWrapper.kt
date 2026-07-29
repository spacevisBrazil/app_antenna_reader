package com.uhflogger.serial

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort

/**
 * Adapta UsbSerialPort para a interface ISerialPort, mantendo o comportamento USB existente.
 */
class UsbSerialPortWrapper(
    private val port      : UsbSerialPort,
    private val connection: UsbDeviceConnection
) : ISerialPort {

    override val isConnected: Boolean get() = true
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

    /** Expõe o UsbSerialPort bruto para uso pelo SerialInputOutputManager (USB) */
    fun rawPort(): UsbSerialPort = port
}
