package com.kaya.health.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.LocalTime
import java.util.UUID
import kotlin.coroutines.resume

sealed interface BandState {
    data object Idle : BandState
    data class Connecting(val name: String?) : BandState
    data class Connected(val name: String, val address: String) : BandState
    data class Failed(val reason: String) : BandState
}

/**
 * Talks to the Noise REP directly over Bluetooth LE, with no NoiseFit and no Health Connect.
 *
 * Transport (from OpenNoiseRep's analysis): every message is chunked. The sender writes a start
 * frame `00 00 00 00 <count u16 LE>`, waits for the start ack `00 00 01 01`, then writes chunks
 * `<index u16 LE, 1-based><up to 242 bytes>`. The receiver acks the end with `00 00 01 00`.
 * The band sends replies the same way on its notify characteristics, and Kaya acks them.
 * There is no pairing, key or handshake.
 */
@Suppress("DEPRECATION") // pre-Android 13 GATT APIs are still needed for older phones
@SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT / BLUETOOTH_SCAN first
class BandClient(private val context: Context, private val scope: CoroutineScope) {

    companion object {
        const val TAG = "KayaBand"
        const val NAME_PREFIX = "Noise_REP_"
        val SERVICE: UUID = UUID.fromString("16186f00-0000-1000-8000-00807f9b34fb")
        val READ: UUID = UUID.fromString("16186f01-0000-1000-8000-00807f9b34fb")
        val WRITE: UUID = UUID.fromString("16186f02-0000-1000-8000-00807f9b34fb")
        val ACTIVITY: UUID = UUID.fromString("16186f03-0000-1000-8000-00807f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val ACK_START = byteArrayOf(0, 0, 1, 1)
        private val ACK_END = byteArrayOf(0, 0, 1, 0)
    }

    private val _state = MutableStateFlow<BandState>(BandState.Idle)
    val state: StateFlow<BandState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    /** Recent protocol events, newest last, for the on-screen connection log. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _payloads = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    /** Every complete message the band sends (reassembled from chunks). */
    val payloads: SharedFlow<ByteArray> = _payloads.asSharedFlow()

    private val prefs = context.getSharedPreferences("kaya_band", Context.MODE_PRIVATE)
    private var gatt: BluetoothGatt? = null
    private val chars = HashMap<UUID, BluetoothGattCharacteristic>()
    private var chunkSize = 242

    private val opLock = Mutex()        // Android allows one GATT operation at a time
    private val transferLock = Mutex()  // one outgoing message (and its reply) at a time
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    @Volatile private var connectWait: CompletableDeferred<Boolean>? = null
    @Volatile private var discoverWait: CompletableDeferred<Int>? = null
    @Volatile private var mtuWait: CompletableDeferred<Int>? = null
    @Volatile private var ackStart: CompletableDeferred<Int>? = null

    private class Stream(val count: Int, val parts: HashMap<Int, ByteArray> = HashMap())
    private val streams = HashMap<UUID, Stream>()
    private val notifications = Channel<Pair<UUID, ByteArray>>(Channel.UNLIMITED)
    private var consumer: Job? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        val line = "${LocalTime.now().withNano(0)}  $msg"
        _log.value = (_log.value + line).takeLast(60)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWait?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                log("Disconnected (status $status)")
                connectWait?.complete(false)
                pendingOp?.complete(-1)
                ackStart?.complete(-1)
                if (_state.value is BandState.Connected || _state.value is BandState.Connecting) {
                    _state.value = BandState.Failed("Band disconnected")
                }
                cleanup()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { discoverWait?.complete(status) }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { mtuWait?.complete(mtu) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { pendingOp?.complete(status) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { pendingOp?.complete(status) }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.trySend(c.uuid to value.copyOf())
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                @Suppress("DEPRECATION")
                notifications.trySend(c.uuid to (c.value?.copyOf() ?: return))
            }
        }
    }

