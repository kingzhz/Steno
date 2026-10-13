package com.snote.core.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 单库连接。
 *
 * 说明：刻意不用 Room —— Room 对 FTS5 / WITHOUT ROWID / VACUUM 的可控性不如手写 DDL，
 * 而本项目的检索方案需要在建库时做引擎探测与整表卸载。
 */
class KbOpenHelper(context: Context) :
    SQLiteOpenHelper(context.applicationContext, KbSchema.DB_NAME, null, KbSchema.DB_VERSION) {

    @Volatile
    var searchEngine: SearchEngine = SearchEngine.FTS5
        private set

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        // createAll 内部已经建好索引并返回引擎，不要再单独调一次。
        searchEngine = KbSchema.createAll(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        for (v in (oldVersion + 1)..newVersion) {
            KbSchema.MIGRATIONS[v]?.forEach { db.execSQL(it) }
        }
        // 索引可能不存在（老库升级）或引擎不同，重建一次。
        searchEngine = KbSchema.createSearchIndex(db)
        // 兜底：迁移只跑到 oldVersion+1..newVersion，若从 v1 直升 v4 而中间某步没写，
        // 表就会缺 —— 这里补一次幂等建表（DDL 全是 IF NOT EXISTS）。
        (KbSchema.CHAT_DDL + KbSchema.PENDING_DDL).forEach { db.execSQL(it) }
        // 建表兜底挡不住「表在、列缺」：老库的 chat_message 已经存在，上面的
        // IF NOT EXISTS 会整个跳过，op_id 永远加不上。补列只能单独查着加。
        KbSchema.ensureColumns(db)
    }

    /** 派生物卸载后必须 VACUUM，否则 SQLite 只把页标为空闲，磁盘不还。 */
    fun vacuum() {
        writableDatabase.execSQL("VACUUM")
    }
}
