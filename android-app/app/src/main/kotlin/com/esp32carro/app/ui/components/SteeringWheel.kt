package com.esp32carro.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun SteeringWheel(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    centerValue: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val size = 180.dp
    val density = LocalDensity.current
    val maxDragPx = with(density) { (size / 2).toPx() }
    
    // Internal rotation state for visual feedback
    var rotationAngle by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()

    fun updateValue(dragAmountPx: Float) {
        // Mapeamos el arrastre a un angulo de rotacion (max 90 grados a cada lado)
        rotationAngle = (rotationAngle + dragAmountPx / 2f).coerceIn(-90f, 90f)
        
        // Mapeamos el angulo (-90..90) al rango del servo (0..180)
        val fraction = (rotationAngle + 90f) / 180f
        val newValue = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
        onValueChange(newValue)
    }

    fun springBackToCenter() {
        scope.launch {
            animate(rotationAngle, 0f) { current, _ ->
                rotationAngle = current
                val fraction = (current + 90f) / 180f
                onValueChange(valueRange.start + fraction * (valueRange.endInclusive - valueRange.start))
            }
        }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Text(
            "DIRECCION",
            color = Color(0xFF888888),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .size(size)
                .background(Color(0xFF1A1A1A), CircleShape)
                .pointerInput(enabled) {
                    if (enabled) {
                        detectHorizontalDragGestures(
                            onDragEnd = { springBackToCenter() },
                            onDragCancel = { springBackToCenter() }
                        ) { change, dragAmount ->
                            change.consume()
                            updateValue(dragAmount)
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            // Steering Wheel Drawing
            Canvas(
                modifier = Modifier
                    .size(size * 0.9f)
                    .rotate(rotationAngle)
            ) {
                val center = Offset(this.size.width / 2, this.size.height / 2)
                val radius = this.size.width / 2
                
                // Outer ring
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0xFF444444), Color(0xFF222222)),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    style = Stroke(width = 16.dp.toPx())
                )
                
                // Top indicator
                drawPath(
                    path = Path().apply {
                        moveTo(center.x - 10.dp.toPx(), center.y - radius)
                        lineTo(center.x + 10.dp.toPx(), center.y - radius)
                        lineTo(center.x, center.y - radius + 20.dp.toPx())
                        close()
                    },
                    color = Color(0xFFFF5722)
                )

                // Spokes
                val spokeWidth = 12.dp.toPx()
                drawLine(
                    color = Color(0xFF333333),
                    start = Offset(center.x - radius, center.y),
                    end = Offset(center.x + radius, center.y),
                    strokeWidth = spokeWidth
                )
                drawLine(
                    color = Color(0xFF333333),
                    start = center,
                    end = Offset(center.x, center.y + radius),
                    strokeWidth = spokeWidth
                )
                
                // Center hub
                drawCircle(color = Color(0xFF2A2A2A), radius = 24.dp.toPx(), center = center)
            }
        }
        
        Text(
            text = "${value.toInt()}°",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
