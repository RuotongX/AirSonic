// Copyright (c) 2026 Chunguang Wei (https://github.com/chunguangwei)
// Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only.
// Commercial use requires prior written consent: chunguangwee@gmail.com. See LICENSE.
// Modified UI: flat minimalist dark theme (no aurora/gradient/glassmorphism).

package com.airsonic.demo.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * HomeCast-flavored minimal dark theme.
 * Flat surfaces, a single accent color, no gradients, no glow, no glass.
 */
object Aurora {
    // Background & surfaces (neutral dark, no gradient)
    val BgTop = Color(0xFF0F1215)
    val BgBottom = Color(0xFF0F1215)
    val Surface = Color(0xFF1A1E24)
    val SurfaceAlt = Color(0xFF232830)

    // Single accent (Material blue)
    val Cyan = Color(0xFF4DA3FF)
    val Magenta = Color(0xFF6E7CF0)

    val CyanSoft = Color(0x1F4DA3FF)
    val MagentaSoft = Color(0x0F6E7CF0)

    // Mirror hero accent
    val HeroGreen = Color(0xFF4DA3FF)
    val HeroTeal = Color(0xFF4DA3FF)

    // Text
    val TextPrimary = Color(0xFFE8EAED)
    val TextSecondary = Color(0xFFA4ABB3)
    val TextDim = Color(0xFF6B7280)

    // Card fills & stroke (flat)
    val GlassFill = Color(0xFFFFFFFF).copy(alpha = 0.04f)
    val GlassFillStrong = Color(0xFFFFFFFF).copy(alpha = 0.07f)
    val GlassStroke = Color(0xFF2A313B)

    val heroBrush: Brush get() = Brush.linearGradient(listOf(HeroGreen, HeroTeal))

        val brandBrush: Brush get() = Brush.linearGradient(listOf(Cyan, Cyan))
    }

private val AuroraColorScheme = darkColorScheme(
    primary = Aurora.Cyan,
    secondary = Aurora.Cyan,
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

/** Flat background: solid color, no aurora animation. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Aurora.BgTop)
    ) {
        content()
    }
}

/** Flat card modifier: subtle fill + quiet stroke, no glow shadows. */
fun Modifier.glass(
    radius: Int = 24,
    strong: Boolean = false,
): Modifier {
    val shape = RoundedCornerShape(radius.dp)
    return this
        .background(
            color = if (strong) Aurora.GlassFillStrong else Aurora.GlassFill,
            shape = shape,
        )
        .border(
            width = 1.dp,
            color = Aurora.GlassStroke,
            shape = shape,
        )
}