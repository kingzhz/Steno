package com.snote.feature.web

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.snote.core.db.KbOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 一条搜索结果。 */
data class WebHit(val title: String, val url: String, val snippet: String)

/** 某个搜索源今天的用量，供设置页展示。 */
data class WebUsage(val label: String, val used: Int, val limit: Int)

/**
 * 联网搜索，多源依次尝试。
 *
 * ## 为什么是「依次」而不是「并行都查」
 *
 * 每个源都有自己的额度（anysearch 2000/天、firecrawl 1000/月）。并行查意味着
 * 每个问题都同时消耗两份额度，firecrawl 的月额度几天就会见底 —— 那时两个源
 * 一起不可用，比只有一个源更糟。依次尝试则只在**前一个失败时**才动下一个，
 * 正常使用永远只烧主力那一个。
 *
 * ## 为什么需要多个源
 *
 * anysearch 是第三方免费服务，没有 SLA、随时可能限流或改协议。单源意味着
 * 它一挂，整个联网搜索功能就没了 —— 这正是「无缝衔接」要解决的问题：
 * 用户不该感知到源切换，只该看到「这次搜到/没搜到」。
 *
 * ## 额度用尽与网络失败要分开对待
 *
 * 本类把「这个源今天不能再用了」记进本地计数表，下次直接跳过它（省一次必失败的
 * 请求）；而网络波动只影响当次，**不**改变计数 —— 抖一下就把额度扣掉是不对的。
 */
class WebSearch(context: Context) {

    private val helper = KbOpenHelper(context.applicationContext)

    /**
     * 搜一次。
     *
     * @return 给模型看的纯文本。**任何失败都在这句话里说明**，不抛异常。
     */
    suspend fun search(query: String, limit: Int = DEFAULT_LIMIT): String {
        val q = query.trim()
        if (q.isEmpty()) return "搜索词为空。"
        val n = limit.coerceIn(1, MAX_RESULTS)

        val tried = ArrayList<String>()
        for (source in sources) {
            val used = usedToday(source.id)
            if (used >= source.dailyLimit) {
                // 已用完的源不再发请求：那必然失败，只是白等一次超时。
                tried.add(source.label + "（额度已用完）")
                continue
            }

            val hits = try {
                withContext(Dispatchers.IO) { source.call(q, n) }
            } catch (t: Throwable) {
                tried.add(source.label + "（" + shortError(t) + "）")
                continue
            }

            // 只有真的发出并拿到响应才计数 —— 计数记的是「消耗了多少额度」，
            // 不是「尝试了多少次」。
            bump(source.id)

            if (hits.isEmpty()) {
                tried.add(source.label + "（没搜到）")
                continue
            }

            return render(q, hits, source, used + 1)
        }

        return if (tried.isEmpty()) {
            "联网搜索失败：没有可用的搜索源。"
        } else {
            "没有搜到与「" + q + "」相关的结果（已尝试：" + tried.joinToString("、") + "）。"
        }
    }

    /** 今天某个源已用的次数。 */
    fun usedToday(sourceId: String): Int = read(key(sourceId))?.toIntOrNull() ?: 0

    /** 所有源今天的用量，供设置页展示。 */
    fun usage(): List<WebUsage> =
        sources.map { WebUsage(it.label, usedToday(it.id), it.dailyLimit) }

    // ---------- 渲染 ----------

    private fun render(q: String, hits: List<WebHit>, source: Source, usedAfter: Int): String {
        val sb = StringBuilder("联网搜索「" + q + "」，找到 " + hits.size + " 条：\n")
        hits.forEachIndexed { i, h ->
            sb.append(i + 1).append(". ").append(h.title).append('\n')
            if (h.url.isNotBlank()) sb.append("   ").append(h.url).append('\n')
            if (h.snippet.isNotBlank()) sb.append("   ").append(h.snippet).append('\n')
        }
        sb.append("\n（以上是搜索结果的原文摘要，引用时请带上链接；")
            .append("概括时不要加入结果里没有的信息。）")

        // 配额提醒分两档。给模型看而不是只给用户看，是因为**模型才是决定
        // 下一轮要不要接着搜的那个** —— 它看到剩余不多自然会少搜几次。
        val ratio = usedAfter.toDouble() / source.dailyLimit
        if (ratio >= 1.0) {
            sb.append("\n\n【额度提示】").append(source.label).append("今天的额度已用尽，")
                .append("已自动换用备用源，结果可能略有差异。")
        } else if (ratio >= WARN_RATIO) {
            val left = source.dailyLimit - usedAfter
            sb.append("\n\n【额度提示】").append(source.label).append("今天还剩约 ")
                .append(left).append(" 次搜索额度，请节制使用，能用已有信息回答的就不用再搜。")
        }
        return sb.toString()
    }

