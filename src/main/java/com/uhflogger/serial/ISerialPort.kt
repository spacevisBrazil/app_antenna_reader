package com.uhflogger.serial

/**
 * Abstração sobre um canal de comunicação serial.
 * Implementada por UsbSerialPortWrapper (USB) e BluetoothSerialPort (BT RFCOMM).
 * Permite que UHFReaderService trabalhe com ambos os transportes sem alterar a lógica de protocolo.
 */
interface ISerialPort {
    /** Escreve bytes na porta. Retorna o número de bytes escritos. */
    fun write(data: ByteArray, timeout: Int): Int

    /** Lê bytes da porta para buf. Retorna o número de bytes lidos. */
    fun read(buf: ByteArray, timeout: Int): Int

    /** Fecha a porta e libera recursos. */
    fun close()

    /**
     * Limpa buffers de hardware.
     * USB: delega para UsbSerialPort.purgeHwBuffers().
     * Bluetooth: limpa o buffer de leitura interno (sem efeito no output).
     */
    fun purgeHwBuffers(input: Boolean, output: Boolean)

    /** True se a porta está aberta e conectada. */
    val isConnected: Boolean

    /** Nome legível para logs (ex: "USB:/dev/bus/usb/..." ou "BT:Winnix_BT") */
    val portName: String
}
