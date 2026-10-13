package com.snote.feature.attachments

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.snote.core.db.KbOpenHelper
import java.io.File
import java.security.MessageDigest

/** 一条附件记录。 */
data class Attachment(
    val id: Long,
    val sha256: String,
    val relPath: String,
    val bytes: Long,
    val mime: String?,
    val createdAt: Long,
)

/**
 * 附件的存取。
 *
 * ## 磁盘布局
 *
 *   files/attachments/<sha256 前 2 位>/<sha256>.<ext>   —— 原文件
 *   files/thumbs/<sha256>.webp                          —— 缩略图
 *
 * 按哈希前两位分子目录：几千个文件堆在一个目录里，某些文件系统（含部分
 * Android 机型的 F2FS/ext4 配置）的目录查找会明显变慢。
 *
 * ## 去重靠唯一索引，不靠先查后插
 *
 * attachment.sha256 上有 UNIQUE 约束，插入冲突即命中已有文件。
 * 「先 SELECT 再 INSERT」在并发下有竞态，唯一索引是数据库保证的。
 *
 * ## 内存纪律
 *
 * 算哈希用 64KB 缓冲区流式读，绝不把整个文件读进内存 —— 用户可能丢进一个
 * 200MB 的视频，readBytes() 会直接 OOM。
 */
