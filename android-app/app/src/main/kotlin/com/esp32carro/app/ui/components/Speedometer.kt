package com.esp32carro.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SpeedometerGauge(
    speedPercent: Float,
    isReverse: Boolean,
    modifier: Modifier = Modifier
) {
    val animatedSpeed by animateFloatAsState(targetValue = speedPercent, label = "speed")

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(100.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                val radius = size.width / 2
                
                drawArc(
                    color = Color(0xFF333333),
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )

                drawArc(
                    color = Color(0xFFFF5722),
                    startAngle = 150f,
                    sweepAngle = (animatedSpeed / 100f) * 240f,
                    useCenter = false,
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )

                val angle = 150f + (animatedSpeed / 100f) * 240f
                val angleRad = Math.toRadians(angle.toDouble())
                val needleLength = radius * 0.8f
                val needleEnd = Offset(
                    center.x + needleLength.toFloat() * cos(angleRad).toFloat(),
                    center.y + needleLength.toFloat() * sin(angleRad).toFloat()
                )
                drawLine(
                    color = Color.White,
                    start = center,
                    end = needleEnd,
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
                
                drawCircle(color = Color.White, radius = 4.dp.toPx(), center = center)
            }
            
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${animatedSpeed.toInt()}",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "%",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
        }
        
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(20.dp)
                .background(
                    if (isReverse) Color.Red else Color(0xFF222222),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "R",
                color = if (isReverse) Color.White else Color(0xFF444444),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