    // ------------------------------------------------------------------ connect

    suspend fun connect(): Boolean {
        if (_state.value is BandState.Connected) return true
        // Fresh install: no Bluetooth permission yet. Say so instead of letting Android throw.
        if (!BandViewModel.hasPermissions(context)) {
            _state.value = BandState.Failed("Kaya needs the Nearby devices (Bluetooth) permission to reach your band.")
            return false
        }
        return try { connectInner() } catch (e: SecurityException) {
            log("permission refused: ${e.message}")
            _state.value = BandState.Failed("Kaya needs the Nearby devices (Bluetooth) permission to reach your band.")
            false
        }
    }

    private suspend fun connectInner(): Boolean {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            _state.value = BandState.Failed("Bluetooth is off. Turn it on and try again.")
            return false
        }
        _state.value = BandState.Connecting(null)
        val mgr = context.getSystemService(BluetoothManager::class.java)
        val saved = prefs.getString("address", null)
        // A band that's connected to the phone (e.g. by NoiseFit) stops advertising, so look at
        // connected devices first and share that link. Then paired devices, the last-known address, a scan.
        val device = mgr.getConnectedDevices(BluetoothProfile.GATT).firstOrNull { it.name?.startsWith(NAME_PREFIX) == true }
            ?.also { log("Band already connected to the phone: ${it.name}, sharing the link") }
            ?: adapter.bondedDevices.firstOrNull { it.name?.startsWith(NAME_PREFIX) == true }?.also { log("Using paired band ${it.name}") }
            ?: saved?.let { adapter.getRemoteDevice(it) }?.also { log("Using last-known band address") }
            ?: scanForBand(adapter)
        if (device == null) {
            log("No band found")
            _state.value = BandState.Failed("No Noise REP band found nearby. Wake the band (or put it on the charger) and try again.")
            return false
        }
        val name = device.name ?: "Noise REP (${device.address.takeLast(5)})"
        prefs.edit().putString("address", device.address).apply()
        _state.value = BandState.Connecting(name)

        // Try up to 3 times: the band briefly refuses while another app (NoiseFit) is mid-sync.
        var ok = false
        for (attempt in 1..3) {
            val wait = CompletableDeferred<Boolean>().also { connectWait = it }
            gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (withTimeoutOrNull(15_000) { wait.await() } == true) { ok = true; break }
            log("Connect attempt $attempt failed, retrying")
            gatt?.let { runCatching { it.disconnect(); it.close() } }
            gatt = null
            _state.value = BandState.Connecting(name)
            kotlinx.coroutines.delay(1_500L * attempt)
        }
        if (!ok) return fail("Couldn't connect to $name")
        log("Connected to $name")
        val g = gatt ?: return fail("Connection lost")

        val mtu = CompletableDeferred<Int>().also { mtuWait = it }
        g.requestMtu(247)
        val negotiated = withTimeoutOrNull(5_000) { mtu.await() } ?: 23
        chunkSize = (negotiated - 3 - 2).coerceIn(18, 242)
        log("MTU $negotiated → chunks of $chunkSize bytes")

        val disc = CompletableDeferred<Int>().also { discoverWait = it }
        g.discoverServices()
        if (withTimeoutOrNull(10_000) { disc.await() } != BluetoothGatt.GATT_SUCCESS) return fail("Service discovery failed")
        val svc = g.getService(SERVICE) ?: return fail("This band doesn't have the Noise REP data service")
        chars.clear()
        svc.characteristics.forEach { chars[it.uuid] = it }
        log("Service found: " + svc.characteristics.joinToString { "${it.uuid.toString().substring(4, 8)}(props ${it.properties})" })

