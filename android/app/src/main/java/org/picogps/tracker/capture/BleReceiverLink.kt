package org.picogps.tracker.capture

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.os.SystemClock
import java.util.UUID
import org.picogps.tracker.protocol.GpsFix
import org.picogps.tracker.protocol.GpsProtocol
import org.picogps.tracker.protocol.ReceiverMetadata

@SuppressLint("MissingPermission")
internal class BleReceiverLink(
    private val context: Context,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        fun onScan(scanning: Boolean, receivers: List<NearbyReceiver>)
        fun onSelection(receiver: NearbyReceiver?)
        fun onConnection(connection: CaptureConnection, message: String? = null)
        fun onLinkLost()
        fun onMetadata(receiver: ReceiverMetadata)
        fun onFix(receiver: ReceiverMetadata, fix: GpsFix, utcMillis: Long, elapsedMillis: Long)
        fun onMessage(message: String?)
    }

    private enum class Stage { CONNECT, DISCOVER, READ_METADATA, SUBSCRIBE, STREAM }

    private data class Candidate(val device: BluetoothDevice, val receiver: NearbyReceiver)

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val candidates = LinkedHashMap<String, Candidate>()
    private var scan: Scan? = null
    private var selected: Candidate? = null
    private var pairing = false
    private var gatt: BluetoothGatt? = null
    private var stage: Stage? = null
    private var metadata: ReceiverMetadata? = null
    private var retries = 0
    private var closed = false

    private val retry = Runnable { connect() }
    private val connectionTimeout = Runnable { retryConnection("The Pico GPS connection timed out.") }
    private val streamTimeout = Runnable { retryConnection("The Pico GPS receiver stopped sending data.") }
    private val scanTimeout = Runnable {
        stopScan()
        if (candidates.isEmpty()) {
            listener.onMessage(
                if (Build.VERSION.SDK_INT < 31) "No Pico GPS receiver found. Check power and Android Location settings."
                else "No Pico GPS receiver found. Check receiver power and range.",
            )
        }
    }
    private val pairTimeout = Runnable { pairingFailed() }

    private val changes = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (closed) return
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> bluetoothOff()
                        BluetoothAdapter.STATE_ON -> if (selected != null) connect() else {
                            listener.onConnection(CaptureConnection.IDLE)
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> bondChanged(intent)
            }
        }
    }

    fun open() {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED).apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // The Bluetooth app sends these protected broadcasts from outside our process.
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(changes, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(changes, filter)
        }
        if (!isBluetoothOn()) bluetoothOff()
    }

    fun startScan() {
        if (closed || scan != null) return
        if (!TrackerCapture.requiredBluetoothPermissions.all {
                context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
        ) {
            listener.onMessage("Grant Bluetooth permissions to find a Pico GPS receiver.")
            return
        }
        if (!isBluetoothOn()) {
            bluetoothOff()
            return
        }
        val scanner = bluetoothCall { adapter?.bluetoothLeScanner }
        if (scanner == null) {
            listener.onMessage("Bluetooth scanning is unavailable.")
            return
        }
        candidates.clear()
        val scan = Scan(scanner)
        this.scan = scan
        listener.onMessage(null)
        reportResults()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GpsProtocol.SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val started = bluetoothCall {
            scanner.startScan(listOf(filter), settings, scan)
            true
        }
        if (started == true) {
            handler.postDelayed(scanTimeout, 12_000)
        } else {
            stopScan()
            listener.onMessage("Bluetooth scanning could not start.")
        }
    }

    fun stopScan() {
        val active = scan ?: return
        scan = null
        handler.removeCallbacks(scanTimeout)
        bluetoothCall { active.scanner.stopScan(active) }
        reportResults()
    }

    fun select(address: String) {
        if (closed) return
        val candidate = candidates[address] ?: return
        stopScan()
        if (selected != null) listener.onLinkLost()
        releaseConnection()
        retries = 0
        selected = candidate
        listener.onSelection(candidate.receiver)
        listener.onMessage(null)
        connect()
    }

    fun disconnect() {
        releaseConnection()
        selected = null
        listener.onSelection(null)
        listener.onConnection(if (isBluetoothOn()) CaptureConnection.IDLE else CaptureConnection.BLUETOOTH_OFF)
    }

    fun close() {
        closed = true
        context.unregisterReceiver(changes)
        stopScan()
        releaseConnection()
    }

    private inner class Scan(val scanner: BluetoothLeScanner) : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post { if (scan === this) found(result) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.post {
                if (scan !== this) return@post
                stopScan()
                listener.onMessage("Bluetooth scan failed ($errorCode).")
            }
        }
    }

    private fun found(result: ScanResult) {
        val device = result.device
        val address = device.address
        val name = result.scanRecord?.deviceName ?: bluetoothCall { device.name } ?: "Pico GPS"
        val bonded = bluetoothCall { device.bondState } == BluetoothDevice.BOND_BONDED
        val receiver = NearbyReceiver(address, name, result.rssi, bonded)
        candidates[address] = Candidate(device, receiver)
        reportResults()
    }

    private fun reportResults() {
        listener.onScan(scan != null, candidates.values.map { it.receiver }.sortedByDescending { it.rssi })
    }

    private fun connect() {
        if (closed || gatt != null || pairing) return
        val candidate = selected ?: return
        if (!TrackerCapture.canConnect(context)) {
            listener.onConnection(CaptureConnection.IDLE, "Grant Bluetooth permissions to connect to the receiver.")
            return
        }
        if (!isBluetoothOn()) {
            bluetoothOff()
            return
        }
        val bondState = bluetoothCall { candidate.device.bondState }
        if (bondState != BluetoothDevice.BOND_BONDED) {
            // A recording may reconnect to an existing bond. New pairing requires visible user selection.
            if (candidate.receiver.bonded || retries > 0) {
                listener.onConnection(CaptureConnection.IDLE, "The Pico GPS bond was removed. Select the receiver to pair again.")
                return
            }
            pairing = true
            listener.onConnection(CaptureConnection.PAIRING, "Enter the passkey shown on the Pico USB console.")
            if (bondState == BluetoothDevice.BOND_BONDING || bluetoothCall { candidate.device.createBond() } == true) {
                handler.postDelayed(pairTimeout, 65_000)
            } else {
                pairingFailed()
            }
            return
        }
        if (!candidate.receiver.bonded) updateBond(candidate, true)
        listener.onConnection(if (retries == 0) CaptureConnection.CONNECTING else CaptureConnection.RECONNECTING)
        gatt = bluetoothCall {
            candidate.device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler)
        }
        if (gatt == null) retryConnection("Could not connect to the Pico GPS receiver.") else watchStage(Stage.CONNECT)
    }

    private fun bluetoothOff() {
        stopScan()
        if (selected != null) listener.onLinkLost()
        releaseConnection()
        listener.onConnection(CaptureConnection.BLUETOOTH_OFF)
    }

    private fun isBluetoothOn(): Boolean = bluetoothCall { adapter?.isEnabled } == true

    @Suppress("DEPRECATION")
    private fun bondChanged(intent: Intent) {
        val candidate = selected ?: return
        if (!pairing) return
        val device = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        }
        if (device?.address != candidate.device.address) return
        when (bluetoothCall { candidate.device.bondState }) {
            BluetoothDevice.BOND_BONDED -> {
                pairing = false
                handler.removeCallbacks(pairTimeout)
                updateBond(candidate, true)
                connect()
            }
            BluetoothDevice.BOND_NONE -> pairingFailed()
        }
    }

    private fun updateBond(candidate: Candidate, bonded: Boolean) {
        val updated = candidate.copy(receiver = candidate.receiver.copy(bonded = bonded))
        selected = updated
        if (candidates.containsKey(updated.receiver.address)) {
            candidates[updated.receiver.address] = updated
            reportResults()
        }
        listener.onSelection(updated.receiver)
    }

    private fun pairingFailed() {
        pairing = false
        handler.removeCallbacks(pairTimeout)
        listener.onConnection(CaptureConnection.IDLE, "Pairing failed. Open the Pico pairing window and select the receiver again.")
    }

    private fun retryConnection(message: String) {
        if (selected == null || closed) return
        listener.onLinkLost()
        releaseConnection()
        if (!isBluetoothOn()) {
            listener.onConnection(CaptureConnection.BLUETOOTH_OFF)
            return
        }
        val delay = minOf(60_000L, 1_000L shl retries.coerceAtMost(6))
        retries = (retries + 1).coerceAtMost(6)
        listener.onConnection(CaptureConnection.RECONNECTING, message)
        handler.postDelayed(retry, delay)
    }

    private fun releaseConnection() {
        handler.removeCallbacks(retry)
        handler.removeCallbacks(pairTimeout)
        handler.removeCallbacks(connectionTimeout)
        handler.removeCallbacks(streamTimeout)
        pairing = false
        stage = null
        metadata = null
        val oldGatt = gatt
        gatt = null
        if (oldGatt != null) {
            bluetoothCall { oldGatt.disconnect() }
            bluetoothCall { oldGatt.close() }
        }
    }

    private fun watchStage(newStage: Stage) {
        stage = newStage
        handler.removeCallbacks(connectionTimeout)
        if (newStage == Stage.STREAM) {
            watchStream()
        } else {
            handler.postDelayed(connectionTimeout, 15_000)
        }
    }

    private fun watchStream() {
        handler.removeCallbacks(streamTimeout)
        handler.postDelayed(streamTimeout, 10_000)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (this@BleReceiverLink.gatt !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                retryConnection("Lost the Pico GPS connection.")
            } else if (newState == BluetoothProfile.STATE_CONNECTED && stage == Stage.CONNECT) {
                watchStage(Stage.DISCOVER)
                if (bluetoothCall { gatt.discoverServices() } != true) {
                    retryConnection("Could not discover the Pico GPS service.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (this@BleReceiverLink.gatt !== gatt || stage != Stage.DISCOVER) return
            val characteristic = gatt.getService(GpsProtocol.SERVICE_UUID)?.getCharacteristic(GpsProtocol.METADATA_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                retryConnection("The receiver did not provide the Pico GPS service.")
                return
            }
            watchStage(Stage.READ_METADATA)
            if (bluetoothCall { gatt.readCharacteristic(characteristic) } != true) {
                retryConnection("Could not read Pico GPS receiver information.")
            }
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) metadataRead(gatt, characteristic.uuid, status, characteristic.value)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            metadataRead(gatt, characteristic.uuid, status, value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (this@BleReceiverLink.gatt !== gatt || stage != Stage.SUBSCRIBE || descriptor.uuid != GpsProtocol.CCCD_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                retryConnection("Could not subscribe to Pico GPS fixes. Check the receiver pairing.")
                return
            }
            watchStage(Stage.STREAM)
            listener.onConnection(CaptureConnection.STREAMING)
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) fixReceived(gatt, characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            fixReceived(gatt, characteristic.uuid, value)
        }
    }

    private fun metadataRead(gatt: BluetoothGatt, uuid: UUID, status: Int, bytes: ByteArray?) {
        if (this.gatt !== gatt || stage != Stage.READ_METADATA || uuid != GpsProtocol.METADATA_UUID) return
        if (status != BluetoothGatt.GATT_SUCCESS) {
            retryConnection("Could not read Pico GPS receiver information. Check the receiver pairing.")
            return
        }
        val receiver = bytes?.let(GpsProtocol::decodeMetadata)
        if (receiver == null) {
            releaseConnection()
            listener.onConnection(CaptureConnection.IDLE, "This receiver uses an unsupported Pico GPS protocol.")
            return
        }
        metadata = receiver
        listener.onMetadata(receiver)
        subscribe(gatt)
    }

    @Suppress("DEPRECATION")
    private fun subscribe(gatt: BluetoothGatt) {
        val fixes = gatt.getService(GpsProtocol.SERVICE_UUID)?.getCharacteristic(GpsProtocol.FIX_UUID)
        val cccd = fixes?.getDescriptor(GpsProtocol.CCCD_UUID)
        if (fixes == null || cccd == null || bluetoothCall { gatt.setCharacteristicNotification(fixes, true) } != true) {
            retryConnection("The receiver did not provide Pico GPS notifications.")
            return
        }
        watchStage(Stage.SUBSCRIBE)
        val enabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val queued = bluetoothCall {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(cccd, enabled) == BluetoothStatusCodes.SUCCESS
            } else {
                cccd.setValue(enabled) && gatt.writeDescriptor(cccd)
            }
        }
        if (queued != true) retryConnection("Could not enable Pico GPS notifications.")
    }

    private fun fixReceived(gatt: BluetoothGatt, uuid: UUID, bytes: ByteArray?) {
        if (this.gatt !== gatt || stage != Stage.STREAM || uuid != GpsProtocol.FIX_UUID) return
        val receiver = metadata ?: return
        val fix = bytes?.let(GpsProtocol::decodeFix) ?: return
        val receivedUtcMillis = System.currentTimeMillis()
        val elapsedMillis = SystemClock.elapsedRealtime()
        retries = 0
        watchStream()
        listener.onFix(receiver, fix, receivedUtcMillis, elapsedMillis)
    }

    private inline fun <T> bluetoothCall(call: () -> T): T? = try {
        call()
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}
