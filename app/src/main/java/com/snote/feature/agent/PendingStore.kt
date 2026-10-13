package com.snote.feature.agent

import android.content.ContentValues
import android.content.Context
import com.snote.core.db.KbOpenHelper

/** 一条待用户审批的写操作。 */
data class PendingOp(
    val id: Long,
    val sessionId: Long,
    val tool: String,
    val argsJson: String,
    val summary: String,
    val createdAt: Long,
)

/**
 * 待审写入的存储。
 *
 * ## 为什么存「调用」而不是「结果」
 *
 * 审批通过后要执行的是**当初那次调用**，而不是把当时的预览结果写回去。
 * 若存结果，用户可能审批了 10 分钟前的旧内容，而知识库期间已被别处改动，
 * 结果就是「预览说改 A、实际改了 B」。存调用则执行时重新读库，永远一致。
 *
 * ## 生命周期
 *
 * 排队 → 用户点同意（执行后删除）或拒绝（直接删除）。没有「已执行」状态：
 * 执行完就没了，历史留痕在对话里，不需要再存一份。
 */
class PendingStore(context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)

    fun add(sessionId: Long, tool: String, argsJson: String, summary: String): Long {
        val values = ContentValues().apply {
            put("session_id", sessionId)
            put("tool", tool)
            put("args_json", argsJson)
            put("summary", summary)
            put("created_at", System.currentTimeMillis())
        }
        return helper.writableDatabase.insert("pending_op", null, values)
    }

    /** 全部待审项，最早的在前（按提出顺序审更符合直觉）。 */
    fun list(): List<PendingOp> {
        val out = ArrayList<PendingOp>()
        helper.readableDatabase.query(
            "pending_op", arrayOf("id", "session_id", "tool", "args_json", "summary", "created_at"),
            null, null, null, null, "created_at ASC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    PendingOp(
                        id = c.getLong(0),
                        sessionId = c.getLong(1),
                        tool = c.getString(2) ?: "",
                        argsJson = c.getString(3) ?: "{}",
                        summary = c.getString(4) ?: "",
                        createdAt = c.getLong(5),
                    )
                )
            }
        }
        return out
    }

    fun count(): Int = list().size

    fun get(id: Long): PendingOp? = list().firstOrNull { it.id == id }

    fun remove(id: Long) {
        helper.writableDatabase.delete("pending_op", "id = ?", arrayOf(id.toString()))
    }

    fun clear() {
        helper.writableDatabase.delete("pending_op", null, null)
    }
}
