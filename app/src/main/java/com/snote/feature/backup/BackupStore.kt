package com.snote.feature.backup

import android.content.Context
import com.snote.core.db.KbOpenHelper
import com.snote.core.db.KbSchema
import com.snote.feature.notes.NoteRepository
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** 一次备份/恢复的结果。message 直接给用户看。 */
data class BackupResult(val ok: Boolean, val message: String)

/**
 * 全库导出与恢复。
 *
 * ## 为什么必须有它
 *
 * 本 App 是本地优先的：数据只在设备上（SQLite + files/attachments/），
 * 而 AndroidManifest 里 allowBackup=false、backup_rules 又把 file 域整个排除 ——
 * 这是刻意的（附件可达 GB 级，进云备份是灾难），但代价是**没有任何兜底**：
 * 用户误清数据、刷机、换机，就是全库灭失。
 *
 * 「本地优先」的前提是用户能自己把数据拿走。这个类就是那个出口。
 *
 * ## 备份包里有什么
 *
 *   manifest.json   版本指纹，恢复前先验，版本不符直接拒绝
 *   kb.db           整库（源数据 + 配置）
 *   attachments/    附件原文件，按 <sha 前两位>/<sha>.<ext> 原样保留
 *
 * 缩略图与 FTS 索引**刻意不打包**：它们是派生物，恢复后本地重建即可，
 * 打包只会让备份体积翻倍。这正是 README 里「源数据永不删除、派生物可重建」
 * 那条分界线在备份功能上的落点。
 *
 * ## 恢复为什么不直接覆盖 kb.db
 *
 * 覆盖文件要先关连接、再改名、再重开，中间任何一步失败都可能留下半个库；
 * 而且此时 App 里其它对象还攥着旧的 SQLiteDatabase 句柄，会读到错乱状态。
 *
 * 改用 ATTACH：把备份库挂成 bak，在**一个事务**里逐表 DELETE + INSERT SELECT。
 * 全程同一个连接、同一个文件，失败则整体回滚，用户看到的是「没变」而不是「坏了」。
 *
 * ## 附件走合并覆盖，不删旧的
 *
 * 恢复只把备份里的附件铺回去（同名直接覆盖），不清理本地多出来的文件。
 * 理由是孤儿文件只占空间、不会造成数据错误，而「先删再铺」一旦中途失败
 * 就是真的丢文件 —— 宁可留垃圾，不可丢数据。
 */
