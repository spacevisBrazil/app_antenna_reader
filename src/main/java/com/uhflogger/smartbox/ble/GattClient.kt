package com.uhflogger.smartbox.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BluetoothGatt nativo envolvido em coroutines: uma operação GATT por vez
 * (o Android perde operação se duas forem disparadas juntas), cada uma com
 * timeout e com o status do callback na [GattException].
 *
 * Quem usa garante as permissões (BLUETOOTH_CONNECT no Android 12+) antes de
 * chamar; por isso o MissingPermission fica suprimido aqui.
 *
 * Os callbacks chegam na thread do binder; o estado compartilhado é só o
 * `pending` (um CompletableDeferred, thread-safe) e os listeners @Volatile.
 */
@SuppressLint("MissingPermission")
class GattClient(private val context: Context, private val device: BluetoothDevice) {

    @Volatile private var gatt: BluetoothGatt? = null
    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Any?>? = null
    @Volatile private var connectWaiter: CompletableDeferred<Unit>? = null
    @Volatile private var closed = false

    /** Queda depois de conectado (status GATT do callback). */
    @Volatile var onDisconnected: ((Int) -> Unit)? = null
    /** Notify do TX (já em bytes). */
    @Volatile var onNotify: ((ByteArray) -> Unit)? = null

    var mtu: Int = SmartboxProtocol.DEFAULT_MTU
        private set

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWaiter?.complete(Unit)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                val err = GattException("disconnected", status, "Conexão GATT caiu (status $status)")
                connectWaiter?.completeExceptionally(GattException("connect", status, "connect falhou (status $status)"))
                pending?.completeExceptionally(err)
                if (!closed) onDisconnected?.invoke(status)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) =
            finish(status, "mtu", mtu)

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) =
            finish(status, "discover", Unit)

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) =
            finish(status, "write", Unit)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            finish(status, "notify", Unit)

        // API 33+: o valor vem no parâmetro.
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) =
            finish(status, "notify", value)

        @Deprecated("API < 33")
        override fun onDescriptorRead(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            finish(status, "notify", d.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == SmartboxProtocol.TX_UUID) onNotify?.invoke(value)
        }

        @Deprecated("API < 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            @Suppress("DEPRECATION")
            val value = c.value ?: return
            if (c.uuid == SmartboxProtocol.TX_UUID) onNotify?.invoke(value.copyOf())
        }

        override fun onReadRemoteRssi(g: BluetoothGatt, rssi: Int, status: Int) =
            finish(status, "rssi", rssi)
    }

    private fun finish(status: Int, step: String, value: Any?) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value)
        else p.completeExceptionally(GattException(step, status, "$step falhou (status $status)"))
    }

    /** Conecta (sem autoConnect, transporte LE). Lança [GattException] em falha ou timeout. */
    suspend fun connect(timeoutMs: Long) {
        val waiter = CompletableDeferred<Unit>()
        connectWaiter = waiter
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GattException("connect", null, "connectGatt devolveu null")
        gatt = g
        val ok = withTimeoutOrNull(timeoutMs) { waiter.await() }
        connectWaiter = null
        if (ok == null) throw GattException("connect", null, "connect sem resposta em ${timeoutMs}ms")
    }

    /** Uma operação GATT por vez: dispara com [start] e espera o callback. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun <T> op(step: String, timeoutMs: Long, start: (BluetoothGatt) -> Boolean): T =
        opMutex.withLock {
            val g = gatt ?: throw GattException(step, null, "$step sem conexão")
            val p = CompletableDeferred<Any?>()
            pending = p
            try {
                if (!start(g)) throw GattException(step, null, "$step não iniciou")
                // `true` separa "terminou" de "estourou o prazo" (null).
                withTimeoutOrNull(timeoutMs) { p.await(); true }
                    ?: throw GattException(step, null, "$step sem resposta em ${timeoutMs}ms")
                p.await() as T
            } finally {
                pending = null
            }
        }

    suspend fun requestMtu(wanted: Int, timeoutMs: Long): Int {
        mtu = op<Int>("mtu", timeoutMs) { it.requestMtu(wanted) }
        return mtu
    }

    suspend fun discoverServices(timeoutMs: Long) {
        op<Any?>("discover", timeoutMs) { it.discoverServices() }
    }

    fun requestHighPriority(): Boolean =
        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) ?: false

    fun characteristic(service: UUID, uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.getService(service)?.getCharacteristic(uuid)

    /** Liga o notify: setCharacteristicNotification + escreve 01 00 no CCCD. */
    suspend fun enableNotify(c: BluetoothGattCharacteristic, timeoutMs: Long) {
        val g = gatt ?: throw GattException("notify", null, "notify sem conexão")
        if (!g.setCharacteristicNotification(c, true)) throw GattException("notify", null, "setCharacteristicNotification falhou")
        val cccd = c.getDescriptor(SmartboxProtocol.CCCD_UUID) ?: throw GattException("notify", null, "TX sem CCCD")
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op<Any?>("notify", timeoutMs) { gg ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gg.writeDescriptor(cccd, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gg.writeDescriptor(cccd)
            }
        }
    }

    /** Lê o CCCD (nem toda stack deixa; quem chama trata a falha como "segue"). */
    suspend fun readCccd(c: BluetoothGattCharacteristic, timeoutMs: Long): ByteArray {
        val cccd = c.getDescriptor(SmartboxProtocol.CCCD_UUID) ?: return ByteArray(0)
        return op("notify", timeoutMs) { it.readDescriptor(cccd) }
    }

    /** Write COM resposta (controle de fluxo do protocolo). */
    suspend fun write(c: BluetoothGattCharacteristic, value: ByteArray, timeoutMs: Long) {
        op<Any?>("write", timeoutMs) { g ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    suspend fun readRssi(timeoutMs: Long): Int? = try {
        op<Int>("rssi", timeoutMs) { it.readRemoteRssi() }
    } catch (_: Exception) {
        null
    }

    /** Desconecta e solta o GATT. Idempotente; nunca lança. */
    fun close() {
        if (closed) return
        closed = true
        val g = gatt ?: return
        gatt = null
        pending?.completeExceptionally(GattException("closed", null, "conexão fechada"))
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }
}
