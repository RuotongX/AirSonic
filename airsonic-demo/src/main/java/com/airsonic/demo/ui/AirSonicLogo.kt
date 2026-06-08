package com.airsonic.demo.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.airsonic.demo.ui.theme.Aurora
import kotlin.math.abs
import kotlin.math.sin

/**
 * AirSonic 原创 logo / 投射标记：
 * 底部一组同心「广播弧」(信号外扩) + 中部一排向上声波柱(中间最高，构成抽象「A」)，
 * 青→品红渐变。`level`(0..1) 驱动声波柱高度(音频律动)，`animated` 自我律动(开屏)。
 */
@Composable
fun AirSonicLogo(
    modifier: Modifier = Modifier,
    animated: Boolean = false,
    level: Float = 0.5f,
    color: Color? = null,
) {
    val t = rememberInfiniteTransition(label = "logo")
    val anim by t.animateFloat(
        initialValue = 0f,
        targetValue = (Math.PI * 2).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Restart),
        label = "wave",
    )
    val brush = if (color != null) SolidColor(color)
    else Brush.linearGradient(listOf(Aurora.Cyan, Aurora.Magenta))

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f

        // ---- 广播弧（底部，3 道同心信号弧）----
        val arcBaseY = h * 0.82f
        val arcStroke = (w * 0.045f)
        for (i in 0..2) {
            val r = w * (0.16f + i * 0.13f)
            val a = if (animated) 0.85f - i * 0.22f - 0.15f * (1 - sinNorm(anim)) else 0.85f - i * 0.22f
            drawArc(
                brush = brush,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(cx - r, arcBaseY - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = arcStroke, cap = StrokeCap.Round),
                alpha = a.coerceIn(0.15f, 1f),
            )
        }

        // ---- 声波柱（中部 5 根，构成「A」峰形）----
        val bars = 5
        val barW = w * 0.07f
        val gap = w * 0.05f
        val totalW = bars * barW + (bars - 1) * gap
        val startX = cx - totalW / 2f + barW / 2f
        val baseY = h * 0.66f
        // A 峰形权重：中间最高，两侧递减
        val peak = floatArrayOf(0.35f, 0.7f, 1f, 0.7f, 0.35f)
        for (i in 0 until bars) {
            val osc = if (animated) (0.5f + 0.5f * sin(anim + i * 0.9f)) else level
            val barH = h * (0.10f + 0.42f * peak[i] * (0.45f + 0.55f * osc))
            val x = startX + i * (barW + gap)
            drawLine(
                brush = brush,
                start = Offset(x, baseY),
                end = Offset(x, baseY - barH),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

private fun sinNorm(a: Float): Float = abs(sin(a))
