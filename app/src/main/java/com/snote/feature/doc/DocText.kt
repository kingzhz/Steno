package com.snote.feature.doc

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * 从用户选的文件里抽出纯文本，供对话使用。
 *
 * ## 能力边界（哪些能读，哪些读不了）
 *
 * | 类型 | 做法 | 依赖 |
 * |---|---|---|
 * | .txt / .md / .csv | 直接按文本读 | 无 |
 * | .docx / .pptx / .xlsx | 解 zip 抽 XML | 无（java.util.zip 是平台自带） |
 * | .pdf（有文字层） | PdfBox 抽取 | pdfbox-android |
 * | .pdf（扫描件） | **抽不出文字**，需渲染成图走识图 | 见 [DocParseResult.NeedsVision] |
 * | .doc（老二进制格式） | **不支持** | 该格式没有可用的纯 Kotlin/Java 实现 |
 *
 * ## 为什么 docx 要自己解而不是用库
 *
 * docx 就是一个 zip，正文在 word/document.xml 里，段落是 w:p、文本是 w:t。
 * 为这点事引一个库，和「本项目第一约束是体积」直接冲突。实测（2026-10-12）
 * 手写解析能从真 docx 里正确抽出标题、加粗文字与列表。
 */
object DocText {

    /**
     * 解析结果。
     *
     * 用密封类而不是「返回 String 或 null」：三种结局对用户的含义完全不同 ——
     * 「读出来了」「这是扫描件要另外处理」「这格式读不了」，混成一个 null
     * 就只能给一句含糊的「读取失败」。
     */
    sealed interface DocParseResult {
        /** 成功。text 已裁到 [MAX_CHARS]。 */
        data class Ok(val text: String, val truncated: Boolean) : DocParseResult

        /** 是 PDF 但没有文字层（扫描件）。 */
        data object NeedsVision : DocParseResult

        /** 格式不支持，message 里说明原因。 */
        data class Unsupported(val message: String) : DocParseResult
    }

    /** 一次最多抽多少字符。整本书塞进上下文既烧钱又没用。 */
    const val MAX_CHARS = 20000

    /**
     * 按文件类型分派。
     *
     * @param name 原始文件名（用来判断扩展名 —— mime 在部分机型上为空或不准）
     * @param mime contentResolver 报的类型，可能为 null
     */
    fun extract(context: Context, uri: Uri, name: String, mime: String?): DocParseResult {
        val ext = name.substringAfterLast('.', "").lowercase()
        return try {
            when {
                ext == "txt" || ext == "md" || ext == "csv" ||
                    mime?.startsWith("text/") == true -> readPlainText(context, uri)
                ext == "docx" -> readZipXml(context, uri, DOCX_ENTRY, DOCX_TEXT_TAG)
                ext == "pptx" -> readZipXml(context, uri, PPTX_ENTRY, PPTX_TEXT_TAG)
                ext == "xlsx" -> readZipXml(context, uri, XLSX_ENTRY, XLSX_TEXT_TAG)
                ext == "pdf" || mime == "application/pdf" -> PdfText.extract(context, uri)
                ext == "doc" -> DocParseResult.Unsupported(
                    "旧版 .doc 是老二进制格式，手机上读不了。请用 Word 另存为 .docx 再传。"
                )
                else -> DocParseResult.Unsupported("暂不支持 ." + ext + " 格式。")
            }
        } catch (t: Throwable) {
            DocParseResult.Unsupported("读取失败：" + (t.message ?: t.javaClass.simpleName))
        }
    }

    // ---------- 纯文本 ----------

    /**
     * 直接读文本。
     *
     * 用 UTF-8 读，并**在解码失败时回退到 GBK**：国内大量 txt 是 GBK 编码，
     * 按 UTF-8 硬读会得到一整片替换符（乱码），而用户不知道该怪谁。
     */
    private fun readPlainText(context: Context, uri: Uri): DocParseResult {
        val bytes = open(context, uri)?.use { it.readBytes() }
            ?: return DocParseResult.Unsupported("读不到文件内容。")

        val text = decodeText(bytes)
        return clip(text)
    }

