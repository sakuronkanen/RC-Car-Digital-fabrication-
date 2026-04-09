package com.example.ble_car_control

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID

@SuppressLint("MissingPermission") // Permissions are checked manually via hasConnectPermission/hasScanPermission
class BleManager(private val context: Context) {

    // ---- Callbacks ----
    var onStatus: ((String) -> Unit)? = null
    var onDeviceFound: ((BleDevice) -> Unit)? = null
    var onTelemetry: ((ByteArray) -> Unit)? = null
    var onError: ((String, Throwable?) -> Unit)? = null

    // Filter by name for ESP32 or Pico W (Set this to "ESP32", "Pico", or leave null to find all)
    var scanNameFilter: String? = null

    // ---- Nordic UART Service (NUS) UUIDs ----
    private val nusServiceUuid: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val nusRxUuid: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E") // phone -> device (write)
    private val nusTxUuid: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E") // device -> phone (notify)
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val bluetoothAdapter: BluetoothAdapter by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter.bluetoothLeScanner

    private var scanCallback: ScanCallback? = null
    private val seenAddresses = HashSet<String>()

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null

    // =========================================================
    // SCAN
    // =========================================================
    fun startScan() {
        if (!hasScanPermission()) {
            onStatus?.invoke("Missing scan permission")
            return
        }

        val s = scanner
        if (s == null) {
            onStatus?.invoke("Bluetooth scanner unavailable (Bluetooth off?)")
            return
        }

        seenAddresses.clear()
        onStatus?.invoke("Scanning...")

        // Remove strict UUID filter to ensure ESP32 is found even if it doesn't advertise the full UUID
        val filters = emptyList<ScanFilter>()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handleScanResult(result)
            }
            override fun onScanFailed(errorCode: Int) {
                onError?.invoke("Scan failed: $errorCode", null)
                onStatus?.invoke("Scan failed")
            }
        }

        try {
            s.startScan(filters, settings, scanCallback)
        } catch (se: SecurityException) {
            onError?.invoke("SecurityException in startScan()", se)
        }
    }

    fun stopScan() {
        val s = scanner ?: return
        val cb = scanCallback ?: return

        if (!hasScanPermission()) {
            scanCallback = null
            return
        }

        try {
            s.stopScan(cb)
        } catch (se: SecurityException) {
            onError?.invoke("SecurityException in stopScan()", se)
        } finally {
            scanCallback = null
            onStatus?.invoke("Scan stopped")
        }
    }

    private fun handleScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address ?: return
        val name = device.name ?: result.scanRecord?.deviceName

        // Debug: see what Android thinks the name is
        Log.d("BLE", "Scan result: name=$name addr=$address rssi=${result.rssi}")

        val matchesName =
            scanNameFilter == null || (name != null && name.contains(scanNameFilter!!, ignoreCase = true))

        val advertisedUuids = result.scanRecord?.serviceUuids
        val matchesNus =
            advertisedUuids?.any { it.uuid.toString().equals(nusServiceUuid.toString(), ignoreCase = true) } == true

        // Accept either: name matches OR NUS UUID advertised
        if (!matchesName && !matchesNus) return

        if (seenAddresses.add(address)) {
            onDeviceFound?.invoke(
                BleDevice(
                    name = name ?: "Unknown Device",
                    address = address,
                    rssi = result.rssi,
                    device = device
                )
            )
        }
    }

    // =========================================================
    // CONNECT / DISCONNECT
    // =========================================================
    fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            onStatus?.invoke("Missing connect permission")
            return
        }

        stopScan()
        disconnect()

        onStatus?.invoke("Connecting to ${device.name ?: device.address}...")

        // Android 7 (Honor 8) needs a delay between stopping scan and connecting to prevent Status 133
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                // For Android 7 (API 24), use the 3-argument version.
                gatt = device.connectGatt(context, false, gattCallback)
            } catch (se: SecurityException) {
                onError?.invoke("SecurityException in connect()", se)
                onStatus?.invoke("Connect permission denied")
            }
        }, 500) // 500ms delay
    }

    // FIX FOR OLD ANDROID: Force clear the Bluetooth cache
    private fun clearGattCache(g: BluetoothGatt) {
        try {
            val refreshMethod = g.javaClass.getMethod("refresh")
            val success = refreshMethod.invoke(g) as Boolean
            Log.d("BLE", "GATT cache refresh result: $success")
        } catch (e: Exception) {
            Log.e("BLE", "Failed to clear GATT cache", e)
        }
    }

    fun disconnect() {
        if (!hasConnectPermission()) return
        try { gatt?.disconnect() } catch (_: Throwable) {}
        try { gatt?.close() } catch (_: Throwable) {}

        gatt = null
        rxChar = null
        txChar = null
        onStatus?.invoke("Disconnected")
    }

    // =========================================================
    // WRITE (joystick)
    // =========================================================
    fun writeJoystick(x: Float, y: Float) {
        val g = gatt ?: return
        val ch = rxChar ?: return

        if (!hasConnectPermission()) return

        val xb = x.toInt().coerceIn(-100, 100).toByte()
        val yb = y.toInt().coerceIn(-100, 100).toByte()
        val payload = byteArrayOf(xb, yb)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(ch, payload, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                ch.value = payload
                g.writeCharacteristic(ch)
            }
        } catch (se: SecurityException) {
            onError?.invoke("SecurityException in writeJoystick()", se)
        }
    }

    // =========================================================
    // GATT CALLBACKS
    // =========================================================
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS || status == 0) {
                    onStatus?.invoke("Connected! Refreshing cache...")

                    // Clear cache for Honor 8
                    try {
                        val refreshMethod = g.javaClass.getMethod("refresh")
                        refreshMethod.invoke(g)
                    } catch (e: Exception) { }

                    // Delay discovering services
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        onStatus?.invoke("Discovering services...")
                        g.discoverServices()
                    }, 1500)
                } else {
                    onStatus?.invoke("Connect failed with status: $status")
                    cleanupGatt()
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                onStatus?.invoke("Disconnected (status: $status)")
                cleanupGatt()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onStatus?.invoke("Service discovery failed: $status")
                return
            }

            // Print out all discovered services to the Logcat so we can see what the ESP32 has
            Log.d("BLE", "Discovered Services:")
            g.services.forEach {
                Log.d("BLE", "Service: ${it.uuid}")
                it.characteristics.forEach { char ->
                    Log.d("BLE", "  - Char: ${char.uuid}")
                }
            }

            // Try to find the service by checking strings, ignoring case
            val service = g.services.find {
                it.uuid.toString().equals(nusServiceUuid.toString(), ignoreCase = true)
            }

            if (service == null) {
                onStatus?.invoke("NUS service not found on device")
                return
            }

            // Find characteristics ignoring case
            rxChar = service.characteristics.find {
                it.uuid.toString().equals(nusRxUuid.toString(), ignoreCase = true)
            }
            txChar = service.characteristics.find {
                it.uuid.toString().equals(nusTxUuid.toString(), ignoreCase = true)
            }

            if (rxChar == null || txChar == null) {
                onStatus?.invoke("NUS characteristics missing")
                return
            }

            onStatus?.invoke("Found NUS. Enabling notifications...")
            enableTxNotifications(g)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == nusTxUuid) {
                onTelemetry?.invoke(characteristic.value ?: byteArrayOf())
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == nusTxUuid) {
                onTelemetry?.invoke(value)
            }
        }
    }

    private fun enableTxNotifications(g: BluetoothGatt) {
        val tx = txChar ?: return

        try {
            val ok = g.setCharacteristicNotification(tx, true)
            if (!ok) {
                onStatus?.invoke("Failed to enable notifications")
                return
            }

            val cccd = tx.getDescriptor(cccdUuid) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
            onStatus?.invoke("Ready")
        } catch (se: SecurityException) {
            onError?.invoke("SecurityException enabling notifications", se)
        }
    }

    private fun cleanupGatt() {
        if (hasConnectPermission()) {
            try { gatt?.close() } catch (_: Throwable) {}
        }
        gatt = null
        rxChar = null
        txChar = null
    }

    // =========================================================
    // PERMISSIONS
    // =========================================================

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // For Honor 8, it just needs ACCESS_FINE_LOCATION
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
}

data class BleDevice(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice
)