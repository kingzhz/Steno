package com.snote.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler

/**
 * 极简 markdown 渲染。
 *
 * ## 为什么自己写而不引库
 *
 * 要渲染的只有模型输出这一种来源，它用到的语法是**固定且很窄的一小撮**：
 * 标题、列表、粗体、行内代码、代码块。引一个完整 markdown 库要多背几百 KB 和
 * 一条依赖链，而本项目第一约束是体积。
 *
 * ## 不支持表格与嵌套列表
 *
 * 刻意不做：表格在手机上本来就难读（宽了要横向滚、窄了挤成一团），
 * 与其渲染一个难看的表格，不如让它按纯文本显示。嵌套列表同理。
 *
 * ## 不认识的语法怎么办
 *
 * 原样显示。宁可让用户看到星号，也不要把不认识的语法吃掉 ——
 * 静默吞字符会让用户以为内容丢了。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    codeBackground: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    // 链接点击：交给系统浏览器。本 App 不内置 WebView —— 那是另一个体积与
    // 安全面的大件，而用户点链接的意图就是「去看那个网页」。
    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> Text(
                    text = block.text,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .background(codeBackground, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    color = color,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                is MdBlock.Line -> {
                    val style = when (block.kind) {
                        MdLineKind.H1 -> MaterialTheme.typography.titleLarge
                        MdLineKind.H2 -> MaterialTheme.typography.titleMedium
                        MdLineKind.H3 -> MaterialTheme.typography.titleSmall
                        MdLineKind.Quote -> MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = FontStyle.Italic
                        )
                        else -> MaterialTheme.typography.bodyMedium
                    }
                    if (block.text.isBlank()) {
                        // 空行只占一点高度，不必真的渲染一个空 Text。
                        Text("", style = style)
                    } else {
                        val annotated = inlineStyled(block.text, codeBackground, LINK_COLOR)
                        Text(
                            text = annotated,
                            color = color,
                            style = style,
                            modifier = Modifier.clickable {
                                // 只有点中带 URL 注解的片段才跳转；点普通文字不做事。
                                annotated.getStringAnnotations("URL", 0, annotated.length)
                                    .firstOrNull()
                                    ?.let { runCatching { uriHandler.openUri(it.item) } }
                            }
                        )
                    }
                }
            }
        }
    }
}

/** 行级元素。 */
enum class MdLineKind { H1, H2, H3, Bullet, Ordered, Quote, Plain }

/** 一个块级元素：要么是代码块，要么是一行。 */
sealed interface MdBlock {
    data class Code(val text: String) : MdBlock
    data class Line(val text: String, val kind: MdLineKind) : MdBlock
}

/**
 * 把 markdown 切成块。
 *
 * 用行扫描而不是正则全文匹配：代码块要吃掉中间所有内容（包括看起来像标题的行），
 * 正则做这件事要么写得很绕，要么在边界上出错。
 */
