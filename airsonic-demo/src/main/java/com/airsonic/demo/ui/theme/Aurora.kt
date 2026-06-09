// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.

package com.airsonic.demo.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp

/**
 * AirSonic「深空极光 Aurora」设计系统。
 *
 * 深蓝→紫渐变背景 + 缓动极光流光 + 玻璃拟态卡片 + 青/品红辉光。
 * 全部用 Compose 绘制，最低 API 29 兼容（不依赖 Modifier.blur 的 31+ 限制）。
 */
object Aurora {
    // 背景（藏青，对齐参考图，干净不偏紫）
    val BgTop = Color(0xFF243657)
    val BgBottom = Color(0xFF1A2742)
    val Surface = Color(0xFF222F4C)

    // 强调（青 + 绿青 hero）
    val Cyan = Color(0xFF45E0E0)
    val Magenta = Color(0xFF9C6BFF)
    val CyanSoft = Color(0x6645E0E0)
    val MagentaSoft = Color(0x669C6BFF)
    // 镜像 hero 绿→青（对齐参考图）
    val HeroGreen = Color(0xFF34E0B0)
    val HeroTeal = Color(0xFF1FB6D8)

    // 文本
    val TextPrimary = Color(0xFFF4F8FF)
    val TextSecondary = Color(0xFFC2CEE8)
    val TextDim = Color(0xFF8A98B8)

    // 玻璃（藏青卡片）
    val GlassFill = Color(0x1FFFFFFF)
    val GlassFillStrong = Color(0x33FFFFFF)
    val GlassStroke = Color(0x40FFFFFF)

    val heroBrush: Brush get() = Brush.linearGradient(listOf(HeroGreen, HeroTeal))

    val brandBrush: Brush
        get() = Brush.linearGradient(listOf(Cyan, Magenta))
}

private val AuroraColorScheme = darkColorScheme(
    primary = Aurora.Cyan,
    secondary = Aurora.Magenta,
    background = Aurora.BgTop,
    surface = Aurora.Surface,
    onPrimary = Color(0xFF00131A),
    onBackground = Aurora.TextPrimary,
    onSurface = Aurora.TextPrimary,
)

@Composable
fun AirSonicTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AuroraColorScheme,
        typography = Typography(),
        content = content,
    )
}

/**
 * 全屏极光背景：深蓝→紫线性渐变 + 两团缓动呼吸的极光（青/品红径向光晕）。
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "aurora")
    val phase by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "phase",
    )
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Aurora.BgTop, Aurora.BgBottom)))
            .drawBehind {
                val w = size.width
                val h = size.height
                // 青色极光（左上↔右中游动）
                val c1 = Offset(w * (0.25f + 0.25f * phase), h * (0.12f + 0.10f * phase))
                drawIntoCanvas {
                    drawCircleGlow(c1, w * 0.75f, Aurora.Cyan.copy(alpha = 0.16f))
                }
                // 品红极光（右下↔左中游动）
                val c2 = Offset(w * (0.82f - 0.30f * phase), h * (0.82f - 0.12f * phase))
                drawCircleGlow(c2, w * 0.85f, Aurora.Magenta.copy(alpha = 0.14f))
            }
    ) {
        content()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCircleGlow(
    center: Offset,
    radius: Float,
    color: Color,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color, Color.Transparent),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/** 玻璃拟态卡片修饰：半透填充 + 1px 青↔品红渐变描边。 */
fun Modifier.glass(
    radius: Int = 24,
    strong: Boolean = false,
): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .shadow(
            elevation = 10.dp,
            shape = shape,
            clip = false,
            ambientColor = Aurora.Magenta.copy(alpha = 0.35f),
            spotColor = Aurora.Cyan.copy(alpha = 0.40f),
        )
        .background(
            color = if (strong) Aurora.GlassFillStrong else Aurora.GlassFill,
            shape = shape,
        )
        .border(
            width = 1.dp,
            brush = Brush.linearGradient(
                listOf(Aurora.GlassStroke, Aurora.CyanSoft, Aurora.MagentaSoft)
            ),
            shape = shape,
        )
}