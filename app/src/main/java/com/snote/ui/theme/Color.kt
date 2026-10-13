package com.snote.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 底色预设。
 *
 * 目前提供两种浅色底：纯白与淡蓝。黑色预设**保留**在枚举里 ——
 * 它是既有选择，删除属于功能移除，不能因为「当前需求没提」就顺手砍掉。
 */
enum class ThemePreset(val label: String) {
    LIGHT("白色"),
    SKY("淡蓝色"),
    DARK("黑色")
}

/** 强调色候选。淡蓝为默认。 */
enum class Accent(val label: String, val argb: Long) {
    /** 默认强调色：偏柔和的淡蓝，与淡蓝底色同族但更深，保证文字对比度。 */
    SKY("淡蓝", 0xFF4A9EE8),
    MINT("薄荷", 0xFF23B58C),
    AMBER("琥珀", 0xFFE8912A),
    VIOLET("紫罗兰", 0xFF7C6BFF),
    ROSE("玫瑰", 0xFFE85C77)
}

fun Accent.color(): Color = Color(argb)

// —— 中性色：刻意只有三级灰阶 + 纯白/纯黑，不做渐变、不做玻璃拟态 ——
//
// ## 两个文字槽位的语义（改动前先读这段）
//
// text / M3 的 onSurface —— **正文**。模型回复、卡片内容、用户自己写的字，
//   以及**结构标题**（「卡片 1」「反向链接（谁提到我）」）一律用它。
// sub / M3 的 onSurfaceVariant —— **次要文字**。时间戳、卡片张数、空状态提示、
//   字段标签这类「有它更好、没它也能懂」的信息。
//
// 这两者不是「深一点/浅一点」的两档，而是两个语义角色。用反了的后果实测过：
// 对话页把整段模型回复写成 onSurfaceVariant，用户看到的是「一片灰字」，
// 反馈是「能不能把字改成黑色」—— 而正文色本来就是黑的，只是没用上。
// 反过来把时间戳写成 onSurface，次要信息会跳出来和正文抢注意力。
val Ink0 = Color(0xFF0E0E10)   // 黑底
val Ink1 = Color(0xFF17171A)   // 黑底上的卡片
val Ink2 = Color(0xFF232329)   // 黑底上的分隔线
val InkText = Color(0xFFECECEF)
val InkSub = Color(0xFF9A9AA4)
/** 黑底上「比卡片更外一层」的容器（底部栏、对话框）。三级灰阶之外多的一档。 */
val Ink3 = Color(0xFF1D1D21)

val Paper0 = Color(0xFFFFFFFF) // 白底
val Paper1 = Color(0xFFF6F7F9) // 白底上的卡片
val Paper2 = Color(0xFFE8EAEE) // 白底上的分隔线
val PaperText = Color(0xFF16181D)
val PaperSub = Color(0xFF6B7280)
val Paper3 = Color(0xFFF1F3F6) // 白底上的外延容器

// —— 淡蓝底色 ——
// 三级的层次关系与白底一致：底最亮、卡片略暗一点形成区隔、分隔线再深一档。
// 刻意**不用**纯蓝调：底色饱和度高会让长时间阅读疲劳，也让正文对比度下降。
val Sky0 = Color(0xFFEAF3FC)   // 淡蓝底
val Sky1 = Color(0xFFF7FBFF)   // 淡蓝底上的卡片（比底更亮，卡片「浮」起来）
val Sky2 = Color(0xFFD3E4F5)   // 淡蓝底上的分隔线
val SkyText = Color(0xFF14202E)
val SkySub = Color(0xFF5A7186)
val Sky3 = Color(0xFFE0EDF9)   // 淡蓝底上的外延容器

/**
 * 一套底色的全部中性色。
 *
 * ## 为什么要有这个结构
 *
 * 之前 Theme.kt 只往 lightColorScheme() 里传了 10 个角色，剩下的一律回落到
 * Material3 的基线调色板 —— 而基线是**紫色系**。结果就是只有我显式写过的
 * 控件颜色对，底部栏、「新建」FAB、ModalBottomSheet 这些组件的默认背景
 * 全是紫的。这不是配色没配好，是配色表**定义不全**。
 *
 * 现在把一套底色的中性色收进一个结构，再由 [buildColorScheme] 一次推出
 * 全部角色，以后新增组件不会再漏。
 */
data class NeutralPalette(
    /** 页面底色。 */
    val base: Color,
    /** 卡片 / 分区容器（surfaceVariant）。 */
    val container: Color,
    /** 比容器更外一层（底部栏、对话框、弹层的背景）。 */
    val containerHigh: Color,
    /** 最外一层（BottomSheet 拖出的那块）。 */
    val containerHighest: Color,
    /** 分隔线与描边。 */
    val outline: Color,
    /** 正文。 */
    val text: Color,
    /** 次要文字。 */
    val sub: Color,
    /** 是否深色底（决定强调色容器要用浅字还是深字）。 */
    val dark: Boolean,
)

val LightPalette = NeutralPalette(
    base = Paper0,
    container = Paper1,
    containerHigh = Paper3,
    containerHighest = Paper2,
    outline = Paper2,
    text = PaperText,
    sub = PaperSub,
    dark = false,
)

val SkyPalette = NeutralPalette(
    base = Sky0,
    container = Sky1,
    containerHigh = Sky3,
    containerHighest = Sky2,
    outline = Sky2,
    text = SkyText,
    sub = SkySub,
    dark = false,
)

val DarkPalette = NeutralPalette(
    base = Ink0,
    container = Ink1,
    containerHigh = Ink3,
    containerHighest = Ink2,
    outline = Ink2,
    text = InkText,
    sub = InkSub,
    dark = true,
)

/**
 * 把强调色按给定不透明度混到基色上。
 *
 * 用 alpha 混合而不是手写死色值：这样换强调色时，所有「强调色的浅色容器」
 * 会**跟着一起变**。之前这些容器全落在 M3 基线的紫上，换个强调色也不动，
 * 用户会以为换色功能坏了。
 */
fun Color.tint(accent: Color, alpha: Float): Color = Color(
    red = red * (1 - alpha) + accent.red * alpha,
    green = green * (1 - alpha) + accent.green * alpha,
    blue = blue * (1 - alpha) + accent.blue * alpha,
    alpha = 1f,
)
