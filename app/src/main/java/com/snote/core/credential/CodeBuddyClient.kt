package com.snote.core.credential

import com.snote.feature.llm.LlmChunk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 登录过程中向后传递的阶段，供界面显示「正在等你在浏览器里完成」。 */
sealed interface LoginStage {
    /** 已拿到 state 与登录地址，并已/即将唤起浏览器。 */
    data class Waiting(val loginUrl: String) : LoginStage
    /** 已经在轮询，第 n 次。 */
    data class Polling(val attempt: Int) : LoginStage
    data class Succeeded(val account: CodeBuddyAccount) : LoginStage
    data class Failed(val message: String) : LoginStage
}

/**
 * CodeBuddy 网关。
 *
 * ## 这是协议的可执行规格
 *
 * 三条硬约束来自 PC 端探针的**真机实测**（不是从文档推的），它们各自对应一个
 * 服务端错误码；本类把它们固化在实现里，调用方不需要知道：
 *
 *   1. `stream` **必须为 true** —— 传 false 报 11101 Non-stream not supported。
 *   2. **首条消息必须是 system** —— 否则报 11128 first message is not system prompt。
 *   3. 请求必须带齐身份头（X-Domain / X-Product-Code / X-Product=SaaS / X-IDE-* /
 *      X-Agent-Purpose），且 User-Agent **按模型族分档**，否则后台归因失败。
 *
 * ## 登录是轮询式，不是本地回调
 *
 * auth/state 拿 state + authUrl → 用户在浏览器完成 → 轮询 auth/token（11217 = 等）
 * → 轮询 login/account（12151 = 等，失败不致命）。**没有本地端口、没有回调地址**，
 * 因此在手机上只需 Intent.ACTION_VIEW 打开浏览器即可。
 */
