package com.leanworkbench.app

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import leanwb.LatticeSplash

private val SplashBg = Color(0xFF121311)
private val SplashInk = Color(0xFFF6F4EE)
private val SplashJoin = Color(0xFF7FD196)

/**
 * The launch splash, drawn over the app, which composes behind it. The timing
 * is [LatticeSplash]; this only draws its frames. A tap skips it; with animations off
 * in the system settings, it shows the finished mark briefly.
 */
@Composable
fun LatticeSplashOverlay(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val reduceMotion = remember {
        try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduceMotion) delay(420)
        else clock.animateTo(LatticeSplash.TOTAL_MS, tween(LatticeSplash.TOTAL_MS.toInt(), easing = LinearEasing))
        onDone()
    }
    // With animations off, the finished, still mark (the frame just before the fade).
    val f = LatticeSplash.frame(if (reduceMotion) LatticeSplash.TOTAL_MS - LatticeSplash.FADE_MS else clock.value)
    Box(
        Modifier.fillMaxSize()
            .graphicsLayer { alpha = f.alpha }
            .background(SplashBg)
            .pointerInput(Unit) { detectTapGestures { onDone() } },
        contentAlignment = Alignment.Center,
    ) {
        // The launcher icon's geometry, in its 108-unit viewport.
        Canvas(Modifier.size(180.dp).graphicsLayer { scaleX = f.scale; scaleY = f.scale }) {
            val u = size.minDimension / 108f
            fun pt(x: Float, y: Float) = Offset(x * u, y * u)
            val bottom = pt(54f, 78f)
            val left = pt(34f, 54f)
            val right = pt(74f, 54f)
            val join = pt(54f, 30f)
            val w = 4f * u
            grow(bottom, left, f.edges[0], w)
            grow(bottom, right, f.edges[1], w)
            grow(left, join, f.edges[2], w)
            grow(right, join, f.edges[3], w)
            val r = 6.5f * u
            if (f.glow > 0f) drawCircle(SplashJoin.copy(alpha = 0.28f * f.glow), radius = r * (1.6f + 0.9f * f.glow), center = join)
            listOf(bottom, left, right).forEachIndexed { i, c -> if (f.nodes[i] > 0.01f) drawCircle(SplashInk, r * f.nodes[i], c) }
            if (f.nodes[3] > 0.01f) drawCircle(SplashJoin, r * f.nodes[3], join)
        }
    }
}

private fun DrawScope.grow(a: Offset, b: Offset, k: Float, w: Float) {
    if (k <= 0f) return
    drawLine(SplashInk, a, a + (b - a) * k, strokeWidth = w, cap = StrokeCap.Round)
}