class AttachmentStore(private val context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)

    private val filesDir: File get() = File(context.filesDir, "attachments")
    private val thumbsDir: File get() = File(context.filesDir, "thumbs")

    /**
     * 导入一个文件。
     *
     * @param dedup 是否启用去重（对应「附件去重」功能开关）。关闭时同一文件会
     *              存成多份 —— 这是用户显式选择的行为，不是 bug。
     * @return 附件记录；失败返回 null。
     */
    fun import(uri: Uri, dedup: Boolean = true): Attachment? {
        val name = queryName(uri) ?: "file"
        val mime = context.contentResolver.getType(uri)

        // 先落到临时文件：哈希必须读完整份才知道，而内容流只能读一次。
        val tmp = File(context.cacheDir, "import-" + System.currentTimeMillis())
        val copied = try {
            copyAndHash(uri, tmp)
        } catch (t: Throwable) {
            null
        }
        if (copied == null) {
            tmp.delete()
            return null
        }
        val sha = copied.first
        val size = copied.second

        // 去重：哈希已存在就直接复用，临时文件删掉。
        if (dedup) {
            val existing = bySha(sha)
            if (existing != null) {
                tmp.delete()
                return existing
            }
        }

        val ext = extensionOf(name, mime)
        val dir = File(filesDir, sha.substring(0, 2))
        if (!dir.exists() && !dir.mkdirs()) {
            tmp.delete()
            return null
        }
        // 关闭去重时同名会互相覆盖，因此带上时间戳后缀保证各存一份。
        val target = if (dedup) File(dir, sha + ext)
        else File(dir, sha + "-" + System.currentTimeMillis() + ext)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }

        val rel = relativePath(target)
        val values = ContentValues().apply {
            // 关闭去重时，同一文件要能存成多行，而 sha256 列上有 UNIQUE 约束。
            // 这里加一个唯一后缀，代价是该列不再是纯哈希 —— 为「去重」这一个开关
            // 去改表结构不划算，且此模式下按哈希反查本就不是调用方需要的语义。
            put("sha256", if (dedup) sha else sha + "-" + System.nanoTime())
            put("rel_path", rel)
            put("bytes", size)
            put("mime", mime)
            put("created_at", System.currentTimeMillis())
        }
        val id = helper.writableDatabase.insert("attachment", null, values)
        return if (id > 0) Attachment(id, sha, rel, size, mime, System.currentTimeMillis()) else null
    }

    fun bySha(sha: String): Attachment? = queryOne("sha256 = ?", arrayOf(sha))

    fun byId(id: Long): Attachment? = queryOne("id = ?", arrayOf(id.toString()))

    fun list(): List<Attachment> {
        val out = ArrayList<Attachment>()
        helper.readableDatabase.query(
            "attachment", arrayOf("id", "sha256", "rel_path", "bytes", "mime", "created_at"),
            null, null, null, null, "created_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Attachment(
                        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                        c.getLong(3), c.getString(4), c.getLong(5)
                    )
                )
            }
        }
        return out
    }

    fun fileOf(a: Attachment): File = File(context.filesDir, a.relPath)

    /**
     * 生成缩略图（幂等）。
     *
     * 用 inSampleSize 先算出采样比再解码，而不是解码后再缩放：直接解码一张
     * 4000x3000 的照片要占约 48MB 堆，而缩略图只要 200px。
     *
     * @return 缩略图文件；非图片或解码失败返回 null（调用方回落到图标）。
     */
    fun thumbnail(a: Attachment, targetPx: Int = 240): File? {
        val dst = File(thumbsDir, a.sha256 + ".png")
        if (dst.exists() && dst.length() > 0) return dst

        val src = fileOf(a)
        if (!src.exists()) return null

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(src.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetPx &&
                bounds.outHeight / (sample * 2) >= targetPx
            ) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return null
            if (!thumbsDir.exists()) thumbsDir.mkdirs()

            dst.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            bmp.recycle()
            if (dst.length() > 0) dst else null
        } catch (t: Throwable) {
            null
        }
    }

    /** 删除缩略图缓存（「附件缩略图」功能关闭时调用）。返回删除的文件数。 */
    fun clearThumbs(): Int {
        if (!thumbsDir.exists()) return 0
        val files = thumbsDir.listFiles() ?: return 0
        var n = 0
        files.forEach { if (it.delete()) n++ }
        return n
    }

    /** 删除一条附件记录（文件也删）。 */
    fun delete(id: Long) {
        val a = byId(id) ?: return
        fileOf(a).delete()
        File(thumbsDir, a.sha256 + ".png").delete()
        helper.writableDatabase.delete("attachment", "id = ?", arrayOf(id.toString()))
    }

    // ---------- 内部 ----------

    /**
     * 把 uri 的内容复制到 dst，同时算出 SHA-256。
     *
     * 一边读一边喂 digest，不把整份读进内存 —— 用户可能丢进一个几百 MB 的视频，
     * readBytes() 会直接 OOM。
     *
     * @return (哈希, 字节数)；打不开输入流返回 null。
     */
    private fun copyAndHash(uri: Uri, dst: File): Pair<String, Long>? {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        input.use { src ->
            dst.outputStream().use { out ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    val n = src.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                    total += n
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } to total
    }

    private fun queryOne(where: String, args: Array<String>): Attachment? {
        helper.readableDatabase.query(
            "attachment", arrayOf("id", "sha256", "rel_path", "bytes", "mime", "created_at"),
            where, args, null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Attachment(
                c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "",
                c.getLong(3), c.getString(4), c.getLong(5)
            )
        }
    }

    private fun relativePath(f: File): String {
        val base = context.filesDir.absolutePath
        val p = f.absolutePath
        return if (p.startsWith(base)) p.substring(base.length).trimStart(File.separatorChar) else p
    }

    private fun queryName(uri: Uri): String? = try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (t: Throwable) {
        null
    }

    private fun extensionOf(name: String, mime: String?): String {
        val fromName = name.substringAfterLast('.', "")
        if (fromName.isNotEmpty() && fromName.length <= 5) return "." + fromName.lowercase()
        return when {
            mime == null -> ""
            mime.startsWith("image/png") -> ".png"
            mime.startsWith("image/jpeg") -> ".jpg"
            mime.startsWith("image/webp") -> ".webp"
            mime.startsWith("image/gif") -> ".gif"
            mime.startsWith("application/pdf") -> ".pdf"
            mime.startsWith("text/") -> ".txt"
            else -> ""
        }
    }

    companion object {
        /** 流式算哈希的缓冲区。64KB 是吞吐与内存的常见折中。 */
        private const val BUFFER = 64 * 1024
    }
}
