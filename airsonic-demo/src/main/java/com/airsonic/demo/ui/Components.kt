// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airsonic.demo.ui.theme.Aurora
import com.airsonic.demo.ui.theme.glass
import kotlin.math.sin

/** 区块小标题。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Aurora.TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = modifier)
}

/** 主行动按钮（青→品红渐变胶囊）。 */
@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(
                brush = if (enabled) Aurora.brandBrush
                else Brush.linearGradient(listOf(Aurora.TextDim, Aurora.TextDim)),
                shape = RoundedCornerShape(28.dp),
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF00131A), fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

/** 次级玻璃按钮。 */
@Composable
fun GlassButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .glass(radius = 27)
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Aurora.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }
}

/** 分段选择器（自动/2K/1080p 等）。disabled 时灰显带"即将推出"。 */
@Composable
fun Segmented(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean = true,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glass(radius = 16)
            .padding(4.dp)
            .alpha(if (enabled) 1f else 0.45f),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        brush = if (sel) Aurora.brandBrush else Brush.linearGradient(listOf(Color.Transparent, Color.Transparent)),
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable(enabled = enabled) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (sel) Color(0xFF00131A) else Aurora.TextSecondary,
                    fontSize = 14.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun Modifier.weight1(): Modifier = this

/** 时长计时器文本（mm:ss / h:mm:ss），每秒刷新。 */
@Composable
fun DurationText(startedAtElapsedMs: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(startedAtElapsedMs) {
        while (startedAtElapsedMs > 0) {
            now = android.os.SystemClock.elapsedRealtime()
            kotlinx.coroutines.delay(500)
        }
    }
    val sec = if (startedAtElapsedMs > 0) ((now - startedAtElapsedMs) / 1000).coerceAtLeast(0) else 0
    val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
    val text = if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    Text(text, color = Aurora.Cyan, fontSize = 28.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = modifier)
}

/**
 * 科技感音波律动条：`level`(0..1) 驱动；level≈0 时也有缓动呼吸（待机感）。
 */
@Composable
fun WaveBars(level: Float, modifier: Modifier = Modifier, bars: Int = 24) {
    val t = rememberInfiniteTransition(label = "wave")
    val anim by t.animateFloat(0f, (Math.PI * 2).toFloat(), infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "p")
    val brush = Aurora.brandBrush
    Canvas(modifier = modifier) {
        val w = size.width; val h = size.height
        val gap = w / (bars * 2f)
        val barW = (w - gap * (bars + 1)) / bars
        val baseY = h / 2f
        for (i in 0 until bars) {
            val env = 0.5f + 0.5f * sin(anim + i * 0.55f)
            val amp = (0.12f + 0.88f * level) * env
            val bh = (h * 0.85f * amp).coerceAtLeast(h * 0.04f)
            val x = gap + i * (barW + gap) + barW / 2f
            drawLine(brush, Offset(x, baseY - bh / 2), Offset(x, baseY + bh / 2), strokeWidth = barW, cap = StrokeCap.Round)
        }
    }
}

/**
 * 真·频谱条：读 [spectrum]（24 频段 0..1，来自 FFT）画对称柱，
 * 每帧平滑——上升直接跟随、回落缓慢衰减（经典频谱视觉）。空闲(全 0)时退化为细线。
 */
@Composable
fun SpectrumBars(spectrum: FloatArray, modifier: Modifier = Modifier) {
    val display = remember { FloatArray(CastEngine.SPECTRUM_BANDS) }
    var frame by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { frame = it } } }
    val brush = Aurora.brandBrush
    Canvas(modifier) {
        @Suppress("UNUSED_EXPRESSION") frame   // 订阅帧时钟：驱动回落动画每帧重绘
        val n = display.size
        val w = size.width; val h = size.height
        val gap = w / (n * 2.4f)
        val barW = (w - gap * (n + 1)) / n
        val baseY = h / 2f
        for (i in 0 until n) {
            val target = spectrum.getOrElse(i) { 0f }
            display[i] = if (target >= display[i]) target else display[i] * 0.80f + target * 0.20f
            val bh = (h * 0.92f * display[i]).coerceAtLeast(h * 0.03f)
            val x = gap + i * (barW + gap) + barW / 2f
            drawLine(brush, Offset(x, baseY - bh / 2), Offset(x, baseY + bh / 2), strokeWidth = barW, cap = StrokeCap.Round)
        }
    }
}

/** 顶部"当前设备"条 + 状态点。 */
@Composable
fun DeviceChip(name: String?, sub: String?, connected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .glass(radius = 22)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(9.dp).background(
                if (connected) Aurora.Cyan else Aurora.TextDim,
                shape = RoundedCornerShape(50),
            )
        )
        Spacer(Modifier.width(8.dp))
        Column {
            Text(name ?: L10n.s.selectDevice, color = Aurora.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = Aurora.TextDim, fontSize = 11.sp)
        }
        Spacer(Modifier.width(6.dp))
        Text("▾", color = Aurora.TextSecondary, fontSize = 14.sp)
    }
}

/** 即将推出角标。 */
@Composable
fun ComingSoonTag(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Aurora.MagentaSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(L10n.s.comingSoon, color = Aurora.TextPrimary, fontSize = 10.sp)
    }
}

/** 投送中浮层（设备名 + 时长 + 律动 + 停止）。 */
@Composable
fun CastingBar(deviceName: String, startedAt: Long, spectrum: FloatArray, onStop: () -> Unit) {
    AnimatedVisibility(visible = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glass(radius = 24, strong = true)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("${L10n.s.castingTo} $deviceName", color = Aurora.TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            DurationText(startedAt)
            Spacer(Modifier.height(12.dp))
            SpectrumBars(spectrum = spectrum, modifier = Modifier.fillMaxWidth().height(56.dp))
            Spacer(Modifier.height(14.dp))
            GlassButton(L10n.s.stop, onClick = onStop)
        }
    }
}