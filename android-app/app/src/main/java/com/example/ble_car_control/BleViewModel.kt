package com.example.ble_car_control

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleViewModel(application: Application) : AndroidViewModel(application) {

    var currentX = 0f
    var currentY = 0f

    private val bleManager = BleManager(application.applicationContext)

    // Store latest found device here
    private var targetDevice: BleDevice? = null

    private val _status = MutableStateFlow("Disconnected")
    val status = _status.asStateFlow()

    private val _temperature = MutableStateFlow("0.0")
    val temperature = _temperature.asStateFlow()

    private val _humidity = MutableStateFlow("0.0")
    val humidity = _humidity.asStateFlow()

    init {
        // Wire up BleManager callbacks
        bleManager.onStatus = { statusMsg ->
            _status.value = statusMsg
        }

        bleManager.onDeviceFound = { device ->
            if (device.name.contains("Pico", ignoreCase = true)) {
                Log.d("BLE", "Found device: ${device.name} [${device.address}]")

                targetDevice = device
                bleManager.stopScan()
                _status.value = "Found ${device.name}! Press Connect."

            }
        }

        bleManager.onTelemetry = { bytes ->
            // Convert the raw bytes from the ESP32 back into a String
            val payload = String(bytes)
            Log.d("BLE", "Received: $payload")

            // Split "22.5,45.0" into two parts
            val parts = payload.split(",")
            if (parts.size == 2) {
                _temperature.value = parts[0]
                _humidity.value = parts[1]
            }
        }

        viewModelScope.launch {
            while (true) {
                if (_status.value == "Ready") { // Check if connected and GATT ready
                    bleManager.writeJoystick(currentX, currentY)
                }
                delay(40)
            }
        }
    }

    fun sendJoystick(x: Float, y: Float) {
        currentX = x
        currentY = y
    }

    fun scan() {
        bleManager.startScan()
    }

    fun connect() {
        targetDevice?.let {
            bleManager.connect(it.device)
        } ?: run {
            _status.value = "No device found yet. Scan first."
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }
}