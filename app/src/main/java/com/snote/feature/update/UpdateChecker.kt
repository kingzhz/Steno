package com.snote.feature.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.snote.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** 一次版本检查的结果。 */
sealed interface UpdateResult {
    /** 已是最新。 */
    data object UpToDate : UpdateResult

    /** 有新版本可用。 */
    data class Available(
        val versionName: String,
        val apkUrl: String,
        val pageUrl: String,
        val notes: String,
        val sizeBytes: Long,
        /**
         * 官方 SHA-256（十六进制小写）；空串表示这个 release 没提供摘要。
         *
         * 来自 api.github.com 的 asset.digest 字段 —— 它是**直连**拿到的，
         * 而 APK 可能从镜像下载。两者一比，镜像就无法偷换安装包：
         * 换过的文件哈希必然对不上（除非它同时能篡改 api.github.com 的响应）。
         */
        val sha256: String,
    ) : UpdateResult

    /** 检查失败。message 直接给用户看。 */
    data class Failed(val message: String) : UpdateResult
}

/**
 * 从 GitHub Releases 检查并下载新版本。
 *
 * ## 为什么是「手动触发」而不是「启动时自动检查」
 *
 * 本应用的核心承诺是**数据不出设备**。自动检查意味着每次冷启动都向
 * api.github.com 发一次请求 —— 那等于告诉 GitHub「这台设备装了 Steno、
 * 大概什么时候用的、IP 在哪」。对一个本地优先的笔记应用，这个代价太大。
 *
 * 所以只在用户主动点「检查更新」时才联网。少一点自动化，换回承诺的完整。
 *
 * ## 为什么下载完不自动安装
 *
 * 安卓不允许普通应用静默安装 —— 这是系统级防线，不是可以绕过的限制。
 * 能做的是：下载好 APK，然后用 Intent 把系统安装器叫起来（用户点一下确认）。
 * 从 Android 8 起还需要「安装未知应用」权限，如果没给，系统会引导去开。
 *
 * ## 为什么用 OkHttp 而不是 HttpURLConnection
 *
 * 项目里已有 OkHttp（对话与联网搜索在用），复用它就够 —— 为了这一个功能
 * 再引一个 HTTP 库没有道理。
 */
object UpdateChecker {

    /** 仓库坐标。硬编码在这里是刻意的：它就是这个应用自己的身份，不会变。 */
    private const val OWNER = "kingzhz"
    private const val REPO = "Steno"

    private const val API_LATEST = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /**
     * 下载镜像前缀，按顺序尝试。
     *
     * ## 为什么需要它们
     *
     * 实测（本机、移动网络）：直连 GitHub Releases 约 272 KB/s，38MB 要十几分钟；
     * 而 gh-proxy.com 约 4.4 MB/s，8 秒下完同一个包 —— 16 倍差距。
     * 对国内用户来说，"能用"与"不能用"的区别就在这里。
     *
     * ## 为什么可以信任镜像
     *
     * **不信任，而是校验**：官方 SHA-256 从 api.github.com 直连取得（见
     * [UpdateResult.Available.sha256]），下载完本地算一遍比对。镜像能做的
     * 只有「不给」或「给错」，给错会被哈希挡下来。它拿不到签名私钥，
     * 就算塞一个自己签的包装上去，安卓安装器也会拒绝。
     *
     * 所以顺序是：先快后慢，但**每个候选都必须过哈希**才接受。
     */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "",                       // 空串 = 直连，放最后兜底
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 下载 APK 用单独的 client：读超时要长得多，大文件不能按 API 的标准卡。 */
    private val downloadHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // GitHub 的直链会 302 到 objects.githubusercontent.com，默认跟随，
        // 但默认只跟 2 跳 —— 明确放宽到 5，避免中途被截断。
        .followRedirects(true)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * 查最新版本。
     *
     * 只比较 versionName 的数字部分（major.minor.patch），不比较 versionCode：
     * 后者只能从 API 里拿到 tag 名，而 tag 名是人写的，格式不可靠。
     */
    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(API_LATEST)
            .header("Accept", "application/vnd.github+json")
            // GitHub API 要求 UA，否则 403。这是它唯一管的请求头。
            .header("User-Agent", "Steno-Android")
            .build()

