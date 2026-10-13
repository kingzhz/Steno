package com.snote.core.settings

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.snote.core.db.KbOpenHelper

/**
 * 用户选择的持久化（落在 setting 表，key 形如 `choice.<name>`）。
 *
 * 与 FeatureStateStore 的区别：那个存「功能开关」，这个存「用户偏好」
 * （选了哪个模型、上次开的是哪个会话）。两者共用 setting 表但前缀不同，
 * 互不干扰。
 *
 * 为什么不加内存缓存：这些值一次查询就出，且必须「改完立刻生效」——
 * 缓存反而要处理失效，得不偿失。
 */
class ChoiceStore(context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)

    fun get(name: String): String? {
        helper.readableDatabase.query(
            "setting", arrayOf("value"), "key = ?", arrayOf(key(name)), null, null, null
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    fun put(name: String, value: String) {
        helper.writableDatabase.insertWithOnConflict(
            "setting", null,
            ContentValues().apply {
                put("key", key(name))
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun remove(name: String) {
        helper.writableDatabase.delete("setting", "key = ?", arrayOf(key(name)))
    }

    private fun key(name: String) = "choice." + name

    companion object {
        /** 当前选中的模型 id。 */
        const val MODEL = "model"

        /** 上次打开的会话 id（0 / 缺失 = 新会话）。 */
        const val LAST_SESSION = "last_session"

        /** 底色预设的枚举名（ThemePreset.name）。 */
        const val THEME_PRESET = "theme_preset"

        /** 强调色枚举名（Accent.name）。 */
        const val THEME_ACCENT = "theme_accent"

        /**
         * 对话输入框里**还没发出去**的草稿。
         *
         * 为什么要落库而不是只放内存：底部 Tab 切换会让 ChatScreen 离开组合，
         * 所有 remember 状态被丢弃（见 ChatScreen.restoreLastSession 的注释）。
         * 用户在输入框里写了一半切去记笔记，回来发现字没了 —— 这是真实的丢数据，
         * 不是「状态没保住」的小毛病。落库还顺带覆盖了「App 被杀掉重开」。
         */
        const val CHAT_DRAFT = "chat_draft"

        /**
         * 识图模型。
         *
         * 当前模型不支持图片时，含图的那一轮**临时**换用它，下一轮自动换回。
         * 存 id 而不是「用/不用」布尔值：能识图的模型有一二十个，用户该能自己挑。
         */
        const val VISION_MODEL = "vision_model"

        /** 识图模型的默认值。 */
        const val DEFAULT_VISION_MODEL = "deepseek-v4.1-flash"
    }
}
