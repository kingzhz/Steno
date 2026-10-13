package com.snote.feature.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.snote.core.db.KbOpenHelper
import com.snote.core.search.Bigram
import com.snote.core.search.LinkIndex

/** 文档列表行。 */
data class DocSummary(
    val id: Long,
    val title: String,
    val updatedAt: Long,
    val cardCount: Int,
)

/**
 * 一张卡片的编辑草稿。
 *
 * id <= 0 表示这张卡还没落库（用户刚点「新建分节」）。保存时会被 INSERT 并回填真实 id。
 */
data class CardDraft(
    val id: Long,
    val title: String,
    val body: String,
)

/** 一条检索结果。 */
data class SearchHit(
    val cardId: Long,
    val docId: Long,
    val cardTitle: String,
    val docTitle: String,
    val snippet: String,
    val score: Double,
)

/**
 * 一篇文档的完整编辑态 —— 也是撤销/重做的最小单位。
 */
data class DocDraft(
    val id: Long,
    val title: String,
    val cards: List<CardDraft>,
)

/**
 * 笔记读写。
 *
 * ## 数据模型（v2 起翻面）
 *
 * **card 是真相源**，doc 是容器。doc.body_md 降级为导出用快照，不再是编辑入口。
 * 之所以翻面：agent 要能直接对单张卡片增删改查，若以整篇 markdown 为真相源，
 * agent 每次改一张卡都要解析并重写全文。
 *
 * ## 一次保存 = 一次全量 diff
 *
 * 命中已有 id 则 UPDATE，新卡 INSERT，库里多出来的 DELETE，全在一个事务里。
 * 编辑器允许乱序改任意一张卡，增量补丁容易漏，所以选全量 diff。
 *
 * ## 链接全自动
 *
 * 保存后调 links.rebuildAll()：用全部卡片标题做词典（Aho-Corasick），
 * 扫每张卡的正文，命中即写 link 表。用户从不手写任何链接标记。
 *
 * ## fts_card 是派生物
 *
 * 关掉「全文检索」功能时该表被整表删除，之后所有写入必须静默跳过 ——
 * 那不是错误，是正常状态。重新打开时 rebuildFts() 从 card 表重扫。
 */