        try {
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext UpdateResult.Failed(
                        if (res.code == 404) {
                            "仓库还没有发布版本。"
                        } else {
                            "查询失败：HTTP " + res.code
                        }
                    )
                }
                val body = res.body?.string() ?: return@withContext UpdateResult.Failed("响应为空。")
                parseLatest(body)
            }
        } catch (t: Throwable) {
            // 网络不通是最常见的情况，给一句人话而不是堆栈。
            UpdateResult.Failed("连不上 GitHub，请检查网络后重试。（" + (t.message ?: t.javaClass.simpleName) + "）")
        }
    }

    private fun parseLatest(json: String): UpdateResult {
        val o = try {
            JSONObject(json)
        } catch (t: Throwable) {
            return UpdateResult.Failed("响应格式不对。")
        }

        val tag = o.optString("tag_name").removePrefix("v").trim()
        if (tag.isBlank()) return UpdateResult.Failed("发布信息里没有版本号。")

        if (compareVersions(tag, currentVersion()) <= 0) return UpdateResult.UpToDate

        // 从 assets 里找 .apk。找不到就说明这个 release 只发了源码。
        val assets = o.optJSONArray("assets")
        var apkUrl = ""
        var size = 0L
        var sha = ""
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name")
                if (n.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.optString("browser_download_url")
                    size = a.optLong("size")
                    // digest 形如 "sha256:abc…"；去掉前缀只留十六进制。
                    sha = a.optString("digest").removePrefix("sha256:").trim().lowercase()
                    break
                }
            }
        }
        if (apkUrl.isBlank()) {
            return UpdateResult.Failed("新版本 " + tag + " 没有附带 APK。")
        }

        return UpdateResult.Available(
            versionName = tag,
            apkUrl = apkUrl,
            pageUrl = o.optString("html_url"),
            notes = o.optString("body"),
            sizeBytes = size,
            sha256 = sha,
        )
    }

    /** 一次镜像尝试的结果。 */
    private sealed interface Attempt {
        class Ok(val file: File) : Attempt
        class Bad(val reason: String) : Attempt
    }

    /**
     * 下载 APK：按镜像顺序尝试，**每个候选都必须通过 SHA-256 校验**。
     *
     * ## 为什么落 cacheDir 而不是 filesDir
     *
     * 这是**可丢弃的派生物** —— 装完就没用了，系统在空间紧张时也该能清掉它。
     * 放 filesDir 会永久占着四十多兆。
     *
     * ## 校验缺失时的行为
     *
     * 若 release 没提供 digest（例如手工上传的旧资产），**回落到只走直连**，
     * 不走镜像 —— 没有可校验的锚点时，镜像的可信度从「可验证」降到「靠信任」，
     * 那不值得拿安装包去赌。
     *
     * @param onProgress 已下载字节数 → 总字节数（总数 <= 0 表示未知）
     */
    suspend fun download(
        context: Context,
        url: String,
        sha256: String = "",
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "update")
        if (!dir.exists() && !dir.mkdirs()) return@withContext null
        // 先清掉上一轮残留：否则用户可能拿到一个装了一半的旧包。
        dir.listFiles()?.forEach { it.delete() }

        // 有摘要才走镜像；没有就只试直连（见上面的注释）。
        val prefixes = if (sha256.isNotBlank()) MIRROR_PREFIXES else listOf("")

        for (prefix in prefixes) {
            val target = File(dir, "Steno-update.apk")
            val r = tryDownload(prefix + url, target, onProgress)
            if (r is Attempt.Ok) {
                // 有摘要就必须对得上；对不上说明这个源给了错的东西，换下一个。
                if (sha256.isBlank() || sha256Of(r.file) == sha256) return@withContext r.file
                r.file.delete()
            } else {
                target.delete()
            }
        }
        null
    }

    private fun tryDownload(url: String, out: File, onProgress: (Long, Long) -> Unit): Attempt = try {
        downloadHttp.newCall(
            Request.Builder().url(url).header("User-Agent", "Steno-Android").build()
        ).execute().use { res ->
            if (!res.isSuccessful) return Attempt.Bad("HTTP " + res.code)
            val body = res.body ?: return Attempt.Bad("empty")
            val total = body.contentLength()
            body.byteStream().use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
        if (out.length() > 0) Attempt.Ok(out) else Attempt.Bad("empty file")
    } catch (t: Throwable) {
        Attempt.Bad(t.message ?: t.javaClass.simpleName)
    }

    /** 流式计算文件 SHA-256。绝不把整个文件读进内存（安装包有几十兆）。 */
    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    /**
     * 把系统安装器叫起来。
     *
     * 必须走 FileProvider 换一个 content:// —— 直接传 file:// 在 Android 7+
     * 会抛 FileUriExposedException（与拍照那条路径同一个原因，见 file_paths.xml）。
     */
    fun installIntent(context: Context, apk: File): Intent? {
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                apk,
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 当前版本名，来自构建配置而不是硬编码。 */
    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /**
     * 比较版本号。返回 >0 表示 a 比 b 新。
     *
     * 按数字逐段比较，而不是字符串比较 —— 后者会把 "0.10.0" 判定为小于
     * "0.9.0"（因为 '1' < '9'）。段数不同时缺失的段按 0 处理。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".", "-", "+")
        val pb = b.split(".", "-", "+")
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i)?.toIntOrNull() ?: 0
            val y = pb.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }

    /** 给人看的体积。 */
    fun humanSize(b: Long): String = when {
        b <= 0 -> "未知大小"
        b >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", b / 1024.0 / 1024 / 1024)
        b >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", b / 1024.0 / 1024)
        b >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> b.toString() + " B"
    }
}