    private fun shortError(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "超时"
        is java.net.UnknownHostException -> "无网络"
        else -> t.message?.take(40) ?: t.javaClass.simpleName
    }

    // ---------- 本地计数 ----------

    private fun key(sourceId: String): String =
        "websearch." + sourceId + "." + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun read(k: String): String? {
        helper.readableDatabase.query(
            "setting", arrayOf("value"), "key = ?", arrayOf(k), null, null, null
        ).use { c -> return if (c.moveToFirst()) c.getString(0) else null }
    }

    private fun bump(sourceId: String) {
        val k = key(sourceId)
        val next = ((read(k)?.toIntOrNull() ?: 0) + 1).toString()
        helper.writableDatabase.insertWithOnConflict(
            "setting", null,
            ContentValues().apply { put("key", k); put("value", next) },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    // ---------- 源定义 ----------

    /**
     * 一个搜索源。
     *
     * [call] 失败时**必须抛异常**（而不是返回空列表）：空列表的含义是
     * 「搜了，但没有相关结果」，与「这个源挂了」是两回事 —— 只有后者才该换源。
     */
    private class Source(
        val id: String,
        val label: String,
        val dailyLimit: Int,
        val call: (String, Int) -> List<WebHit>,
    )

    private val sources: List<Source> = listOf(
        Source("anysearch", "AnySearch", ANYSEARCH_LIMIT) { q, n -> anysearch(q, n) },
        // firecrawl 免费层是 1000/**月**，这里按天折算成 33 作为闸门：
        // 宁可提前停用也不要在月中突然没额度 —— 那才是用户真正会遇到的情况。
        Source("firecrawl", "Firecrawl", FIRECRAWL_DAILY) { q, n -> firecrawl(q, n) },
        Source("bing_rss", "Bing", BING_LIMIT) { q, n -> bingRss(q, n) },
    )

    // ---------- 各源实现 ----------

    /**
     * anysearch：JSON-RPC over MCP，**免密钥**。
     *
     * 协议来自 argo（github.com/taxueseek/argo）的 engines_builders_tech.py：
     * Authorization 头是**可选**的，不带也能调。响应里 result.content[].text
     * 是 markdown，结果块以行首「### N.」分隔。
     */
    private fun anysearch(query: String, limit: Int): List<WebHit> {
        val args = JSONObject().put("query", query).put("max_results", limit)
        val payload = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
            .put("params", JSONObject().put("name", "search").put("arguments", args))

        val json = postJson(ENDPOINT_ANYSEARCH, payload.toString())
        // 上游会用 HTTP 200 + isError=true 表达失败。只看状态码会把
        // 「服务不可用」误判成「没有结果」，模型据此回答就是错的。
        val result = json.optJSONObject("result") ?: return emptyList()
        if (result.optBoolean("isError", false)) {
            val msg = result.optJSONArray("content")
                ?.optJSONObject(0)?.optString("text").orEmpty()
            throw IllegalStateException(msg.ifBlank { "anysearch 返回 isError" })
        }
        val content = result.optJSONArray("content") ?: return emptyList()
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            sb.append(content.optJSONObject(i)?.optString("text").orEmpty())
        }
        return parseMarkdown(sb.toString())
    }

    /**
     * firecrawl：官方免费层**匿名（keyless）**即可搜索，额度 1000/月。
     *
     * argo 的 engine_env.py 记着这条：「FIRECRAWL_API_KEY 可选：官方免费层匿名即可搜索」。
     * 只拿标题/描述/链接，不请求全文 —— 全文会让返回体大几十倍。
     */
    private fun firecrawl(query: String, limit: Int): List<WebHit> {
        val body = JSONObject().put("query", query).put("limit", limit)
        val json = postJson(ENDPOINT_FIRECRAWL, body.toString())
        if (!json.optBoolean("success", false)) {
            val err = json.optString("error").ifBlank { "firecrawl 返回 success=false" }
            throw IllegalStateException(err.take(80))
        }
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<WebHit>(data.length())
        for (i in 0 until data.length()) {
            val o = data.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            out.add(
                WebHit(
                    title = title.take(TITLE_MAX),
                    url = o.optString("url"),
                    snippet = o.optString("description").trim().take(SNIPPET_MAX),
                )
            )
        }
        return out
    }

    /**
     * Bing 的 RSS 出口：**免密钥**，字段语义由 RSS 2.0 规范保证，不怕改版。
     *
     * 但它的中文相关性明显弱于前两个源（实测搜「2026年诺贝尔物理学奖得主」
     * 会返回百度百科、节假日安排这类结果），且不支持 mkt/setlang 本地化参数。
     * 因此只放在最后当保底，不参与主力竞争。
     */
    private fun bingRss(query: String, limit: Int): List<WebHit> {
        val url = ENDPOINT_BING + java.net.URLEncoder.encode(query, "UTF-8") +
            "&format=rss&count=" + limit.coerceIn(1, 20)
        val request = Request.Builder().url(url)
            .header("Accept", "application/rss+xml, application/xml, text/xml")
            .build()
        val xml = http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            resp.body?.string().orEmpty()
        }
        if (xml.isBlank()) return emptyList()
        return parseRss(xml)
    }