class NoteRepository(context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)

    /** 供功能开关层判断索引引擎 / 做卸载重建。 */
    val dbHelper: KbOpenHelper get() = helper

    /** 链接的唯一入口。它与本类共用同一个 helper，因此写在同一事务里。 */
    private val links = LinkStore(helper)

    /** fts_card 是否存在（缓存；dropFts/rebuildFts 时更新）。 */
    @Volatile
    private var ftsPresent: Boolean? = null

    fun listDocs(): List<DocSummary> {
        val out = ArrayList<DocSummary>()
        // 标题留空时退化为第一张卡片的标题 —— 否则列表里一排「未命名」没法用。
        val sql = "SELECT d.id, " +
            "COALESCE(NULLIF(d.title, ''), " +
            "(SELECT c.title FROM card c WHERE c.doc_id = d.id AND c.title <> '' " +
            "ORDER BY c.ord LIMIT 1), '未命名'), d.updated_at, " +
            "(SELECT COUNT(*) FROM card c WHERE c.doc_id = d.id) " +
            "FROM doc d ORDER BY d.updated_at DESC"
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(DocSummary(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)))
            }
        }
        return out
    }

    /** 新建文档，并附带一张空卡片 —— 用户进去就能直接写。 */
    fun createDoc(title: String): Long {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val docValues = ContentValues().apply {
                put("title", title)
                put("body_md", "")
                put("kind", "note")
                put("created_at", now)
                put("updated_at", now)
            }
            val docId = db.insert("doc", null, docValues)

            val cardValues = ContentValues().apply {
                put("doc_id", docId)
                put("ord", 0)
                put("title", "")
                put("body_md", "")
                put("created_at", now)
                put("updated_at", now)
            }
            val cardId = db.insert("card", null, cardValues)
            if (hasFts(db)) indexCard(db, cardId, "", "")

            db.setTransactionSuccessful()
            return docId
        } finally {
            db.endTransaction()
        }
    }

    /** 载入编辑态。文档不存在返回 null；卡片为空时补一张空卡。 */
    fun loadDraft(docId: Long): DocDraft? {
        val db = helper.readableDatabase
        var title: String? = null
        db.query("doc", arrayOf("title"), "id = ?", arrayOf(docId.toString()), null, null, null)
            .use { c -> if (c.moveToFirst()) title = c.getString(0) ?: "" }
        if (title == null) return null

        val cards = ArrayList<CardDraft>()
        db.query(
            "card", arrayOf("id", "title", "body_md"), "doc_id = ?",
            arrayOf(docId.toString()), null, null, "ord ASC"
        ).use { c ->
            while (c.moveToNext()) {
                cards.add(CardDraft(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: ""))
            }
        }
        if (cards.isEmpty()) cards.add(CardDraft(0L, "", ""))
        return DocDraft(docId, title!!, cards)
    }

    /**
     * 保存编辑态，返回回填了真实卡片 id 的副本。
     *
     * 调用方必须把返回值当作新的当前状态 —— 否则第二次保存会把同一张新卡再插一遍。
     */
    fun saveDraft(draft: DocDraft): DocDraft {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            val present = hasFts(db)

            val inDb = HashSet<Long>()
            db.query(
                "card", arrayOf("id"), "doc_id = ?", arrayOf(draft.id.toString()), null, null, null
            ).use { c -> while (c.moveToNext()) inDb.add(c.getLong(0)) }

            val savedCards = ArrayList<CardDraft>(draft.cards.size)
            var ord = 0
            for (card in draft.cards) {
                val values = ContentValues().apply {
                    put("doc_id", draft.id)
                    put("ord", ord)
                    put("title", card.title)
                    put("body_md", card.body)
                    put("updated_at", now)
                }
                val realId: Long
                if (card.id > 0 && inDb.remove(card.id)) {
                    db.update("card", values, "id = ?", arrayOf(card.id.toString()))
                    realId = card.id
                } else {
                    values.put("created_at", now)
                    realId = db.insert("card", null, values)
                }
                if (present) indexCard(db, realId, card.title, card.body)
                savedCards.add(CardDraft(realId, card.title, card.body))
                ord++
            }

            for (gone in inDb) {
                if (present) db.delete("fts_card", "rowid = ?", arrayOf(gone.toString()))
                db.delete("card", "id = ?", arrayOf(gone.toString()))
            }

            val docValues = ContentValues().apply {
                put("title", draft.title)
                put("body_md", toMarkdown(draft))
                put("updated_at", now)
            }
            db.update("doc", docValues, "id = ?", arrayOf(draft.id.toString()))

            links.rebuildAll(db)

            db.setTransactionSuccessful()
            return draft.copy(cards = savedCards)
        } finally {
            db.endTransaction()
        }
    }

    fun deleteDoc(id: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val present = hasFts(db)
            if (present) {
                db.query("card", arrayOf("id"), "doc_id = ?", arrayOf(id.toString()), null, null, null)
                    .use { c ->
                        while (c.moveToNext()) {
                            db.delete("fts_card", "rowid = ?", arrayOf(c.getLong(0).toString()))
                        }
                    }
            }
            db.delete("card", "doc_id = ?", arrayOf(id.toString()))
            db.delete("doc", "id = ?", arrayOf(id.toString()))
            links.rebuildAll(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 链接（全自动，用户不参与） ----------

    /**
     * 构建全体卡片名的匹配自动机，供正文高亮使用。
     *
     * 不常驻：每次保存后重建一次，规模几百张卡时是毫秒级。
     */
    fun linkIndex(): LinkIndex = links.buildIndex(helper.readableDatabase)

    /** 谁指向了这张卡（侧边栏「指向这里」）。 */
    fun backlinks(cardId: Long): List<LinkRef> = links.backlinks(cardId)

    /** 这张卡提到了谁（侧边栏「相关内容」）。 */
    fun outlinks(cardId: Long): List<LinkRef> = links.outlinks(cardId)

    /** 供 agent / 人工显式建链，与自动链写入同一张表。 */
    fun addLink(srcCard: Long, dstCard: Long): Boolean {
        val db = helper.writableDatabase
        return links.addLink(db, srcCard, dstCard)
    }

    // ---------- 全文检索 ----------

    /** 索引当前是否存在（功能关掉时整表被删）。 */
    fun isFtsPresent(): Boolean = hasFts(helper.readableDatabase)

    /**
     * 检索卡片。
     *
     * 走 FTS 只拿候选，排序与摘要都在 Kotlin 里算 —— 这样 FTS5 / FTS4 行为一致
     * （bm25() 与 snippet() 都是 FTS5 专有，FTS4 机上会直接语法错误）。
     */
    fun search(query: String, limit: Int = 30): List<SearchHit> {
        val db = helper.readableDatabase
        if (!hasFts(db)) return emptyList()
        val expr = Bigram.matchExpression(query)
        if (expr.isEmpty()) return emptyList()

        val tokens = Bigram.tokenize(query).split(' ').filter { it.isNotEmpty() }
        val out = ArrayList<SearchHit>()

        val sql = "SELECT c.id, c.doc_id, c.title, d.title, c.body_md " +
            "FROM fts_card JOIN card c ON c.id = fts_card.rowid " +
            "JOIN doc d ON d.id = c.doc_id " +
            "WHERE fts_card MATCH ? LIMIT 200"
        db.rawQuery(sql, arrayOf(expr)).use { c ->
            while (c.moveToNext()) {
                val cardTitle = c.getString(2) ?: ""
                val docTitle = c.getString(3) ?: ""
                val body = c.getString(4) ?: ""
                out.add(
                    SearchHit(
                        cardId = c.getLong(0),
                        docId = c.getLong(1),
                        cardTitle = cardTitle,
                        docTitle = docTitle,
                        snippet = makeSnippet(body, tokens),
                        score = scoreOf(cardTitle, body, tokens)
                    )
                )
            }
        }
        return out.sortedByDescending { it.score }.take(limit)
    }

    /** 标题命中权重更高：找的是「那张叫这个名字的卡」，不是正文里顺带提过。 */
    private fun scoreOf(title: String, body: String, tokens: List<String>): Double {
        var s = 0.0
        for (t in tokens) {
            if (title.contains(t, ignoreCase = true)) s += 8.0
            s += countOccurrences(body, t) * 1.0
        }
        return s
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var i = 0
        var n = 0
        while (true) {
            val at = haystack.indexOf(needle, i, ignoreCase = true)
            if (at < 0) break
            n++
            i = at + needle.length
        }
        return n
    }

    /** 摘一段命中附近的正文。命中在开头时直接取前 120 字。 */
    private fun makeSnippet(body: String, tokens: List<String>): String {
        val flat = body.replace('\n', ' ').trim()
        if (flat.isEmpty()) return ""
        var at = -1
        for (t in tokens) {
            val i = flat.indexOf(t, ignoreCase = true)
            if (i >= 0 && (at < 0 || i < at)) at = i
        }
        if (at < 0) return flat.take(120)
        val from = (at - 24).coerceAtLeast(0)
        val to = (from + 120).coerceAtMost(flat.length)
        return (if (from > 0) "…" else "") + flat.substring(from, to) +
            (if (to < flat.length) "…" else "")
    }

    // ---------- 检索索引 ----------

    /** 全文检索索引整表重建（对应「全文检索」功能重新打开）。 */
    fun rebuildFts() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            KbSchemaIndex.ensure(db)
            db.execSQL("DELETE FROM fts_card")
            db.query("card", arrayOf("id", "title", "body_md"), null, null, null, null, null)
                .use { c ->
                    while (c.moveToNext()) {
                        indexCard(db, c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "")
                    }
                }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ftsPresent = true
    }

    /** 卸载检索索引（对应功能关闭）。源数据不动，重新打开后 rebuildFts() 即可恢复。 */
    fun dropFts() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DROP TABLE IF EXISTS fts_card")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ftsPresent = false
        helper.vacuum()
    }

    fun ftsRowCount(): Int {
        val db = helper.readableDatabase
        if (!hasFts(db)) return 0
        return db.rawQuery("SELECT COUNT(*) FROM fts_card", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    // ---------- 内部 ----------

    private fun hasFts(db: SQLiteDatabase): Boolean {
        ftsPresent?.let { return it }
        val present = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name='fts_card'", null
        ).use { it.moveToFirst() }
        ftsPresent = present
        return present
    }

    /** fts_card 用 card.id 当 rowid，便于按卡片精确增删。 */
    private fun indexCard(db: SQLiteDatabase, cardId: Long, title: String, body: String) {
        val values = ContentValues().apply {
            put("rowid", cardId)
            put("title", Bigram.tokenize(title))
            put("body_tok", Bigram.tokenize(body))
        }
        db.insertWithOnConflict("fts_card", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /**
     * 删除某一类派生物（derived 表 + 对应的内容）。
     *
     * 只是删账本行还是连内容一起删，由 kind 决定 —— 目前两类派生物：
     *   thumb  缩略图文件在 files/thumbs，账本在 derived
     *   ocr    识别结果直接存在 derived 表里（没有独立表，量小）
     *
     * 返回删除的行数。
     */
    fun deleteDerived(kind: String): Int =
        helper.writableDatabase.delete("derived", "kind = ?", arrayOf(kind))

    /** 把卡片拼回 markdown，存入 doc.body_md 作为导出快照，不参与写入路径。 */
    private fun toMarkdown(draft: DocDraft): String {
        val sb = StringBuilder()
        for (card in draft.cards) {
            val t = card.title.trim()
            val b = card.body.trim()
            if (t.isEmpty() && b.isEmpty()) continue
            if (t.isNotEmpty()) sb.append("## ").append(t).append('\n')
            if (b.isNotEmpty()) sb.append(b).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

/**
 * 索引表的按需创建。
 *
 * 必须复用 KbSchema.createSearchIndex 的探测逻辑，不能自己硬写 fts5：
 * 有些机型的 SQLite 没编 FTS5，建库时已回退成 FTS4，这里再建 fts5 会直接抛错。
 */
private object KbSchemaIndex {
    fun ensure(db: SQLiteDatabase) {
        com.snote.core.db.KbSchema.createSearchIndex(db)
    }
}
