package com.snote.feature.notes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.snote.core.db.KbOpenHelper
import com.snote.core.search.LinkIndex

/** 侧边栏要展示的一条关系。 */
data class LinkRef(
    val cardId: Long,
    val docId: Long,
    val cardTitle: String,
    val docTitle: String,
)

/**
 * 链接的读写。
 *
 * ## 链接不是用户建的
 *
 * 用户从不写 [[标记]]。保存卡片时把正文扫一遍，命中别的卡片名就自动写 link 表 ——
 * 这是本 App 相对普通笔记软件的核心差异。link 属源数据：
 * 关掉「双链与跳转」功能也不删除，只是侧边栏不展示。
 *
 * ## 三种 kind
 *
 *   auto  —— 软件自动扫描生成，每次 rebuildAll 可整批删除重算；
 *   agent —— agent 自己建的，rebuildAll 不碰；
 *   user  —— 用户确认建的，rebuildAll 不碰。
 *
 * 分开存是为了让重建幂等：重算只删自己那一类，不会把 agent 的成果冲掉。
 */
class LinkStore(private val helper: KbOpenHelper) {

    companion object {
        const val KIND_AUTO = "auto"
        const val KIND_AGENT = "agent"
        const val KIND_USER = "user"

        /** 标题短于这个长度不参与自动链接：单字噪音太大（「的」「了」都会链出去）。 */
        const val MIN_TERM = 2
    }

    /**
     * 用全部卡片标题构建匹配自动机。
     *
     * 刻意只用**标题**，不用别名：别名放开后普通词（「设置」「内容」）会造成大量误链。
     * 等真出现漏链再加白名单。
     */
    fun buildIndex(db: SQLiteDatabase): LinkIndex {
        val entries = ArrayList<LinkIndex.Entry>()
        db.query("card", arrayOf("id", "doc_id", "title"), null, null, null, null, null)
            .use { c ->
                while (c.moveToNext()) {
                    val t = (c.getString(2) ?: "").trim()
                    if (t.length < MIN_TERM) continue
                    entries.add(LinkIndex.Entry(t, c.getLong(0), c.getLong(1)))
                }
            }
        return LinkIndex(entries)
    }

    /**
     * 全量重建自动链接。
     *
     * 只删 kind='auto' 的行，agent/user 建的链接原样保留。
     * 几百张卡的规模下全量重算比增量简单，而且不会漏 —— 增量要处理
     * 「改标题导致已失效的旧链」这类边界，容易留下脏数据。
     */
    fun rebuildAll(db: SQLiteDatabase) {
        db.delete("link", "kind = ?", arrayOf(KIND_AUTO))
        val index = buildIndex(db)
        if (index.size == 0) return
        db.query("card", arrayOf("id", "title", "body_md"), null, null, null, null, null)
            .use { c ->
                while (c.moveToNext()) {
                    val srcId = c.getLong(0)
                    val text = (c.getString(1) ?: "") + "\n" + (c.getString(2) ?: "")
                    val hits = index.scan(text, selfCardId = srcId)
                    if (hits.isEmpty()) continue
                    val seen = HashSet<Long>()
                    for (h in hits) {
                        if (!seen.add(h.cardId)) continue
                        val values = ContentValues().apply {
                            put("src_card", srcId)
                            put("dst_card", h.cardId)
                            put("kind", KIND_AUTO)
                        }
                        db.insertWithOnConflict("link", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                    }
                }
            }
    }

    /** 显式建链的唯一入口（agent / 用户确认）。返回是否真的插入了新行。 */
    fun addLink(db: SQLiteDatabase, srcCard: Long, dstCard: Long, kind: String = KIND_USER): Boolean {
        if (srcCard == dstCard || srcCard <= 0 || dstCard <= 0) return false
        val values = ContentValues().apply {
            put("src_card", srcCard)
            put("dst_card", dstCard)
            put("kind", kind)
        }
        return db.insertWithOnConflict("link", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0
    }

    fun removeLink(db: SQLiteDatabase, srcCard: Long, dstCard: Long, kind: String = KIND_USER) {
        db.delete(
            "link", "src_card = ? AND dst_card = ? AND kind = ?",
            arrayOf(srcCard.toString(), dstCard.toString(), kind)
        )
    }

    /** 指向这张卡片的其他卡片。 */
    fun backlinks(cardId: Long): List<LinkRef> =
        queryRefs(
            "SELECT c.id, c.doc_id, c.title, d.title FROM link l " +
                "JOIN card c ON c.id = l.src_card JOIN doc d ON d.id = c.doc_id " +
                "WHERE l.dst_card = ? GROUP BY c.id ORDER BY d.updated_at DESC",
            cardId
        )

    /** 这张卡片正文提到的其他卡片。 */
    fun outlinks(cardId: Long): List<LinkRef> =
        queryRefs(
            "SELECT c.id, c.doc_id, c.title, d.title FROM link l " +
                "JOIN card c ON c.id = l.dst_card JOIN doc d ON d.id = c.doc_id " +
                "WHERE l.src_card = ? GROUP BY c.id ORDER BY d.updated_at DESC",
            cardId
        )

    // ---------- 内部 ----------

    private fun queryRefs(sql: String, cardId: Long): List<LinkRef> {
        if (cardId <= 0) return emptyList()
        val out = ArrayList<LinkRef>()
        helper.readableDatabase.rawQuery(sql, arrayOf(cardId.toString())).use { c ->
            while (c.moveToNext()) {
                out.add(
                    LinkRef(
                        c.getLong(0),
                        c.getLong(1),
                        c.getString(2) ?: "",
                        c.getString(3) ?: ""
                    )
                )
            }
        }
        return out
    }
}
