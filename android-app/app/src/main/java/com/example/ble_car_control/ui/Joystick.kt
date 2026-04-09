package com.example.ble_car_control.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun Joystick(
    modifier: Modifier = Modifier,
    size: Dp = 140.dp,          // joystick area diameter
    knobSize: Dp = 50.dp,       // knob diameter
    onMove: (Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val radiusPx = with(density) { (size / 2).toPx() }
    val knobRadiusPx = with(density) { (knobSize / 2).toPx() }

    var knobOffset by remember { mutableStateOf(Offset.Zero) } // offset from center (px)
    var active by remember { mutableStateOf(false) }

    Canvas(
        modifier = modifier
            .size(size)
            .pointerInput(radiusPx) {
                detectDragGestures(
                    onDragStart = { downPos ->
                        val center = Offset(radiusPx, radiusPx)
                        val rel = downPos - center

                        // Activate ONLY if initial press is inside circle
                        if (rel.distance() <= radiusPx) {
                            active = true
                            knobOffset = clampToCircle(rel, radiusPx)
                            onMoveNormalized(knobOffset, radiusPx, onMove)
                        } else {
                            // Ignore if initial press is outside
                            active = false
                        }
                    },
                    onDrag = { change, _ ->
                        if (!active) return@detectDragGestures

                        val center = Offset(radiusPx, radiusPx)
                        val rel = change.position - center

                        // Follow finger inside; clamp to radius outside
                        knobOffset = clampToCircle(rel, radiusPx)
                        onMoveNormalized(knobOffset, radiusPx, onMove)
                    },
                    onDragEnd = {
                        active = false
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    },
                    onDragCancel = {
                        active = false
                        knobOffset = Offset.Zero
                        onMove(0f, 0f)
                    }
                )
            }
    ) {
        val center = this.center

        // Base circle
        drawCircle(
            color = Color(0x332196F3),
            radius = radiusPx,
            center = center
        )

        // Knob circle
        drawCircle(
            color = if (active) Color(0xFF1976D2) else Color(0xFF90CAF9),
            radius = knobRadiusPx,
            center = center + knobOffset
        )
    }
}

// --- helpers ---

private fun Offset.distance(): Float = sqrt(x * x + y * y)

private fun clampToCircle(pos: Offset, radius: Float): Offset {
    val d = pos.distance()
    if (d <= radius || d == 0f) return pos

    val angle = atan2(pos.y, pos.x)
    return Offset(
        x = cos(angle) * radius,
        y = sin(angle) * radius
    )
}

private fun onMoveNormalized(pos: Offset, radius: Float, onMove: (Float, Float) -> Unit) {
    val x = (pos.x / radius) * 100f
    val y = (pos.y / radius) * 100f * -1
    onMove(x.coerceIn(-100f, 100f), y.coerceIn(-100f, 100f))
}