class CodeBuddyClient(
    private val http: OkHttpClient = defaultClient(),
) {

    // ==================== 登录 ====================

    /**
     * 完整登录流程。以 Flow 形式逐阶段上报，界面据此更新状态。
     */
    fun login(openBrowser: (String) -> Unit): Flow<LoginStage> = callbackFlow {
        val job = launch(Dispatchers.IO) {
            try {
                val stateJson = post(
                    "/v2/plugin/auth/state?platform=" + PLATFORM,
                    baseHeaders(null) + mapOf(
                        "X-No-Authorization" to "true",
                        "X-No-User-Id" to "true",
                        "X-No-Enterprise-Id" to "true",
                        "X-No-Department-Info" to "true",
                    ),
                    JSONObject()
                )
                val state = stateJson?.optJSONObject("data")?.optString("state").orEmpty()
                val rawUrl = stateJson?.optJSONObject("data")?.optString("authUrl").orEmpty()
                if (state.isBlank() || rawUrl.isBlank()) {
                    trySend(LoginStage.Failed("auth/state 响应缺少 state 或 authUrl"))
                    close(); return@launch
                }

                val loginUrl = decorateLoginUrl(rawUrl)
                trySend(LoginStage.Waiting(loginUrl))
                openBrowser(loginUrl)

                // 轮询 token：11217 = 尚未就绪，继续等。5 分钟上限与官方一致。
                val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
                var attempt = 0
                var token: JSONObject? = null
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(POLL_MS)
                    attempt++
                    if (attempt % 5 == 0) trySend(LoginStage.Polling(attempt))
                    val headers = mapOf(
                        "X-No-Authorization" to "true",
                        "User-Agent" to userAgentFor(null),
                    )
                    val res = get("/v2/plugin/auth/token?state=" + state, headers) ?: continue
                    val data = res.optJSONObject("data")
                    if (data != null) { token = data; break }
                    val code = res.optInt("code", 0)
                    if (code == 0 || code == CODE_TOKEN_NOT_READY) continue
                    trySend(LoginStage.Failed("登录被拒绝：code=" + code + " " + res.optString("msg")))
                    close(); return@launch
                }
                if (token == null) {
                    trySend(LoginStage.Failed("等待登录超时（5 分钟）"))
                    close(); return@launch
                }

                val access = token.optString("accessToken")
                val refresh = token.optString("refreshToken")
                if (access.isBlank()) {
                    trySend(LoginStage.Failed("服务端未返回 accessToken"))
                    close(); return@launch
                }

                // 账户信息取不到不致命（实测经常 12151 或空昵称）。
                var userId = ""
                var label = ""
                run {
                    val accDeadline = System.currentTimeMillis() + 30_000L
                    while (System.currentTimeMillis() < accDeadline) {
                        Thread.sleep(POLL_MS)
                        val res = get(
                            "/v2/plugin/login/account?state=" + state,
                            mapOf("X-No-Authorization" to "true", "User-Agent" to userAgentFor(null))
                        ) ?: continue
                        val data = res.optJSONObject("data")
                        if (data != null) {
                            userId = data.optString("uid")
                            label = data.optString("nickname")
                            break
                        }
                        if (res.optInt("code", 0) == CODE_ACCOUNT_NOT_READY) continue
                        break
                    }
                }
                if (userId.isBlank()) userId = CodeBuddyAccount.subjectFromJwt(access)

                val now = System.currentTimeMillis()
                val account = CodeBuddyAccount(
                    id = CodeBuddyAccount.newId(),
                    label = label.ifBlank { userId.ifBlank { "CodeBuddy " + now.toString().takeLast(4) } },
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAtMs = CodeBuddyAccount.expiresAtFromJwt(access),
                    domain = HOST,
                    userId = userId,
                )
                trySend(LoginStage.Succeeded(account))
            } catch (t: Throwable) {
                trySend(LoginStage.Failed(t.message ?: t.javaClass.simpleName))
            } finally {
                close()
            }
        }
        awaitClose { job.cancel() }
    }

    /**
     * 续期。refresh_token 失效时返回 null（调用方应提示重新登录）。
     *
     * 判定口径与官方实现一致：HTTP 401/403、或 message 含 expired/invalid，
     * 都视为**终态**，不再重试 —— 否则每次刷新失败都会被当成可重试错误而无限循环。
     */
    fun refresh(account: CodeBuddyAccount): CodeBuddyAccount? {
        if (!account.hasRefreshToken) return null
        val res = postRaw(
            "/v2/plugin/auth/token/refresh",
            baseHeaders(null) + mapOf(
                "Authorization" to "Bearer " + account.accessToken,
                "X-Refresh-Token" to account.refreshToken,
                "X-Auth-Refresh-Source" to "cli",
            ),
            JSONObject()
        ) ?: return null
        if (res.first != 200) return null
        val data = res.second.optJSONObject("data") ?: return null
        val access = data.optString("accessToken")
        if (access.isBlank()) return null
        return account.copy(
            accessToken = access,
            refreshToken = data.optString("refreshToken").ifBlank { account.refreshToken },
            expiresAtMs = CodeBuddyAccount.expiresAtFromJwt(access),
        )
    }

    // ==================== 模型目录 ====================

    /**
     * 模型 id 列表。
     *
     * 两个端点**取并集**（实测 id 集合不同）：scoped 端点对 CLI token 常返回 500，
     * 但一旦可用它带的元数据更全；/v3/config 是稳定兜底。
     * 两边都失败时返回空表，由界面提示「拉取失败」，不编造默认模型。
     */
    fun listModels(account: CodeBuddyAccount): List<String> {
        val ids = LinkedHashSet<String>()
        collect(get("/console/enterprises/personal/models", authHeaders(account)), ids)
        collect(get("/v3/config", authHeaders(account)), ids)
        return ids.toList()
    }

    private fun collect(json: JSONObject?, into: MutableSet<String>) {
        if (json == null) return
        val data = json.optJSONObject("data") ?: return
        val arr = data.optJSONArray("models")
            ?: data.optJSONObject("data")?.optJSONArray("models")
            ?: return
        for (i in 0 until arr.length()) {
            val item = arr.opt(i)
            val id = when (item) {
                is String -> item
                is JSONObject -> item.optString("id")
                else -> ""
            }
            if (id.isNotBlank()) into.add(id)
        }
    }

    // ==================== 对话 ====================

    /**
     * 流式对话。
     *
     * 这里代调用方补上两条硬约束（system 首条、stream:true），因此
     * **调用方传进来的 messages 不应该再自己加 system**，否则会变成两条。
     */
    fun chat(
        account: CodeBuddyAccount,
        model: String,
        messages: List<com.snote.feature.llm.LlmMessage>,
        tools: List<String> = emptyList(),
    ): Flow<LlmChunk> = callbackFlow {
        val payload = buildBody(model, messages, tools)
        val request = Request.Builder()
            .url(BASE + "/v2/chat/completions")
            .apply { (baseHeaders(model) + mapOf("Authorization" to "Bearer " + account.accessToken))
                .forEach { (k, v) -> addHeader(k, v) } }
            .addHeader("Accept", "text/event-stream")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()

        val call = http.newCall(request)
        val job = launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val body = try { resp.body?.string()?.take(300) ?: "" } catch (t: Throwable) { "" }
                        trySend(LlmChunk.Failed("HTTP " + resp.code + " " + body))
                        return@use
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        trySend(LlmChunk.Failed("响应体为空"))
                        return@use
                    }
                    // 工具调用分片：流式下 name 先到、arguments 一段段到，
                    // 必须按 index 拼起来才是完整 JSON（否则每片都是一个坏 JSON）。
                    val argBuf = HashMap<Int, StringBuilder>()
                    val names = HashMap<Int, String>()
                    val callIds = HashMap<Int, String>()

                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") continue
                        val obj = try { JSONObject(data) } catch (t: Throwable) { continue }
                        val choice = obj.optJSONArray("choices")?.optJSONObject(0) ?: continue
                        val delta = choice.optJSONObject("delta") ?: continue

                        val text = delta.optString("content", "")
                        if (text.isNotEmpty()) trySend(LlmChunk.Delta(text))

                        delta.optJSONArray("tool_calls")?.let { tc ->
                            for (i in 0 until tc.length()) {
                                val one = tc.optJSONObject(i) ?: continue
                                val idx = one.optInt("index", i)
                                one.optString("id").takeIf { it.isNotEmpty() }?.let { callIds[idx] = it }
                                one.optJSONObject("function")?.let { fn ->
                                    fn.optString("name").takeIf { it.isNotEmpty() }?.let { names[idx] = it }
                                    fn.optString("arguments").takeIf { it.isNotEmpty() }?.let {
                                        argBuf.getOrPut(idx) { StringBuilder() }.append(it)
                                    }
                                }
                            }
                        }
                    }

                    argBuf.keys.sorted().forEach { idx ->
                        trySend(
                            LlmChunk.ToolCall(
                                id = callIds[idx] ?: "",
                                name = names[idx] ?: "",
                                argumentsJson = argBuf[idx]?.toString() ?: "",
                            )
                        )
                    }
                }
                trySend(LlmChunk.Done)
            } catch (t: Throwable) {
                trySend(LlmChunk.Failed(t.message ?: t.javaClass.simpleName))
            } finally {
                close()
            }
        }
        awaitClose { call.cancel(); job.cancel() }
    }

    private fun buildBody(model: String, messages: List<com.snote.feature.llm.LlmMessage>, tools: List<String>): String {
        val root = JSONObject()
        root.put("model", model)
        // 硬约束 1：服务端只接受流式（非流式报 11101）。
        root.put("stream", true)

        val arr = JSONArray()
        // 硬约束 2：首条必须是 system（否则报 11128）。调用方没给就补一条。
        if (messages.firstOrNull()?.role != "system") {
            arr.put(JSONObject().put("role", "system").put("content", DEFAULT_SYSTEM))
        }
        for (m in messages) {
            val o = JSONObject().put("role", m.role)
            if (m.images.isEmpty()) {
                // 纯文本照旧发字符串。**不要**顺手也包成数组：
                // 服务端对纯文本字符串是稳定路径，改形态属于无谓的风险。
                o.put("content", m.content)
            } else {
                // 有图时 content 必须是数组：text 在前、image_url 在后。
                // 实测（2026-10-11）这个形态被接受，模型能正确读出图片内容。
                val parts = JSONArray()
                if (m.content.isNotEmpty()) {
                    parts.put(JSONObject().put("type", "text").put("text", m.content))
                }
                m.images.forEach { url ->
                    parts.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", url))
                    )
                }
                o.put("content", parts)
            }
            m.toolCallId?.let { o.put("tool_call_id", it) }
            m.toolCallsJson?.let { o.put("tool_calls", JSONArray(it)) }
            arr.put(o)
        }
        root.put("messages", arr)

        if (tools.isNotEmpty()) {
            val t = JSONArray()
            tools.forEach { t.put(JSONObject(it)) }
            root.put("tools", t)
        }
        return root.toString()
    }

    // ==================== 底层 ====================

    private fun authHeaders(account: CodeBuddyAccount): Map<String, String> =
        baseHeaders(null) + mapOf(
            "Authorization" to "Bearer " + account.accessToken,
            // 实测：X-Product 这里是**部署类型**（SaaS），不是产品名。
            // 历史实现误发成产品名导致后台「使用端」列归因不到。
            "X-Product" to "SaaS",
        )

    private fun get(path: String, headers: Map<String, String>): JSONObject? = postRaw(path, headers, null)?.second

    private fun post(path: String, headers: Map<String, String>, body: JSONObject?): JSONObject? =
        postRaw(path, headers, body)?.second

    private fun postRaw(path: String, headers: Map<String, String>, body: JSONObject?): Pair<Int, JSONObject>? {
        return try {
            val builder = Request.Builder().url(BASE + path)
            headers.forEach { (k, v) -> builder.addHeader(k, v) }
            if (body == null) builder.get() else builder.post(body.toString().toRequestBody(JSON_MEDIA))
            http.newCall(builder.build()).execute().use { resp ->
                val text = try { resp.body?.string() ?: "" } catch (t: Throwable) { "" }
                val json = try { JSONObject(text) } catch (t: Throwable) { JSONObject() }
                resp.code to json
            }
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        const val HOST = "copilot.tencent.com"
        const val BASE = "https://" + HOST
        const val PLATFORM = "ide"
        const val CLIENT_VERSION = "1.106.1"

        private const val CODE_TOKEN_NOT_READY = 11217
        private const val CODE_ACCOUNT_NOT_READY = 12151
        private const val POLL_MS = 1500L
        private const val DEFAULT_SYSTEM = "You are a helpful assistant."

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /**
         * User-Agent。
         *
         * 中国版只有一条产品线，**不按模型族分档** —— 全部模型沿用同一个 IDE UA。
         * （国际版 WorkBuddy 才需要按模型族分档；那是它独有的需求，不要照搬过来。）
         *
         * 参数保留 model 是为了与调用点签名一致，也让将来真出现分档需求时好改。
         */
        @Suppress("UNUSED_PARAMETER")
        fun userAgentFor(model: String?): String = "CodeBuddyIDE/1.106.1"

        /** 所有请求共用的身份头。缺任一个都会让后台归因失败或直接拒绝。 */
        fun baseHeaders(model: String?): Map<String, String> = linkedMapOf(
            "X-Domain" to HOST,
            "X-Product-Code" to "codebuddy",
            "X-Product" to "CodeBuddy",
            "X-IDE-Name" to "CodeBuddy",
            "X-IDE-Type" to "CodeBuddy",
            "X-IDE-Version" to CLIENT_VERSION,
            "X-Agent-Purpose" to "conversation",
            "User-Agent" to userAgentFor(model),
        )

        /**
         * 登录 URL 装饰。
         *
         * 中国版**不做任何追加**，原样返回 —— `version` 与 `loginSessionId`
         * 是国际版登录站的要求，多发给中国版 portal 会被当成异常。
         *
         * 保留这个函数（而不是删掉直接传 authUrl）是为了让调用点与产品差异解耦：
         * 将来加国际版只需换实现，不用改调用方。
         */
        fun decorateLoginUrl(authUrl: String): String = authUrl

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}