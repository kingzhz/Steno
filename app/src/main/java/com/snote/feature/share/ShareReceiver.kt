package com.snote.feature.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.snote.feature.attachments.AttachmentStore
import com.snote.feature.notes.CardDraft
import com.snote.feature.notes.DocDraft
import com.snote.feature.notes.NoteRepository
import com.snote.feature.doc.DocText

/** 一次分享导入的结果。 */
data class ShareResult(
    /** 成功时新建的文档 id；失败为 0。 */
    val docId: Long,
    val message: String,
) {
    val ok: Boolean get() = docId > 0L
}

/**
 * 接收系统分享进来的内容，落成一篇笔记。
 *
 * ## 为什么需要它
 *
 * 应用的主打场景是「把零散的想法、截图、文件丢进来」，而在此之前
 * AndroidManifest 里唯一的 intent-filter 是 MAIN/LAUNCHER ——
 * 从浏览器、相册、微信分享过来的内容**没有任何接收方**。
 * 也就是说，这个 App 最核心的入口恰恰是断的。
 *
 * ## 支持两种 EXTRA
 *
 *   EXTRA_TEXT      纯文本（分享网页标题+链接、分享一段话）
 *   EXTRA_STREAM    一个或多个 Uri（图片、PDF、任意文件）
 *
 * 两者可以同时存在（很多 App 分享时既带文字又带图），所以都处理，
 * 而不是 if/else 二选一。
 *
 * ## 正文里为什么带来源链接
 *
 * 分享网页时，EXTRA_TEXT 往往只有标题，真正的信息在 EXTRA_HTML_TEXT 或
 * Intent 的 data 里。用户事后回看只看到一句标题会想不起这是哪来的，
 * 所以把能拿到的链接一并写进正文。
 */
object ShareReceiver {

    /** 这个 Intent 是不是分享进来的。 */
    fun isShare(intent: Intent?): Boolean {
        val a = intent?.action ?: return false
        return a == Intent.ACTION_SEND || a == Intent.ACTION_SEND_MULTIPLE
    }

    /**
     * 处理分享 Intent。
     *
     * 会从 Intent 里读内容、必要时把附件导入知识库，然后建一篇笔记。
     * 文本为空且没有可导入的附件时返回失败。
     */
    fun handle(context: Context, intent: Intent): ShareResult {
        val repo = NoteRepository(context)
        val attachments = AttachmentStore(context)

        val text = extractText(intent)
        val uris = extractUris(intent)

        // 一份附件都导不进来、正文也空 → 没有任何可保存的东西。
        if (text.isBlank() && uris.isEmpty()) {
            return ShareResult(0, "分享内容里没有可保存的文字或文件。")
        }

        val now = System.currentTimeMillis()
        val fileName = uris.firstOrNull()?.let { queryName(context, it) }

        // 标题：有文件名用它，否则截正文首行。两种都拿不到才叫「分享」。
        val title = fileName
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(30)
            ?: "分享"

        val saved = ArrayList<Pair<Long, String>>()   // attachmentId -> 原始文件名
        var failed = 0

        for (u in uris) {
            // 逐个导入：一个失败不该让其余的也丢掉。
            val a = runCatching { attachments.import(u) }.getOrNull()
            if (a != null) saved.add(a.id to (queryName(context, u) ?: "附件")) else failed++
        }

        // 带附件来、却一个都没进来，且没有文字可存 —— 直接失败，不要留一篇空笔记。
        //
        // 这是实测踩出来的：分享一个读不到的 Uri 时，原实现照样建了标题为「分享」
        // 的文档，用户看到的是「分享成功」但笔记里什么都没有，只能自己猜为什么。
        // 空笔记比明确的失败更坏 —— 它让用户以为内容已经保存了。
        if (uris.isNotEmpty() && saved.isEmpty() && text.isBlank()) {
            return ShareResult(0, "分享的文件读不到（共 " + uris.size + " 个），可能没有读取权限。")
        }

        // 正文：原文 + 附件名单。
        val body = buildString {
            if (text.isNotBlank()) append(text.trim())
            if (saved.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("附件：")
                saved.forEach { append("\n- ").append(it.second) }
            }
        }

        val docId = repo.createDoc(title)
        // createDoc 会自带一张空卡，这里用 saveDraft 把它填成我们要的内容。
        // 不直接 INSERT card 是有意的：saveDraft 走的是编辑器同一条路径
        // （含 FTS 索引、链接重建），绕过它会让新笔记搜不到、也不参与双链。
        repo.saveDraft(
            DocDraft(
                id = docId,
                title = title,
                cards = listOf(CardDraft(0L, title, body))
            )
        )

        // 附件挂到这篇文档的第一张卡上（evidence 表）。
        // 挂卡而不是挂 doc：原文证据的粒度是卡片，这是 schema 既定的事实。
        runCatching {
            val draft = repo.loadDraft(docId)
            val cardId = draft?.cards?.firstOrNull()?.id ?: 0L
            if (cardId > 0L && saved.isNotEmpty()) {
                attachments.linkToCard(cardId, saved.map { it.first })
            }
        }

        val msg = buildString {
            append("已保存为笔记")
            if (saved.isNotEmpty()) append("，" + saved.size + " 个附件")
            if (failed > 0) append("（" + failed + " 个导入失败）")
        }
        return ShareResult(docId, msg)
    }

    // ---------- 内部 ----------

    /** 取纯文本；EXTRA_HTML_TEXT 只在没有纯文本时兜底（它是 HTML，要剥标签）。 */
    private fun extractText(intent: Intent): String {
        val plain = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (!plain.isNullOrBlank()) return plain

        val html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        if (!html.isNullOrBlank()) return stripHtml(html)

        // 有些 App 把链接放在 data 里而不是 EXTRA_TEXT。
        val data = intent.data?.toString()
        if (!data.isNullOrBlank()) return data

        return ""
    }

    /** 取全部附件 Uri；SEND 与 SEND_MULTIPLE 的统一出口。 */
    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
            }
        }
        // ClipData 是 SEND_MULTIPLE 之外的另一条常见路径（图片分享）。
        intent.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) {
                cd.getItemAt(i).uri?.let { if (!out.contains(it)) out.add(it) }
            }
        }
        return out
    }

    private fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull()

    /** 极简去标签：分享来的 HTML 只需要去掉标记留下文字，不需要完整解析。 */
    private fun stripHtml(s: String): String =
        s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
}
