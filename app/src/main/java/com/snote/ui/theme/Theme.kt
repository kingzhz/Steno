package com.snote.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val LocalPreset = staticCompositionLocalOf { ThemePreset.LIGHT }
val LocalAccent = staticCompositionLocalOf { Accent.SKY }

/**
 * 应用主题。
 *
 * ## 三套底色
 *
 * 白 / 淡蓝 / 黑，各自的中性色在 [NeutralPalette] 里，由 [buildColorScheme]
 * 推出完整 ColorScheme。
 *
 * ## 默认是白色
 *
 * 白底在室内光下最稳，也不挑壁纸；淡蓝作为并列选项而非默认。
 *
 * ## 淡蓝不跟随系统深浅
 *
 * 跟随的话，用户在深色模式下选淡蓝会突然变黑，与他的选择相反。
 */
@Composable
fun SnoteTheme(
    preset: ThemePreset = ThemePreset.LIGHT,
    accent: Accent = Accent.SKY,
    content: @Composable () -> Unit
) {
    val palette = when (preset) {
        ThemePreset.SKY -> SkyPalette
        ThemePreset.DARK -> DarkPalette
        ThemePreset.LIGHT -> LightPalette
    }
    val scheme = buildColorScheme(palette, accent.color())

    CompositionLocalProvider(LocalPreset provides preset, LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = SnoteShapes,
            typography = SnoteTypography,
            content = content
        )
    }
}

/**
 * 从「一套中性色 + 一个强调色」推出**完整**的 Material3 ColorScheme。
 *
 * ## 为什么必须把每个角色都写出来
 *
 * lightColorScheme() / darkColorScheme() 的每个参数都有默认值，而默认值是
 * Google 的**基线调色板 —— 紫色系**。只传一部分参数时，没传的那几个不会报错，
 * 而是静默回落到紫。表现就是「我显式写过颜色的控件是对的，没写过的组件
 * （底部栏、FAB、BottomSheet、Chip）全是紫的」。
 *
 * 所以这里刻意**一个不漏**地传满：宁可多写几十行，也不留给基线去填。
 *
 * ## 强调色容器用 alpha 混合
 *
 * primaryContainer / secondaryContainer / tertiaryContainer 都由强调色混出来，
 * 因此换强调色时它们跟着变，不会永远是同一个颜色。
 */
private fun buildColorScheme(p: NeutralPalette, accent: Color) = run {
    // 强调色的容器：浅底上用很淡的同色衬底 + 深色文字；深底上反过来。
    val primaryContainer = if (p.dark) accent.tint(p.base, 0.30f) else accent.tint(p.base, 0.16f)
    val secondaryContainer = if (p.dark) accent.tint(p.base, 0.22f) else accent.tint(p.base, 0.12f)
    val tertiaryContainer = if (p.dark) accent.tint(p.base, 0.26f) else accent.tint(p.base, 0.14f)

    // 容器上的文字：浅底用强调色的深版，深底用强调色的浅版，保证对比度。
    val onContainer = if (p.dark) accent.tint(Color.White, 0.35f) else accent.tint(Color.Black, 0.25f)
    val onSecondaryContainer = if (p.dark) p.text else accent.tint(Color.Black, 0.20f)
    val onTertiaryContainer = if (p.dark) p.text else accent.tint(Color.Black, 0.22f)

    val base = if (p.dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onContainer,
            inversePrimary = accent,
            secondary = accent,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = accent,
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = p.base,
            onBackground = p.text,
            surface = p.base,
            onSurface = p.text,
            surfaceVariant = p.container,
            onSurfaceVariant = p.sub,
            surfaceTint = accent,
            inverseSurface = p.text,
            inverseOnSurface = p.base,
            error = Color(0xFFFF6B6B),
            onError = Color(0xFF2B0A0A),
            errorContainer = Color(0xFF5C1F1F),
            onErrorContainer = Color(0xFFFFD9D9),
            outline = p.outline,
            outlineVariant = p.containerHigh,
            scrim = Color.Black,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Color.White,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onContainer,
            inversePrimary = accent,
            secondary = accent,
            onSecondary = Color.White,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            tertiary = accent,
            onTertiary = Color.White,
            tertiaryContainer = tertiaryContainer,
            onTertiaryContainer = onTertiaryContainer,
            background = p.base,
            onBackground = p.text,
            surface = p.base,
            onSurface = p.text,
            surfaceVariant = p.container,
            onSurfaceVariant = p.sub,
            surfaceTint = accent,
            inverseSurface = p.text,
            inverseOnSurface = p.base,
            error = Color(0xFFB3261E),
            onError = Color.White,
            errorContainer = Color(0xFFF9DEDC),
            onErrorContainer = Color(0xFF410E0B),
            outline = p.outline,
            outlineVariant = p.containerHigh,
            scrim = Color.Black,
        )
    }

    // —— surfaceContainer 系列 ——
    // M3 的底部栏、BottomSheet、对话框默认取这几个角色。它们在 ColorScheme
    // 的构造参数里**没有**对应入参（是 surface 的容器色阶），因此只能建好之后
    // 用 copy 覆盖。之前底部栏发紫，根因就在这里。
    base.copy(
        surfaceContainerLowest = p.base,
        surfaceContainerLow = p.container,
        surfaceContainer = p.containerHigh,
        surfaceContainerHigh = p.containerHigh,
        surfaceContainerHighest = p.containerHighest,
    )
}
