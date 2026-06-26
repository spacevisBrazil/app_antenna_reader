package com.uhflogger.serial

/**
 * Abstraction over a serial communication channel.
 * Implemented by UsbSerialPortWrapper (USB) and BluetoothSerialPort (BT RFCOMM).
 * Allows UHFReaderService to work with both transports without changes to protocol logic.
 */
interface ISerialPort {
    /** Write bytes to the port. Returns number of bytes written. */
    fun write(data: ByteArray, timeout: Int): Int

    /** Read bytes from the port into buf. Returns number of bytes read. */
    fun read(buf: ByteArray, timeout: Int): Int

    /** Close the port and release resources. */
    fun close()

    /**
     * Purge hardware buffers.
     * USB: delegates to UsbSerialPort.purgeHwBuffers().
     * Bluetooth: clears the internal read buffer (no-op for output).
     */
    fun purgeHwBuffers(input: Boolean, output: Boolean)

    /** True if the port is currently open and connected. */
    val isConnected: Boolean

    /** Human-readable name for logging (e.g. "USB:/dev/bus/usb/..." or "BT:Winnix_BT") */
    val portName: String
}