fun parseMarkdown(src: String): List<MdBlock> {
    val out = ArrayList<MdBlock>()
    val lines = src.split('\n')
    val code = StringBuilder()
    var inCode = false

    for (raw in lines) {
        val trimmed = raw.trimStart()

        // 围栏代码块：三个反引号开始、三个反引号结束。语言标注一并吞掉。
        if (trimmed.startsWith(FENCE)) {
            if (inCode) {
                out.add(MdBlock.Code(code.toString().trimEnd('\n')))
                code.setLength(0)
                inCode = false
            } else {
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(raw).append('\n')
            continue
        }

        out.add(MdBlock.Line(raw, classify(trimmed)))
    }

    // 围栏没闭合（模型输出被截断）：把已收到的当代码块显示，别丢掉。
    if (inCode && code.isNotEmpty()) out.add(MdBlock.Code(code.toString().trimEnd('\n')))
    return out
}

private fun classify(trimmed: String): MdLineKind = when {
    trimmed.startsWith("### ") -> MdLineKind.H3
    trimmed.startsWith("## ") -> MdLineKind.H2
    trimmed.startsWith("# ") -> MdLineKind.H1
    trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> MdLineKind.Bullet
    ORDERED.matches(trimmed) -> MdLineKind.Ordered
    trimmed.startsWith("> ") -> MdLineKind.Quote
    else -> MdLineKind.Plain
}

private val ORDERED = Regex("^\\d+\\.\\s+.*")

/** 三个反引号。写成常量是因为 Kotlin 里它嵌在字符串里很难读。 */
private const val FENCE = "\u0060\u0060\u0060"

/** 单个反引号。 */
private const val TICK = '\u0060'

/**
 * 行内样式：粗体、斜体、行内代码。
 *
 * 成对符号找不到闭合时**原样输出那个字符**，不吞不报错 —— 模型输出的中间态
 * （流式时只收到一半的星号对）很常见，那时候不该让文字消失。
 */
fun inlineStyled(
    src: String,
    codeBackground: Color,
    linkColor: Color = codeBackground,
): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < src.length) {
            when {
                // markdown 链接 [文字](URL)：只显示文字，点击跳到 URL。
                //
                // 这条分支必须排在所有样式分支**之前**：链接里常含 ** 与 *，
                // 先被粗体吃掉的话，URL 会作为普通文字留在正文里 ——
                // 而它动辄上百字符（实测 rfi.fr 那条带中文百分号编码的链接
                // 能占满一整行），这正是把段落排版撑爆的元凶。
                src[i] == '[' -> {
                    val close = src.indexOf("](", i + 1)
                    val end = if (close > i) src.indexOf(')', close + 2) else -1
                    if (close > i && end > close) {
                        appendLink(
                            label = src.substring(i + 1, close),
                            url = src.substring(close + 2, end),
                            linkColor = linkColor,
                        )
                        i = end + 1
                    } else {
                        append(src[i]); i++
                    }
                }
                // 裸 URL：正文里直接贴出来的长链接。显示成短形式（域名 + 省略号），
                // 完整地址藏在链接注解里 —— 既不再撑爆行宽，也没有丢信息。
                src.startsWith("https://", i) || src.startsWith("http://", i) -> {
                    val end = urlEnd(src, i)
                    val url = src.substring(i, end)
                    appendLink(
                        label = shortUrl(url),
                        url = url,
                        linkColor = linkColor,
                    )
                    i = end
                }
                src.startsWith("**", i) -> {
                    val end = src.indexOf("**", i + 2)
                    if (end > i) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(src.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(src[i]); i++
                    }
                }
                src[i] == TICK -> {
                    val end = src.indexOf(TICK, i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                            append(src.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(src[i]); i++
                    }
                }
                src[i] == '*' -> {
                    val end = src.indexOf('*', i + 1)
                    if (end > i) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(src.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(src[i]); i++
                    }
                }
                else -> {
                    append(src[i]); i++
                }
            }
        }
    }

/**
 * 往 AnnotatedString 里追加一段「显示文字、点击打开链接」的内容。
 *
 * 用 StringAnnotation 而不是 ClickableText：后者的可点区域覆盖整段文本，
 * 一行里混着普通文字与链接时会误触；注解方式由 Compose 精确判定命中的是哪个链接。
 */
private fun AnnotatedString.Builder.appendLink(label: String, url: String, linkColor: Color) {
    pushStringAnnotation(tag = "URL", annotation = url)
    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
        append(label)
    }
    pop()
}

/** 裸 URL 到哪里结束：空白、括号、CJK 标点都算边界。 */
private fun urlEnd(src: String, start: Int): Int {
    var i = start
    while (i < src.length) {
        val c = src[i]
        if (c.isWhitespace() || c == ')' || c == ']' || c == '>' ||
            c == '\uFF0C' || c == '\u3002' || c == '\u3001' || c == '\uFF09' ||
            c == '\u3011' || c == '"' || c == '\''
        ) break
        i++
    }
    // 尾部常被中文句读黏上英文句点，剥掉 —— 留着会让链接点不开。
    while (i > start && (src[i - 1] == '.' || src[i - 1] == ',' || src[i - 1] == ';')) i--
    return i
}

/**
 * 把长 URL 显示成短形式。
 *
 * 只留「协议 + 域名」，超长部分用省略号。目的不是好看，而是
 * **不让一条链接独占一整行** —— 实测带中文百分号编码的新闻链接能长到
 * 200 字符以上，在手机上就是一次满屏滚动。
 */
private fun shortUrl(url: String): String {
    if (url.length <= URL_SHOW_MAX) return url
    val schemeEnd = url.indexOf("://")
    val hostStart = if (schemeEnd > 0) schemeEnd + 3 else 0
    val pathStart = url.indexOf('/', hostStart)
    val head = if (pathStart > hostStart) url.substring(0, pathStart) else url
    return if (head.length <= URL_SHOW_MAX) head + "/…" else head.take(URL_SHOW_MAX) + "…"
}

/** 短链接里最多显示多少个字符。 */
private const val URL_SHOW_MAX = 48

/**
 * 链接颜色。
 *
 * 用固定的蓝而不是主题色：主题色在深色气泡（用户自己发的消息）上对比度很低，
 * 链接会看不清。链接是要被认出来的，不能跟着背景走。
 */
private val LINK_COLOR = Color(0xFF1A73E8)

