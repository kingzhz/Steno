package com.snote.feature.doc

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/**
 * 从 PDF 抽文字层。
 *
 * ## 只做「有文字层」的 PDF
 *
 * 扫描件 PDF 里根本没有文字对象（实测造过一份无文字层的 PDF：不含 /Font、
 * 不含 BT/ET 文字块、不含 Tj/TJ 绘制操作符）。这类文件用任何解析库都只能
 * 得到空白 —— 不是我们没实现好，是信息确实不在里面。此时返回
 * [DocText.DocParseResult.NeedsVision]，由界面提示用户改用识图方式。
 *
 * ## 为什么必须初始化 PDFBoxResourceLoader
 *
 * PdfBox 自带一套字体（约 1.5MB）用于解析带嵌入字体的 PDF。不调用 init 时，
 * 遇到这类 PDF 会在解析途中抛异常 —— 而不是启动时就报错，所以很容易被当成
 * 「这个文件坏了」。初始化很轻（只加载资源索引），在首次解析时做一次即可。
 */
internal object PdfText {

    @Volatile
    private var initialized = false

    /** 每页最多抽多少字符的兜底（超长文档靠 DocText.MAX_CHARS 统一裁剪）。 */
    private const val MAX_PAGES = 100

    fun extract(context: Context, uri: Uri): DocText.DocParseResult {
        ensureInit(context)

        val text = try {
            open(context, uri)?.use { input ->
                PDDocument.load(input).use { doc ->
                    if (doc.numberOfPages > MAX_PAGES) {
                        return DocText.DocParseResult.Unsupported(
                            "这是 " + doc.numberOfPages + " 页的文档，一次读不完。" +
                                "请拆分后再传（当前上限 " + MAX_PAGES + " 页）。"
                        )
                    }
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                }
            } ?: return DocText.DocParseResult.Unsupported("读不到文件内容。")
        } catch (t: Throwable) {
            return DocText.DocParseResult.Unsupported(
                "PDF 解析失败：" + (t.message ?: t.javaClass.simpleName)
            )
        }

        val trimmed = text.trim()
        // 抽出来是空的 ⇒ 这是扫描件。说清「能怎么补救」，而不是只报失败。
        if (trimmed.isEmpty()) return DocText.DocParseResult.NeedsVision

        return if (trimmed.length <= DocText.MAX_CHARS) {
            DocText.DocParseResult.Ok(trimmed, false)
        } else {
            DocText.DocParseResult.Ok(trimmed.substring(0, DocText.MAX_CHARS), true)
        }
    }

    private fun ensureInit(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    private fun open(context: Context, uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        }
}
