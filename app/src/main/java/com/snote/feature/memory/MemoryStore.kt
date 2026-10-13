package com.snote.feature.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.snote.core.db.KbOpenHelper

/** 一条全局记忆。 */
data class Memory(
    val id: Long,
    val text: String,
    /** 'user' = 用户自己写的；'agent' = 模型申请、用户批准后写入的。 */
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * 全局记忆的存取。
 *
 * ## 为什么它「全局」
 *
 * 记忆不属于任何一次会话 —— 它跨会话生效，每一轮对话都注入 system 提示词。
 * 这正是它与对话历史的根本区别：历史是「某次聊过什么」，记忆是
 * 「关于这个人，长期成立的事实」。
 *
 * ## 为什么要限量
 *
 * 记忆是**每轮都要注入**的。没有上限的话，它会长到把上下文吃掉一大块，
 * 而且稀释模型对当前问题的注意力 —— 而用户完全感知不到，只会觉得
 * 「最近回答怎么变差了」。上限在这里不是为了省 token 而是为了可诊断：
 * 撞上限时明确提示用户清理，好过悄悄劣化。
 */
class MemoryStore(context: Context, private val helper: KbOpenHelper = KbOpenHelper(context)) {

    /** 全部记忆，最早写的在前（阅读顺序稳定，且新条目追加在末尾不会跳位）。 */
    fun list(): List<Memory> {
        val out = ArrayList<Memory>()
        helper.readableDatabase.query(
            "memory", arrayOf("id", "text", "source", "created_at", "updated_at"),
            null, null, null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Memory(
                        id = c.getLong(0),
                        text = c.getString(1) ?: "",
                        source = c.getString(2) ?: SOURCE_USER,
                        createdAt = c.getLong(3),
                        updatedAt = c.getLong(4),
                    )
                )
            }
        }
        return out
    }

    fun count(): Int = list().size

    /** 总字符数。上限按字符算而不是按条数 —— 一句话和一段话的代价差几十倍。 */
    fun totalChars(): Int = list().sumOf { it.text.length }

    fun add(text: String, source: String = SOURCE_USER): Long {
        val now = System.currentTimeMillis()
        return helper.writableDatabase.insert(
            "memory", null,
            ContentValues().apply {
                put("text", text.trim())
                put("source", source)
                put("created_at", now)
                put("updated_at", now)
            }
        )
    }

    fun update(id: Long, text: String) {
        helper.writableDatabase.update(
            "memory",
            ContentValues().apply {
                put("text", text.trim())
                // 编辑后转为用户来源：用户改过的话就是用户的话，
                // 留着 'agent' 会让人以为是模型写的，反而误导。
                put("source", SOURCE_USER)
                put("updated_at", System.currentTimeMillis())
            },
            "id = ?", arrayOf(id.toString())
        )
    }

    fun remove(id: Long) {
        helper.writableDatabase.delete("memory", "id = ?", arrayOf(id.toString()))
    }

    /**
     * 判断「这句话是不是已经记过了」。
     *
     * 用去空白后完全相同来判重（不做模糊匹配）：模型每次措辞都可能不同，
     * 模糊判重会误杀真正不同的记忆。宁可让它偶尔重复申请、由用户拒掉，
     * 也不要悄悄吞掉一条用户其实想留的记忆。
     */
    fun contains(text: String): Boolean {
        val t = text.trim()
        return list().any { it.text.trim() == t }
    }

    /**
     * 拼成注入 system 提示词的那一段。
     *
     * 返回空串表示没有记忆 —— 调用方据此**完全不插入这一节**，
     * 而不是插一个「（暂无记忆）」的空标题：空标题会让模型以为
     * 这里本该有内容却缺失了。
     */
    fun promptSection(): String {
        val all = list()
        if (all.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("## 关于用户（长期记忆）\n")
        sb.append("以下是用户与你在过往对话中确认过的事实，请在回答时遵循：\n")
        all.forEach { sb.append("- ").append(it.text).append('\n') }
        sb.append('\n')
        return sb.toString()
    }

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_AGENT = "agent"

        /**
         * 记忆总字符上限。
         *
         * 2000 字约 1000-1500 token，占多数模型窗口的千分之一量级 ——
         * 足够记几十条，又不会挤占对话本身。撞上限时界面明确提示清理。
         */
        const val MAX_CHARS = 2000
    }
}