    /**
     * 先试 UTF-8，再试 GBK。
     *
     * 判据用「解码结果里替换符 U+FFFD 的占比」而不是 try/catch：
     * String(bytes, charset) 在遇到非法字节时**不抛异常**，只是静默替换成 U+FFFD，
     * 所以必须解完再检查。
     */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        val bad = utf8.count { it == '\uFFFD' }
        if (bad * 20 < utf8.length.coerceAtLeast(1)) return utf8

        // UTF-8 解出大量替换符 ⇒ 大概率不是 UTF-8。国内 txt 常见 GBK。
        val gbk = try {
            String(bytes, charset("GBK"))
        } catch (t: Throwable) {
            null
        } ?: return utf8
        val gbkBad = gbk.count { it == '\uFFFD' }
        return if (gbkBad < bad) gbk else utf8
    }

    private fun clip(text: String): DocParseResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DocParseResult.Unsupported("文件里没有可读的文字。")
        return if (trimmed.length <= MAX_CHARS) {
            DocParseResult.Ok(trimmed, false)
        } else {
            DocParseResult.Ok(trimmed.substring(0, MAX_CHARS), true)
        }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        }

    // ---------- Office（zip + XML） ----------

    /**
     * 从 Office 文档里抽文字。
     *
     * 三个格式的差异只有「正文在哪个条目」与「文本用哪个标签」，
     * 因此共用一套逻辑 —— 分别写三遍只会有三处各自长歪的机会。
     */
    private fun readZipXml(
        context: Context,
        uri: Uri,
        entryName: String,
        textTag: String,
    ): DocParseResult {
        val xml = open(context, uri)?.use { input ->
            ZipInputStream(input).use { zip ->
                generateSequence { zip.nextEntry }
                    .firstOrNull { it.name == entryName }
                    ?.let { readEntry(zip) }
            }
        } ?: return DocParseResult.Unsupported("文件结构不对：找不到 " + entryName + "。")

        val text = stripXml(xml, textTag)
        return clip(text)
    }

    /**
     * 抽一个 zip 条目的全部字节。
     *
     * 不能直接 readBytes()：ZipInputStream 的 read 在条目结束时返回 -1，
     * 而 readBytes() 会一路读到整个流结束（把后面所有条目也吞进来）。
     * 必须自己循环到条目边界。
     */
    private fun readEntry(zip: ZipInputStream): String {
        val buf = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val n = zip.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * 从 XML 里抽正文。
     *
     * 用正则剥标签而不是 XmlPullParser：这里只要「把所有 <w:t> 的文本按顺序拼起来」，
     * 而 Office 的 XML 有巨量命名空间属性，用解析器反而要写一堆状态机。
     * 边界情况（嵌套标签、属性里的 > ）对这三种格式的正文影响极小。
     */
    private fun stripXml(xml: String, textTag: String): String {
        val sb = StringBuilder()
        // 段落/行边界换成换行，否则所有文字会连成一坨。
        var s = xml
        for (boundary in LINE_BREAKS) {
            s = s.replace(boundary, "\n")
        }
        // 逐个抽 <tag ...>文字</tag>
        val re = Regex("<" + textTag + "[^>]*>([\\s\\S]*?)</" + textTag + ">")
        var last = 0
        for (m in re.findAll(s)) {
            sb.append(m.groupValues[1])
            last = m.range.last
        }
        if (sb.isEmpty()) return ""
        // 兜底：把实体还原（模型不需要看到 &amp; 这种写法）
        return sb.toString()
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
            .replace("&amp;", "&")
    }

    private val LINE_BREAKS = listOf(
        "</w:p>", "</a:p>", "</w:tr>",
        "<w:br/>", "<w:br />", "<a:br/>",
    )

    // ---------- 各格式的条目与标签 ----------

    private const val DOCX_ENTRY = "word/document.xml"
    private const val DOCX_TEXT_TAG = "w:t"

    private const val PPTX_ENTRY = "ppt/slides/slide1.xml"
    private const val PPTX_TEXT_TAG = "a:t"

    private const val XLSX_ENTRY = "xl/sharedStrings.xml"
    private const val XLSX_TEXT_TAG = "t"
}