class BackupStore(private val context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)
    private val repo = NoteRepository(context)

    private val attachmentsDir: File get() = File(context.filesDir, "attachments")
    private val dbFile: File get() = context.getDatabasePath(KbSchema.DB_NAME)

    companion object {
        private const val ENTRY_MANIFEST = "manifest.json"
        private const val ENTRY_DB = "kb.db"
        private const val PREFIX_ATT = "attachments/"
        private const val FORMAT_VERSION = 1

        /**
         * 需要搬运的表，**顺序有意为之无关**（搬运前关掉外键约束）。
         *
         * fts_card 不在此列：它是 FTS 虚表，恢复后本地 rebuildFts() 重建。
         * sqlite_sequence 也不在：单独处理，见 restoreTables()。
         */
        private val COPY_TABLES = listOf(
            "doc", "card", "link", "alias", "tag", "card_tag",
            "attachment", "evidence", "setting", "derived",
            "chat_session", "chat_message", "pending_op", "memory"
        )

        /** 建议的备份文件名，由调用方交给 SAF 选择器做默认名。 */
        fun suggestedName(now: Long = System.currentTimeMillis()): String =
            "steno-backup-" +
                SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(now)) +
                ".zip"
    }

    // ==================== 导出 ====================

    /**
     * 把整库写到 [out]。
     *
     * 注意 [out] 由调用方负责关闭（SAF 的 OutputStream），这里只管写。
     */
    fun exportTo(out: OutputStream): BackupResult {
        val db = helper.writableDatabase

        // 先把 WAL 里还没落到主库的帧并回去，否则直接拷贝文件可能少最后几次写入。
        // 本工程默认是回滚日志模式，这句在非 WAL 下只是返回一行提示，不会抛。
        runCatching { db.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() } }

        val attFiles = listAttachmentFiles()
        val attBytes = attFiles.sumOf { it.length() }

        try {
            ZipOutputStream(out.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                val man = JSONObject().apply {
                    put("app", "steno")
                    put("formatVersion", FORMAT_VERSION)
                    put("dbVersion", KbSchema.DB_VERSION)
                    put("exportedAt", System.currentTimeMillis())
                    put("attachmentCount", attFiles.size)
                    put("attachmentBytes", attBytes)
                }
                zip.write(man.toString(2).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry(ENTRY_DB))
                dbFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()

                for (f in attFiles) {
                    val parent = f.parentFile ?: continue
                    zip.putNextEntry(ZipEntry(PREFIX_ATT + parent.name + "/" + f.name))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (t: Throwable) {
            return BackupResult(false, "导出失败：" + (t.message ?: t.javaClass.simpleName))
        }

        return BackupResult(
            true,
            "已导出：数据库 + " + attFiles.size + " 个附件（" + humanSize(attBytes) + "）。"
        )
    }

    // ==================== 恢复 ====================

    /**
     * 用 [input] 里的备份覆盖当前库。
     *
     * @param replaceAttachments 是否同时恢复附件。false 表示只回滚笔记与配置
     *        （例如从别人那里拿到一份纯文字备份）。
     */
    fun importFrom(input: InputStream, replaceAttachments: Boolean = true): BackupResult {
        val tmpDir = File(context.cacheDir, "restore-" + System.currentTimeMillis())
        if (!tmpDir.mkdirs()) return BackupResult(false, "无法创建临时目录，请清理缓存后重试。")

        try {
            // ---- 1. 先全部解包到临时目录，一个字节都不动现有数据 ----
            var manifest: JSONObject? = null
            var attCount = 0
            ZipInputStream(input.buffered()).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory) {
                        val name = e.name
                        when {
                            name == ENTRY_MANIFEST ->
                                manifest = JSONObject(zip.readBytes().toString(Charsets.UTF_8))

                            name == ENTRY_DB ->
                                File(tmpDir, "restore.db").outputStream().use { zip.copyTo(it) }

                            name.startsWith(PREFIX_ATT) -> {
                                val outRoot = File(tmpDir, "attachments")
                                val target = File(outRoot, name.substring(PREFIX_ATT.length))
                                // 防 zip-slip：条目名里带 ../ 时必须拦下，
                                // 否则一个构造过的备份能把文件写到应用目录之外。
                                if (target.canonicalPath.startsWith(outRoot.canonicalPath + File.separator)) {
                                    target.parentFile?.mkdirs()
                                    target.outputStream().use { zip.copyTo(it) }
                                    attCount++
                                } else {
                                    return BackupResult(false, "备份内含非法路径，已中止：" + name)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                    e = zip.nextEntry
                }
            }

            val m = manifest ?: return BackupResult(false, "这不是 Steno 的备份文件（缺少 manifest.json）。")
            if (m.optInt("formatVersion", 0) != FORMAT_VERSION) {
                return BackupResult(false, "备份格式版本不支持：" + m.optInt("formatVersion", 0))
            }

            val restoredDb = File(tmpDir, "restore.db")
            if (!restoredDb.exists() || restoredDb.length() == 0L) {
                return BackupResult(false, "备份里没有数据库文件。")
            }

            // ---- 2. 搬表：ATTACH + 单事务 ----
            val db = helper.writableDatabase
            db.execSQL("ATTACH DATABASE ? AS bak", arrayOf(restoredDb.absolutePath))
            try {
                val backupVer = db.rawQuery("PRAGMA bak.user_version", null).use {
                    if (it.moveToFirst()) it.getInt(0) else -1
                }
                if (backupVer != KbSchema.DB_VERSION) {
                    return BackupResult(
                        false,
                        "备份的库版本是 v" + backupVer + "，当前是 v" + KbSchema.DB_VERSION +
                            "，不能直接恢复（列结构可能不同）。"
                    )
                }

                // 外键必须在事务外关闭：事务内改这个 pragma 是空操作。
                db.execSQL("PRAGMA foreign_keys = OFF")
                db.beginTransaction()
                try {
                    for (t in COPY_TABLES) {
                        db.execSQL("DELETE FROM " + t)
                        db.execSQL("INSERT INTO " + t + " SELECT * FROM bak." + t)
                    }
                    // 自增序列跟着搬，否则恢复后新建的 id 会与备份里的历史 id 断开。
                    runCatching {
                        db.execSQL("DELETE FROM sqlite_sequence")
                        db.execSQL("INSERT INTO sqlite_sequence(name, seq) SELECT name, seq FROM bak.sqlite_sequence")
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } catch (t: Throwable) {
                return BackupResult(false, "恢复失败，数据未改动：" + (t.message ?: t.javaClass.simpleName))
            } finally {
                runCatching { db.execSQL("DETACH DATABASE bak") }
                runCatching { db.execSQL("PRAGMA foreign_keys = ON") }
            }

            // ---- 3. 附件铺回去 ----
            var copied = 0
            if (replaceAttachments) {
                copied = copyTree(File(tmpDir, "attachments"), attachmentsDir)
            }

            // ---- 4. 派生物重建 ----
            // 索引不在备份里，必须重扫；否则恢复完搜索是空的。
            repo.rebuildFts()

            return BackupResult(
                true,
                "已恢复：数据库" +
                    (if (replaceAttachments) " + " + copied + " 个附件" else "（未动附件）") +
                    "。建议重启应用，让各页面重新读取数据。"
            )
        } catch (t: Throwable) {
            return BackupResult(false, "恢复失败，数据未改动：" + (t.message ?: t.javaClass.simpleName))
        } finally {
            deleteTree(tmpDir)
        }
    }

    // ==================== 小工具 ====================

    /** 收集 attachments/<xx>/<file> 下的全部原文件（不含 thumbs）。 */
    private fun listAttachmentFiles(): List<File> {
        val dirs = attachmentsDir.listFiles() ?: return emptyList()
        return dirs.filter { it.isDirectory }.flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
            .filter { it.isFile }
    }

    private fun copyTree(src: File, dst: File): Int {
        if (!src.exists()) return 0
        var n = 0
        src.walkTopDown().forEach { f ->
            if (f.isFile) {
                val target = File(dst, f.relativeTo(src).path)
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
                n++
            }
        }
        return n
    }

    private fun deleteTree(f: File) {
        if (!f.exists()) return
        f.walkBottomUp().forEach { runCatching { it.delete() } }
    }

    private fun humanSize(b: Long): String = when {
        b >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", b / 1024.0 / 1024)
        b >= 1024 -> String.format(Locale.US, "%.0f KB", b / 1024.0)
        else -> b.toString() + " B"
    }
}
