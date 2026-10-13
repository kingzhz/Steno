package com.snote.feature.chat

import android.content.ContentValues
import android.content.Context
import com.snote.core.db.KbOpenHelper
import org.json.JSONArray

/** 会话列表里的一行。 */
data class ChatSessionSummary(
    val id: Long,
    val title: String,
    val model: String,
    val updatedAt: Long,
    val messageCount: Int,
)

/**
 * 对话历史持久化。
 *
 * ## 为什么对话也要落库
 *
 * 它是**源数据**：用户自己说的话，重来一遍不可能一模一样。所以关掉 agent 功能时
 * 只让入口不可用，绝不删这里的行（与 fts_card 那种派生物刚好相反）。
 *
 * ## 一条消息 = 一次写入
 *
 * 流式回复是逐段到达的，但只在**收完整条**之后落一次盘。中途每段都写会让
 * 一次对话产生几十次 INSERT，而中途崩溃丢掉半句话并无损失 —— 用户重问即可。
 *
 * ## 删除会话 = 级联删消息
 *
 * 靠外键 ON DELETE CASCADE（KbOpenHelper.onConfigure 已开
 * setForeignKeyConstraintsEnabled）。对话不是知识，没有留存价值。
 */
class ChatStore(context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)

    // ---------- 会话 ----------

    /** 会话列表，最近更新的在前。 */
    fun listSessions(): List<ChatSessionSummary> {
        val out = ArrayList<ChatSessionSummary>()
        val sql = "SELECT s.id, s.title, s.model, s.updated_at, " +
            "(SELECT COUNT(*) FROM chat_message m WHERE m.session_id = s.id) " +
            "FROM chat_session s ORDER BY s.updated_at DESC"
        helper.readableDatabase.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatSessionSummary(
                        c.getLong(0),
                        c.getString(1) ?: "",
                        c.getString(2) ?: "",
                        c.getLong(3),
                        c.getInt(4),
                    )
                )
            }
        }
        return out
    }

    fun createSession(title: String, model: String): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("title", title)
            put("model", model)
            put("created_at", now)
            put("updated_at", now)
        }
        return helper.writableDatabase.insert("chat_session", null, values)
    }

    fun renameSession(id: Long, title: String) {
        val values = ContentValues().apply { put("title", title) }
        helper.writableDatabase.update("chat_session", values, "id = ?", arrayOf(id.toString()))
    }

    fun touchSession(id: Long, model: String) {
        val values = ContentValues().apply {
            put("model", model)
            put("updated_at", System.currentTimeMillis())
        }
        helper.writableDatabase.update("chat_session", values, "id = ?", arrayOf(id.toString()))
    }

    /**
     * 删除会话。消息由外键级联删除。
     *
     * 显式先删消息再删会话：外键级联依赖 onConfigure 里的开关，万一某个路径下
     * 没开，残留的消息行会变成永远查不到的垃圾（孤儿行）。
     */
    fun deleteSession(id: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("chat_message", "session_id = ?", arrayOf(id.toString()))
            db.delete("chat_session", "id = ?", arrayOf(id.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---------- 消息 ----------

    fun loadMessages(sessionId: Long): List<ChatLine> {
        val out = ArrayList<ChatLine>()
        helper.readableDatabase.query(
            "chat_message", arrayOf("role", "content", "op_id", "thumb_json"), "session_id = ?",
            arrayOf(sessionId.toString()), null, null, "ord ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatLine(
                        role = c.getString(0) ?: "user",
                        text = c.getString(1) ?: "",
                        // 待审卡片的身份必须跟着消息一起回来：没有它，重载后
                        // 就没法查 pending_op 判断这张卡是不是已被处理过。
                        opId = c.getLong(2),
                        // 历史里回显的是缩略图（原图没存）。
                        thumbs = decodeThumbs(c.getString(3)),
                    )
                )
            }
        }
        return out
    }

    /**
     * 解析缩略图数组。
     *
     * 存成 JSON 数组字符串而不是另开一张表：一条消息的图片永远是「跟这条消息
     * 同生共死」的，没有独立查询需求，拆表只会多一次 join。损坏时静默返回
     * 空列表 —— 历史里少显示几张图，不该让整个会话打不开。
     */
    private fun decodeThumbs(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 覆盖式保存整段对话。
     *
     * 与编辑器同理选全量重写而非增量追加：一次对话只有几十行，重写成本可忽略，
     * 而增量追加要额外维护「本地已写到第几条」的状态，一旦对不上就重影。
     */
    fun saveMessages(sessionId: Long, lines: List<ChatLine>) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.delete("chat_message", "session_id = ?", arrayOf(sessionId.toString()))
            val now = System.currentTimeMillis()
            lines.forEachIndexed { i, line ->
                db.insert("chat_message", null, ContentValues().apply {
                    put("session_id", sessionId)
                    put("ord", i)
                    put("role", line.role)
                    put("content", line.text)
                    put("op_id", line.opId)
                    // 只存缩略图。原图（line.images）是这个进程内的临时物，
                    // 下次启动就该没了 —— 模型靠它识图，历史不靠它。
                    put("thumb_json", if (line.thumbs.isEmpty()) "" else JSONArray(line.thumbs).toString())
                    put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * 首条用户消息的前若干字作标题。
     *
     * 取不到就返回空串 —— 列表层负责显示「未命名对话」，不要把默认文案
     * 写进库里，否则用户改了文案也追不回来。
     */
    fun autoTitle(firstUserText: String): String {
        val t = firstUserText.trim().replace('\n', ' ')
        return if (t.length <= TITLE_MAX) t else t.substring(0, TITLE_MAX) + "…"
    }

    /** 判断某会话是否已有标题（避免每轮都重算）。 */
    fun hasTitle(sessionId: Long): Boolean {
        helper.readableDatabase.query(
            "chat_session", arrayOf("title"), "id = ?", arrayOf(sessionId.toString()),
            null, null, null
        ).use { c -> return c.moveToFirst() && (c.getString(0) ?: "").isNotBlank() }
    }

    companion object {
        const val TITLE_MAX = 18
    }
}
