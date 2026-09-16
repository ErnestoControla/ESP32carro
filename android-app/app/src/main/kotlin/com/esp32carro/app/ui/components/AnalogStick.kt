package com.esp32carro.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun AnalogStick(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    centerValue: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val trackHeight = 220.dp
    val knobSize = 64.dp
    val density = LocalDensity.current
    val travelPx = remember(density) {
        with(density) { (trackHeight - knobSize).toPx() }
    }

    fun valueToOffset(v: Float): Float {
        val fraction = (v - valueRange.start) / (valueRange.endInclusive - valueRange.start)
        return (1f - fraction) * travelPx
    }

    fun offsetToValue(offset: Float): Float {
        val fraction = 1f - (offset / travelPx)
        return valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
    }

    var offsetPx by remember { mutableFloatStateOf(valueToOffset(centerValue)) }
    val scope = rememberCoroutineScope()

    fun springBackToCenter() {
        scope.launch {
            val start = offsetPx
            val end = valueToOffset(centerValue)
            animate(start, end) { current, _ ->
                offsetPx = current
                onValueChange(offsetToValue(current))
            }
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(
            text = label.uppercase(),
            color = Color(0xFFFF5722),
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Box(
            modifier = Modifier
                .width(48.dp)
                .height(trackHeight)
                .background(Color(0xFF121212), RoundedCornerShape(24.dp))
                .border(2.dp, Color(0xFF333333), RoundedCornerShape(24.dp))
                .then(
                    if (enabled) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragEnd = { springBackToCenter() },
                                onDragCancel = { springBackToCenter() }
                            ) { change, dragAmount ->
                                change.consume()
                                offsetPx = (offsetPx + dragAmount).coerceIn(0f, travelPx)
                                onValueChange(offsetToValue(offsetPx))
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(vertical = knobSize / 2),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                repeat(5) {
                    Box(Modifier.width(8.dp).height(2.dp).background(Color(0xFF444444)))
                }
            }

            Box(
                modifier = Modifier
                    .offset { IntOffset(0, offsetPx.roundToInt()) }
                    .size(knobSize)
                    .align(Alignment.TopCenter)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFFE64A19), Color(0xFFBF360C)),
                        ),
                        shape = CircleShape
                    )
                    .border(2.dp, Color(0xFF212121), CircleShape)
            )
        }
    }
}
