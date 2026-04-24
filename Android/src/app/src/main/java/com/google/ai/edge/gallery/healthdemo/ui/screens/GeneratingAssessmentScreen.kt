package com.google.ai.edge.gallery.healthdemo.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.ai.edge.gallery.healthdemo.viewmodel.HealthDemoViewModel

private val DarkBg = Color(0xFF07071A)
private val RingColor = Color(0xFF3D3D8F)
private val RingColorBright = Color(0xFF6060CC)
private val TextBlue = Color(0xFF7B8FFF)

@Composable
fun GeneratingAssessmentScreen(
    viewModel: HealthDemoViewModel,
    onReady: () -> Unit,
    onError: () -> Unit = onReady,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Terminal state watcher — exit the screen whenever inference stops,
    // win or lose. Previously only success navigated forward, which meant a
    // parser failure (isProcessing=false, guidance=null, inferenceError set)
    // left the rings spinning forever and the user stranded on the loader.
    LaunchedEffect(uiState.guidance, uiState.isProcessing, uiState.inferenceError) {
        if (uiState.isProcessing) return@LaunchedEffect
        when {
            uiState.guidance != null -> onReady()
            uiState.inferenceError != null -> onError()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "rings")
    val rotation1 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "r1"
    )
    val rotation2 by infiniteTransition.animateFloat(
        initialValue = 360f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "r2"
    )
    val rotation3 by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(12000, easing = LinearEasing)),
        label = "r3"
    )
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "dot"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF1A1A4E), Color(0xFF07071A)),
                    radius = 900f
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Generating\nAssessment",
                fontSize = 38.sp,
                fontWeight = FontWeight.Bold,
                color = TextBlue,
                textAlign = TextAlign.Center,
                lineHeight = 46.sp
            )

            Spacer(modifier = Modifier.height(60.dp))

            // Animated concentric dotted rings
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(280.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 12f), 0f)
                    val stroke = Stroke(width = 2.5f, pathEffect = dashEffect)

                    rotate(rotation3, pivot = center) {
                        drawCircle(
                            color = RingColor.copy(alpha = 0.4f),
                            radius = size.minDimension / 2f * 0.92f,
                            center = center,
                            style = stroke
                        )
                    }
                    rotate(rotation2, pivot = center) {
                        drawCircle(
                            color = RingColor.copy(alpha = 0.6f),
                            radius = size.minDimension / 2f * 0.70f,
                            center = center,
                            style = stroke
                        )
                    }
                    rotate(rotation1, pivot = center) {
                        drawCircle(
                            color = RingColorBright.copy(alpha = 0.9f),
                            radius = size.minDimension / 2f * 0.48f,
                            center = center,
                            style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f))
                        )
                    }
                    // Inner glow circle
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF3030AA).copy(alpha = 0.5f), Color.Transparent),
                            center = center,
                            radius = size.minDimension / 2f * 0.35f
                        ),
                        radius = size.minDimension / 2f * 0.35f,
                        center = center
                    )
                }

                // Pulsing dots in center
                Text(
                    "•  •  •  •",
                    fontSize = 20.sp,
                    color = Color.White.copy(alpha = dotAlpha),
                    letterSpacing = 4.sp
                )
            }

            Spacer(modifier = Modifier.height(60.dp))

            // Loading pill
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.12f)
            ) {
                Text(
                    text = uiState.processingStatus.ifEmpty { "Loading..." },
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}
