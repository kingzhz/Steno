package com.snote

import android.app.Application
import com.snote.core.db.KbOpenHelper

/**
 * 应用入口。只在首次真正需要数据库时才建连接（懒开）。
 */
class SnoteApp : Application() {

    val db: KbOpenHelper by lazy { KbOpenHelper(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: SnoteApp
            private set
    }
}
