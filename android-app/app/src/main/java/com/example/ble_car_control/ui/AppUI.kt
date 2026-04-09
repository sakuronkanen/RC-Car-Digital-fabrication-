package com.example.ble_car_control.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ble_car_control.BleViewModel

@Composable
fun AppUI(viewModel: BleViewModel) {

    val status by viewModel.status.collectAsState()
    val temp by viewModel.temperature.collectAsState()
    val hum by viewModel.humidity.collectAsState()

    MaterialTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {

            // ✅ Joystick vasemmalla keskellä
            Joystick(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp),
                onMove = { x, y ->
                    viewModel.sendJoystick(x, y)
                }
            )

            // ✅ UI oikealle ylös
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .widthIn(min = 220.dp)
                    .padding(end = 8.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("BLE Car Controller", style = MaterialTheme.typography.titleMedium)

                Text("Status: $status")

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.scan() }) { Text("Scan") }
                    Button(onClick = { viewModel.connect() }) { Text("Connect") }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text("Temperature: $temp °C", style = MaterialTheme.typography.titleLarge)
                    Text("Humidity: $hum %", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
