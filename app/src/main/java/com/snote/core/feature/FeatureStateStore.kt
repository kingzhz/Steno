package com.snote.core.feature

import android.content.ContentValues
import android.content.Context
import com.snote.core.db.KbOpenHelper

/**
 * 功能开关持久化（落在 setting 表，key = feature.<id>.enabled）。
 *
 * 这里只负责「记住状态」；真正的卸载/重建由 FeatureController 执行。
 */
class FeatureStateStore(context: Context) {

    private val helper = KbOpenHelper(context)

    fun loadAll(): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        helper.readableDatabase.query(
            "setting", arrayOf("key", "value"), "key LIKE ?", arrayOf("feature.%"),
            null, null, null
        ).use { c ->
            while (c.moveToNext()) {
                val key = c.getString(0)
                val id = key.removePrefix("feature.").removeSuffix(".enabled")
                out[id] = c.getString(1) == "1"
            }
        }
        return out
    }

    fun isEnabled(id: String): Boolean =
        loadAll()[id] ?: (FeatureRegistry.byId(id)?.defaultEnabled ?: false)

    fun setEnabled(id: String, enabled: Boolean) {
        val values = ContentValues().apply {
            put("key", "feature." + id + ".enabled")
            put("value", if (enabled) "1" else "0")
        }
        helper.writableDatabase.insertWithOnConflict(
            "setting", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )
    }
}
