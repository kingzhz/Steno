package com.snote.feature.notes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.snote.core.db.KbOpenHelper

/** 一个标签及其使用次数。 */
data class TagCount(val id: Long, val name: String, val count: Int)

/**
 * 标签与别名。
 *
 * ## 两者的区别（容易被混为一谈）
 *
 *   标签（tag）  —— 挂在**卡片**上，用于分组与筛选。一个卡片可以有多个标签。
 *   别名（alias）—— 挂在**卡片**上，是这张卡的**其他叫法**（如「PLC」是
 *                  「可编程逻辑控制器」的别名），供侧边栏匹配与将来的分词词典用。
 *
 * ## 别名为什么不参与自动链接
 *
 * LinkStore.buildIndex 刻意只用标题：别名一旦放开，「设置」「内容」这类
 * 普通词会造成大量误链。别名先只做**展示与手动跳转**，等真出现漏链再加白名单。
 *
 * ## 标签是源数据
 *
 * 关掉相关功能只让入口消失，不删 tag / card_tag / alias 里的行。
 */
class TagStore(private val helper: KbOpenHelper) {

    // ---------- 标签 ----------

    /** 全部标签及使用次数，按次数降序（常用的在前）。 */
    fun listTags(): List<TagCount> {
        val out = ArrayList<TagCount>()
        val sql = "SELECT t.id, t.name, " +
            "(SELECT COUNT(*) FROM card_tag ct WHERE ct.tag_id = t.id) " +
            "FROM tag t ORDER BY 3 DESC, t.name ASC"
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(TagCount(c.getLong(0), c.getString(1) ?: "", c.getInt(2)))
            }
        }
        return out
    }

    /** 某张卡片的标签名。 */
    fun tagsOf(cardId: Long): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT t.name FROM card_tag ct JOIN tag t ON t.id = ct.tag_id " +
                "WHERE ct.card_id = ? ORDER BY t.name",
            arrayOf(cardId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) ?: "") }
        return out
    }

    /**
     * 给卡片打标签（已存在则忽略）。
     *
     * 标签名做 trim，空名直接拒绝 —— 空标签在界面上是个看不见的点，用户会以为出 bug。
     */
    fun addTag(cardId: Long, name: String): Boolean {
        val n = name.trim()
        if (cardId <= 0 || n.isEmpty()) return false
        val db = helper.writableDatabase
        val tagId = ensureTag(db, n)
        if (tagId <= 0) return false
        val values = ContentValues().apply {
            put("card_id", cardId)
            put("tag_id", tagId)
        }
        return db.insertWithOnConflict("card_tag", null, values, SQLiteDatabase.CONFLICT_IGNORE) >= 0
    }

    fun removeTag(cardId: Long, name: String) {
        val n = name.trim()
        if (cardId <= 0 || n.isEmpty()) return
        val db = helper.writableDatabase
        db.delete(
            "card_tag", "card_id = ? AND tag_id = (SELECT id FROM tag WHERE name = ?)",
            arrayOf(cardId.toString(), n)
        )
    }

    /** 删除整个标签（从所有卡片上摘掉）。返回被摘掉的卡片数。 */
    fun deleteTag(name: String): Int {
        val n = name.trim()
        if (n.isEmpty()) return 0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val tagId = tagIdOf(db, n) ?: return 0
            val removed = db.delete("card_tag", "tag_id = ?", arrayOf(tagId.toString()))
            db.delete("tag", "id = ?", arrayOf(tagId.toString()))
            db.setTransactionSuccessful()
            return removed
        } finally {
            db.endTransaction()
        }
    }

    /** 带某个标签的全部卡片。 */
    fun cardsWithTag(name: String): List<Long> {
        val out = ArrayList<Long>()
        helper.readableDatabase.rawQuery(
            "SELECT ct.card_id FROM card_tag ct JOIN tag t ON t.id = ct.tag_id WHERE t.name = ?",
            arrayOf(name.trim())
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    // ---------- 别名 ----------

    fun aliasesOf(cardId: Long): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.query(
            "alias", arrayOf("text"), "card_id = ?", arrayOf(cardId.toString()),
            null, null, "text"
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0) ?: "") }
        return out
    }

    fun addAlias(cardId: Long, text: String): Boolean {
        val t = text.trim()
        if (cardId <= 0 || t.isEmpty()) return false
        val db = helper.writableDatabase
        // 同卡同别名不重复；alias 表没有唯一约束，所以先查一次。
        var exists = false
        db.query(
            "alias", arrayOf("text"), "card_id = ? AND text = ?",
            arrayOf(cardId.toString(), t), null, null, null
        ).use { c -> exists = c.moveToFirst() }
        if (exists) return false
        return db.insert("alias", null, ContentValues().apply {
            put("card_id", cardId)
            put("text", t)
        }) >= 0
    }

    fun removeAlias(cardId: Long, text: String) {
        helper.writableDatabase.delete(
            "alias", "card_id = ? AND text = ?",
            arrayOf(cardId.toString(), text.trim())
        )
    }

    /**
     * 按别名找卡片（侧边栏跳转用）。
     *
     * 只按**完全相等**匹配，不做包含匹配 —— 否则「PLC」会命中「PLC 编程入门」，
     * 用户点「PLC」却跳到别的卡片会莫名其妙。
     */
    fun cardsByAlias(text: String): List<Long> {
        val out = ArrayList<Long>()
        helper.readableDatabase.query(
            "alias", arrayOf("card_id"), "text = ?", arrayOf(text.trim()),
            null, null, null
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out
    }

    /** 全部别名，供分词词典与侧边栏匹配。 */
    fun allAliases(): List<Pair<String, Long>> {
        val out = ArrayList<Pair<String, Long>>()
        helper.readableDatabase.query(
            "alias", arrayOf("text", "card_id"), null, null, null, null, null
        ).use { c -> while (c.moveToNext()) out.add((c.getString(0) ?: "") to c.getLong(1)) }
        return out
    }

    // ---------- 内部 ----------

    /** 取标签 id，不存在则建。返回 0 表示失败。 */
    private fun ensureTag(db: SQLiteDatabase, name: String): Long {
        tagIdOf(db, name)?.let { return it }
        val id = db.insertWithOnConflict(
            "tag", null, ContentValues().apply { put("name", name) },
            SQLiteDatabase.CONFLICT_IGNORE
        )
        // 并发下可能别人刚插进去，INSERT IGNORE 返回 -1，再查一次。
        return if (id > 0) id else (tagIdOf(db, name) ?: 0L)
    }

    private fun tagIdOf(db: SQLiteDatabase, name: String): Long? {
        db.query("tag", arrayOf("id"), "name = ?", arrayOf(name), null, null, null).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }
}