        consumer?.cancel()
        consumer = scope.launch(Dispatchers.Default) { for ((u, v) in notifications) handleNotify(u, v) }
        for (u in listOf(READ, WRITE, ACTIVITY)) enableNotify(u)
        _state.value = BandState.Connected(name, device.address)
        return true
    }

    private suspend fun scanForBand(adapter: android.bluetooth.BluetoothAdapter): BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        log("Scanning for $NAME_PREFIX…")
        return withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine { cont ->
                val cb = object : ScanCallback() {
                    override fun onScanResult(type: Int, result: ScanResult) {
                        val n = result.scanRecord?.deviceName ?: result.device.name
                        if (n?.startsWith(NAME_PREFIX) == true && cont.isActive) {
                            scanner.stopScan(this)
                            cont.resume(result.device)
                        }
                    }
                }
                scanner.startScan(cb)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(cb) } }
            }
        }
    }

    private fun fail(reason: String): Boolean {
        log(reason)
        _state.value = BandState.Failed(reason)
        disconnect()
        return false
    }

    fun disconnect() {
        consumer?.cancel()
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        cleanup()
        if (_state.value is BandState.Connected || _state.value is BandState.Connecting) _state.value = BandState.Idle
    }

    private fun cleanup() {
        gatt = null
        chars.clear()
        streams.clear()
    }

    // ------------------------------------------------------------------ GATT operations

    private suspend fun enableNotify(uuid: UUID) = opLock.withLock {
        val g = gatt ?: return@withLock
        val c = chars[uuid] ?: return@withLock
        val notify = c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val indicate = c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!notify && !indicate) return@withLock
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return@withLock
        val value = if (notify) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val done = CompletableDeferred<Int>().also { pendingOp = it }
        val started = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        else {
            d.value = value
            g.writeDescriptor(d)
        }
        val status = if (started) withTimeoutOrNull(3_000) { done.await() } else null
        log("Notifications on ${uuid.toString().substring(4, 8)}: ${if (status == 0) "on" else "failed ($status)"}")
    }

    private suspend fun write(uuid: UUID, value: ByteArray) = opLock.withLock {
        val g = gatt ?: throw IOException("not connected")
        val c = chars[uuid] ?: throw IOException("no characteristic $uuid")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val done = CompletableDeferred<Int>().also { pendingOp = it }
        val started = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
        else {
            c.writeType = type
            c.value = value
            g.writeCharacteristic(c)
        }
        if (!started) throw IOException("write rejected by Android")
        val status = withTimeoutOrNull(3_000) { done.await() } ?: throw IOException("write timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("write failed ($status)")
    }

    // ------------------------------------------------------------------ transport

    private suspend fun handleNotify(src: UUID, data: ByteArray) {
        if (data.size < 3) return
        val idx = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8)
        if (idx == 0) {
            when (data[2].toInt()) {
                0 -> { // band starts a transfer to us
                    val n = if (data.size >= 6) (data[4].toInt() and 0xFF) or ((data[5].toInt() and 0xFF) shl 8) else 0
                    streams[src] = Stream(n)
                    runCatching { write(src, ACK_START) }.onFailure { log("ack failed: ${it.message}") }
                }
                1 -> when (data.getOrNull(3)?.toInt()) { // ack for our transfer
                    1 -> ackStart?.complete(1)
                    2 -> ackStart?.complete(2)
                    else -> Unit // end ack
                }
            }
            return
        }
        val s = streams[src] ?: return
        s.parts[idx] = data.copyOfRange(2, data.size)
        if (s.count > 0 && s.parts.size >= s.count) {
            streams.remove(src)
            val full = s.parts.toSortedMap().values.fold(ByteArray(0)) { acc, b -> acc + b }
            runCatching { write(src, ACK_END) }
            val t = Decode.type(full)
            val known = t == Commands.TYPE_SERIES.toLong() || t == Commands.TYPE_CONFIRM_DO_NOT_SEND.toLong() || t == Commands.TYPE_DEVICE_INFO.toLong()
            log(
                "← type $t${Decode.subtype(full)?.let { "/$it" } ?: ""}${Decode.status(full)?.let { " status $it" } ?: ""} (${full.size} bytes)" +
                    if (!known) " on ${src.toString().substring(4, 8)}: ${full.copyOfRange(0, minOf(full.size, 48)).hex()}" else ""
            )
            _payloads.emit(full)
        }
    }

    private suspend fun sendRaw(payload: ByteArray) {
        val n = maxOf(1, (payload.size + chunkSize - 1) / chunkSize)
        val ack = CompletableDeferred<Int>().also { ackStart = it }
        write(WRITE, byteArrayOf(0, 0, 0, 0, (n and 0xFF).toByte(), (n shr 8).toByte()))
        // The band acks in well under 0.1 s; a longer wait means another app (NoiseFit) has it busy.
        when (withTimeoutOrNull(2_000) { ack.await() }) {
            1 -> Unit
            2 -> throw IOException("band refused the message")
            else -> throw IOException("band didn't acknowledge")
        }
        for (i in 0 until n) {
            val part = payload.copyOfRange(i * chunkSize, minOf(payload.size, (i + 1) * chunkSize))
            write(WRITE, byteArrayOf(((i + 1) and 0xFF).toByte(), ((i + 1) shr 8).toByte()) + part)
        }
    }

    /**
     * Sends [payload] and returns the band's reply of [expectType] (or a status for it), or null.
     * The band handles one transfer at a time, so if another app (NoiseFit) is mid-sync it refuses
     * ours. Retry a few times with a short pause.
     */
    suspend fun ask(
        payload: ByteArray, expectType: Int, timeoutMs: Long = 4_000, attempts: Int = 4,
        /** Extra check so a reply meant for another app (NoiseFit asks the same types) isn't taken. */
        matches: (ByteArray) -> Boolean = { true },
    ): ByteArray? {
        // Backing off: the band ignored several messages in a row (another app is mid-transfer). Don't hammer it.
        if (System.currentTimeMillis() < busyUntil) return null
        repeat(attempts) { i ->
            askOnce(payload, expectType, timeoutMs, matches)?.let { misses = 0; return it }
            if (++misses >= 3) {
                busyUntil = System.currentTimeMillis() + 45_000
                misses = 0
                log("band not answering, backing off for 45 s")
                return null
            }
            if (i < attempts - 1) {
                log("retrying in ${(1 + i) * 0.5}s (band busy?)")
                kotlinx.coroutines.delay(500L * (1 + i))
            }
        }
        return null
    }

    private suspend fun askOnce(payload: ByteArray, expectType: Int, timeoutMs: Long, matches: (ByteArray) -> Boolean): ByteArray? = transferLock.withLock {
        coroutineScope {
            val reply = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(timeoutMs) { _payloads.first { Decode.type(it) == expectType.toLong() && matches(it) } }
            }
            try {
                log("→ type ${Decode.type(payload)}${Decode.subtype(payload)?.let { "/$it" } ?: ""}: ${payload.hex()}")
                sendRaw(payload)
            } catch (e: Exception) {
                log("send failed: ${e.message}")
                reply.cancel()
                return@coroutineScope null
            }
            reply.await().also { if (it == null) log("no reply to type $expectType") }
        }
    }

    @Volatile private var misses = 0
    @Volatile private var busyUntil = 0L
    /** True while Kaya is giving the band a rest after it stopped answering. */
    val backingOff: Boolean get() = System.currentTimeMillis() < busyUntil

    /** The user asked to try again: drop the busy pause. */
    fun clearBackoff() { busyUntil = 0L; misses = 0 }

    /** Sends without waiting for a specific reply (e.g. start/stop streaming). */
    suspend fun tell(payload: ByteArray): Boolean = transferLock.withLock {
        try {
            log("→ type ${Decode.type(payload)}${Decode.subtype(payload)?.let { "/$it" } ?: ""}: ${payload.hex()}")
            sendRaw(payload)
            true
        } catch (e: Exception) {
            log("send failed: ${e.message}")
            false
        }
    }
}