    // ---------- 解析 ----------

    /**
     * 解析 anysearch 的 markdown。
     *
     * 用**行首锚定**而不是按「换行 + ###」切：首个结果块顶格开头、前面没有换行，
     * 按换行切会把第一块并进去 —— argo 的实现里专门记了这个坑。
     */
    private fun parseMarkdown(markdown: String): List<WebHit> {
        val out = ArrayList<WebHit>()
        for (block in MD_BLOCK.split(markdown).drop(1)) {
            val lines = block.trim().lines()
            val title = lines.firstOrNull()?.trim().orEmpty()
            if (title.isEmpty()) continue
            var url = ""
            val snippet = ArrayList<String>()
            for (line in lines.drop(1)) {
                val t = line.trim()
                when {
                    t.startsWith(URL_PREFIX) -> url = t.removePrefix(URL_PREFIX).trim()
                    t.startsWith(URL_PREFIX_BARE) -> url = t.removePrefix(URL_PREFIX_BARE).trim()
                    else -> snippet.add(line)
                }
            }
            out.add(
                WebHit(
                    title = title.take(TITLE_MAX),
                    url = url,
                    snippet = snippet.joinToString("\n").trim().take(SNIPPET_MAX),
                )
            )
        }
        return out
    }

    /**
     * 解析 Bing 的 RSS。
     *
     * 手写正则而不是用 XML 解析器：RSS 里有未转义的 & 与 HTML 片段，
     * 严格解析器会直接抛错把整批结果丢掉；这里只要标题/链接/描述三个字段，
     * 正则在「部分条目畸形」时仍能保住其余条目。
     */
    private fun parseRss(xml: String): List<WebHit> {
        val out = ArrayList<WebHit>()
        for (chunk in xml.split("<item>").drop(1)) {
            val title = unescape(tag(chunk, "title"))
            val url = unescape(tag(chunk, "link"))
            if (title.isEmpty() || url.isEmpty()) continue
            out.add(
                WebHit(
                    title = title.take(TITLE_MAX),
                    url = url,
                    snippet = unescape(tag(chunk, "description")).take(SNIPPET_MAX),
                )
            )
        }
        return out
    }

    private fun tag(chunk: String, name: String): String {
        val m = Regex("<" + name + ">([\\s\\S]*?)</" + name + ">").find(chunk) ?: return ""
        return HTML_TAG.replace(m.groupValues[1], "").trim()
    }

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&amp;", "&")

    // ---------- HTTP ----------

    private fun postJson(url: String, body: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        val text = http.newCall(request).execute().use { resp ->
            val t = try { resp.body?.string() ?: "" } catch (e: Throwable) { "" }
            if (!resp.isSuccessful) throw IllegalStateException("HTTP " + resp.code)
            t
        }
        return try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw IllegalStateException("返回不是 JSON")
        }
    }

    companion object {
        private const val DEFAULT_LIMIT = 5
        private const val MAX_RESULTS = 8
        private const val TITLE_MAX = 120
        private const val SNIPPET_MAX = 300

        /** 用到这个比例就开始在结果里附提醒（模型会据此主动少搜）。 */
        private const val WARN_RATIO = 0.9

        private const val ANYSEARCH_LIMIT = 2000
        private const val BING_LIMIT = 200

        /** firecrawl 免费层 1000/月，折算成每天 33，避免月中突然断供。 */
        private const val FIRECRAWL_DAILY = 33

        private const val ENDPOINT_ANYSEARCH = "https://api.anysearch.com/mcp"
        private const val ENDPOINT_FIRECRAWL = "https://api.firecrawl.dev/v1/search"
        private const val ENDPOINT_BING = "https://www.bing.com/search?q="

        private const val URL_PREFIX = "- **URL**: "
        private const val URL_PREFIX_BARE = "**URL**: "

        /** 结果块分隔：行首「### 1.」「### 2.」…… */
        private val MD_BLOCK = Regex("^### \\d+\\.\\s", RegexOption.MULTILINE)
        private val HTML_TAG = Regex("<[^>]+>")

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * 超时比 CodeBuddy 网关紧：这是工具调用的一环，卡太久会让整轮对话迟迟不响应。
         * 实测正常查询 0.4-3.5 秒，生僻词最长见过 10 秒，故读超时取 20 秒。
         */
        val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
