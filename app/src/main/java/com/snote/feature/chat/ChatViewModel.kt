package com.snote.feature.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snote.core.feature.FeatureController
import com.snote.core.feature.FeatureRegistry
import com.snote.core.settings.ChoiceStore
import com.snote.feature.agent.AgentEvent
import com.snote.feature.agent.AgentRunner
import com.snote.feature.agent.KbToolExecutor
import com.snote.feature.agent.PendingOp
import com.snote.feature.agent.PendingStore
import com.snote.feature.attachments.AttachmentStore
import com.snote.feature.doc.DocText
import com.snote.feature.llm.ImageEncoder
import com.snote.feature.llm.LlmGateway
import com.snote.feature.llm.LlmMessage
import com.snote.feature.llm.ModelCatalog
import com.snote.feature.llm.ModelVision
import com.snote.feature.memory.MemoryStore
import com.snote.feature.notes.NoteRepository
import com.snote.feature.web.WebSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 对话页的状态持有者。
 *
 * ## 为什么必须有它（这是三个 bug 的共同根因）
 *
 * 之前所有状态都放在 ChatScreen 的 remember 里，而 AppRoot 用 when(tab) 切主窗口
 * —— 切走时组件离开组合，**remember 全丢、rememberCoroutineScope 被取消**。
 * 由此长出三个症状：
 *
 *   1. 输入框内容切页就丢（当时用 ChoiceStore 单独补了这一个）
 *   2. 待发的图片/文件切页就丢（补漏了）
 *   3. **正在生成的回答被中断并报错** —— rememberCoroutineScope 一取消，
 *      正在跑的流式请求当场断掉
 *
 * 前两个是「状态放错了地方」，第三个是「协程放错了地方」，治本办法是同一个：
 * 把状态与协程都挪到一个**生命周期长于组件**的持有者里。ViewModel 正是这个角色
 * —— 它跟着 Activity 活，配置变更与页面切换都不影响它。
 *
 * ## 为什么用 AndroidViewModel 而不是普通 ViewModel
 *
 * 它要 Context 来建 ChatStore / PendingStore / ImageEncoder 这些。用
 * applicationContext，绝不持有 Activity —— 那才是内存泄漏。
 *
 * ## 什么该放这里，什么不该
 *
 * 放这里：会被异步任务改写、或需要跨页面存活的东西（消息列表、发送中标志、
 * 待发附件、正在跑的 Job）。
 * 不放这里：纯界面开关（菜单展开没展开、哪个弹层开着）—— 那些切页丢了正好。
 */
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val context = app.applicationContext

    val store = ChatStore(context)
    val choices = ChoiceStore(context)
    val pendingStore = PendingStore(context)
    private val features = FeatureController(context)
    private val web = WebSearch(context)

    /** 长期记忆。每轮注入 system 提示词 —— 「全局」二字的落点。 */
    private val memory = MemoryStore(context)

    /** 记忆是否可用（开关开着）。关掉时既不注入也不发工具声明。 */
    private fun memoryOn(): Boolean = features.isUsable(FeatureRegistry.MEMORY)

    /** 消息列表。用 mutableStateListOf 而不是普通 list，Compose 才能观察到增删改。 */
    val lines = mutableStateListOf<ChatLine>()

    /** 待发图片（data URL）与对应的缩略图。 */
    val attached = mutableStateListOf<Attachment>()

    /** 待发文件（已抽成文本）。 */
    val attachedDocs = mutableStateListOf<AttachedDoc>()

    /** 输入框内容。落库的原因见 ChoiceStore.CHAT_DRAFT 的注释。 */
    var input by mutableStateOf(choices.get(ChoiceStore.CHAT_DRAFT) ?: "")

    /** 是否正在生成。切页回来要能接着看到「还在生成」。 */
    var busy by mutableStateOf(false)
        private set

    /** 一行提示（成功/失败/进度）。 */
    var notice by mutableStateOf<String?>(null)

    /** 当前会话 id；0 = 尚未落库的新会话。 */
    var sessionId by mutableStateOf(0L)
        private set

    /** 当前模型。 */
    var model by mutableStateOf(choices.get(ChoiceStore.MODEL) ?: DEFAULT_CODEBUDDY_MODEL)

    /** 还活着的待审项 id —— 卡片该不该有按钮只认它。 */
    var pendingIds by mutableStateOf(pendingStore.list().map { it.id }.toSet())
        private set

    var pendingCount by mutableStateOf(pendingStore.count())
        private set

    /** 已展开的工具行（按内容 hashCode 标识，避免消息追加时下标漂移）。 */
    var expandedTools by mutableStateOf(setOf<Int>())

    /**
     * **正在进行的这一轮**。
     *
     * 单独持有它是为了「切页回来能看到还在跑」之外，还为了**避免重复发送**：
     * 用 viewModelScope 直接 launch 也能活，但那样就没有句柄可以判断
     * 「现在有没有在跑」了。busy 标志是给界面看的，这个 Job 是给逻辑用的。
     */
    private var runningJob: Job? = null

    private var runner: AgentRunner? = null

    companion object {
        /** 默认模型：实测可用的那个，列表拉不到时也能开聊。 */
        const val DEFAULT_CODEBUDDY_MODEL = "hy4-preview"
    }

    /** 一张待发图片：发给模型的图 + 存历史用的缩略图。 */
    data class Attachment(val dataUrl: String, val thumb: String)

    /** 一份待发文件。 */
    data class AttachedDoc(val name: String, val text: String, val note: String)

    // ---------- 会话 ----------

    fun openSession(id: Long) {
        sessionId = id
        choices.put(ChoiceStore.LAST_SESSION, id.toString())
        lines.clear()
        lines.addAll(store.loadMessages(id))
        store.listSessions().firstOrNull { it.id == id }?.model
            ?.takeIf { it.isNotBlank() }
            ?.let { model = it }
        notice = null
    }

    fun newSession() {
        sessionId = 0L
        choices.remove(ChoiceStore.LAST_SESSION)
        lines.clear()
        notice = null
    }

    /**
     * 恢复上次的会话。
     *
     * 与之前那版的关键差别：**已经恢复过就不再恢复**。ViewModel 活得比组件长，
     * 切页回来时 lines 里还留着上次的内容，若照旧重载，屏幕上正在流式输出的
     * 半句话会被库里的旧快照覆盖掉。
     */
    fun restoreLastSession() {
        if (lines.isNotEmpty()) return
        val savedId = choices.get(ChoiceStore.LAST_SESSION)?.toLongOrNull() ?: 0L
        if (savedId <= 0L) return
        if (store.listSessions().none { it.id == savedId }) {
            choices.remove(ChoiceStore.LAST_SESSION)
            return
        }
        openSession(savedId)
    }

    // ---------- 待审 ----------

    fun refreshPending() {
        val ids = pendingStore.list().map { it.id }.toSet()
        pendingIds = ids
        pendingCount = ids.size
    }

    fun replaceQueued(opId: Long, text: String) {
        val at = lines.indexOfFirst { it.role == "queued" && it.opId == opId }
        if (at >= 0) lines[at] = ChatLine("tool", text)
    }

    /** 就地审批：执行一次写操作并把卡片换成结果行。 */
    fun approveInline(index: Int, opId: Long, repo: NoteRepository) {
        val op = pendingStore.get(opId)
        if (op == null) {
            lines[index] = ChatLine("tool", "这条已处理过了。")
            return
        }
        val result = KbToolExecutor.runWrite(repo, op.tool, op.argsJson, memory = memory)
        pendingStore.remove(op.id)
        refreshPending()
        lines[index] = ChatLine("tool", "已执行 " + op.tool + "：" + result)
        notice = result
    }

    fun rejectInline(index: Int, opId: Long, text: String) {
        pendingStore.get(opId)?.let { pendingStore.remove(it.id) }
        refreshPending()
        lines[index] = ChatLine("tool", "已拒绝：" + text)
    }

    fun approveFromSheet(op: PendingOp, repo: NoteRepository) {
        val result = KbToolExecutor.runWrite(repo, op.tool, op.argsJson, memory = memory)
        pendingStore.remove(op.id)
        refreshPending()
        replaceQueued(op.id, "已执行 " + op.tool + "：" + result)
        lines.add(ChatLine("tool", "已执行 " + op.tool + "：" + result))
        notice = result
    }

    fun rejectFromSheet(op: PendingOp) {
        pendingStore.remove(op.id)
        refreshPending()
        replaceQueued(op.id, "已拒绝：" + op.summary)
        lines.add(ChatLine("tool", "已拒绝：" + op.summary))
    }

    // ---------- 附件 ----------

    /**
     * 改输入框内容并落草稿。
     *
     * 不叫 setInput —— Kotlin 会为 var input 自动生成 setInput(String)，
     * 同名会撞 JVM 签名（Platform declaration clash）。名字带 Edit 也更准确：
     * 它同时做了「改状态」与「存草稿」两件事。
     */
    fun onInputEdit(v: String) {
        input = v
        choices.put(ChoiceStore.CHAT_DRAFT, v)
    }

    /** 加一张图。在 IO 线程解码缩放，完成后把两份 data URL 都留下。 */
    suspend fun addImage(uri: Uri) {
        val enc = withContext(Dispatchers.IO) {
            ImageEncoder.fromUri(context, uri)
        }
        if (enc == null) {
            notice = "图片读取失败。"
            return
        }
        attached.add(Attachment(enc.dataUrl, enc.thumbDataUrl))
        notice = "已添加图片 " + enc.width + "x" + enc.height + "（" + (enc.bytes / 1024) + " KB）"
    }

    /** 加一份文件：抽文字。 */
    suspend fun addDoc(uri: Uri, name: String, mime: String?) {
        when (val r = DocText.extract(context, uri, name, mime)) {
            is DocText.DocParseResult.Ok -> {
                attachedDocs.add(
                    AttachedDoc(
                        name = name,
                        text = r.text,
                        note = "已附加「" + name + "」（" + r.text.length + " 字" +
                            (if (r.truncated) "，已截断" else "") + "）",
                    )
                )
                notice = attachedDocs.last().note
            }
            DocText.DocParseResult.NeedsVision -> {
                notice = "「" + name + "」没有文字层（扫描件），暂时读不了内容。"
            }
            is DocText.DocParseResult.Unsupported -> notice = r.message
        }
    }

    fun removeImage(i: Int) {
        if (i in attached.indices) attached.removeAt(i)
    }

    fun removeDoc(i: Int) {
        if (i in attachedDocs.indices) attachedDocs.removeAt(i)
    }

    // ---------- 发送 ----------

    /**
     * 发一轮。
     *
     * @param gateway 由界面提供（它持有登录态与刷新回写），每次取一次最新值。
     */
    fun send(repo: NoteRepository, gateway: () -> LlmGateway?) {
        val text = input.trim()
        if (busy) return
        if (text.isEmpty() && attached.isEmpty() && attachedDocs.isEmpty()) return

        val gw = gateway()
        if (gw == null) {
            notice = "还没登录。点右上角「设置」添加 CodeBuddy 账号。"
            return
        }
        notice = null

        // 转交判定：当前模型不确定能识图时，含图的那一轮换用识图模型。
        // 判据用「确定支持」而不是「确定不支持」——服务端两端点声明会互相矛盾。
        val visionModel = choices.get(ChoiceStore.VISION_MODEL) ?: ChoiceStore.DEFAULT_VISION_MODEL
        val switched = attached.isNotEmpty() && !ModelVision.certainlySupports(model)
        val runModel = if (switched) visionModel else model

        val sending = attached.toList()
        val docs = attachedDocs.toList()
        attached.clear()
        attachedDocs.clear()
        input = ""
        choices.remove(ChoiceStore.CHAT_DRAFT)

        val withDocs = if (docs.isEmpty()) text else buildString {
            if (text.isNotEmpty()) append(text).append("\n\n")
            docs.forEach { d ->
                append("【文件：").append(d.name).append("】\n")
                append(d.text).append("\n\n")
            }
        }.trimEnd()

        lines.add(
            ChatLine(
                role = "user",
                text = withDocs,
                images = sending.map { it.dataUrl },
                thumbs = sending.map { it.thumb },
            )
        )
        docs.forEach { lines.add(ChatLine("tool", it.note)) }
        if (switched) {
            lines.add(
                ChatLine(
                    "tool",
                    "已用 " + runModel + " 识别图片（当前模型 " + model +
                        " 不确定能否识图）。下一轮会自动切回。"
                )
            )
        }

        val placeholder = lines.size
        lines.add(ChatLine("assistant", ""))
        busy = true

        // 用 viewModelScope：它跟着 ViewModel 活，切页不会取消。
        // 这正是「切页导致回答中断」的修复点。
        runningJob = viewModelScope.launch {
            val history = lines.take(placeholder)
                .filter { it.role == "user" || it.role == "assistant" }
                .map { LlmMessage(it.role, it.text, images = it.images) }
            try {
                runner(repo, gateway).run(runModel, history).collect { ev ->
                    when (ev) {
                        is AgentEvent.Delta -> lines[placeholder] =
                            ChatLine("assistant", lines[placeholder].text + ev.text)
                        is AgentEvent.ToolUsed ->
                            lines.add(ChatLine("tool", toolLine(ev.tool, ev.argsJson, ev.result)))
                        is AgentEvent.Queued -> {
                            lines.add(ChatLine("queued", ev.op.summary, ev.op.id))
                            refreshPending()
                        }
                        is AgentEvent.Failed -> lines[placeholder] =
                            ChatLine("assistant", "错误：" + ev.message)
                        is AgentEvent.Done -> Unit
                    }
                }
            } catch (t: Throwable) {
                // 协程被取消不是错误（用户可能主动新开会话），别显示成失败。
                if (t !is kotlinx.coroutines.CancellationException) {
                    lines[placeholder] = ChatLine("assistant", "错误：" + (t.message ?: "未知"))
                }
            } finally {
                busy = false
                persist()
            }
        }
    }

    /** 中途停下正在生成的回答。 */
    fun stop() {
        runningJob?.cancel()
        runningJob = null
        busy = false
    }

    /** 一轮结束后落盘：用户消息 + 完整回复一起写。 */
    private fun persist() {
        try {
            if (sessionId == 0L) {
                val title = store.autoTitle(
                    lines.firstOrNull { it.role == "user" }?.text.orEmpty()
                )
                sessionId = store.createSession(title, model)
                choices.put(ChoiceStore.LAST_SESSION, sessionId.toString())
            } else {
                store.touchSession(sessionId, model)
            }
            store.saveMessages(sessionId, lines)
        } catch (t: Throwable) {
            notice = "对话已显示，但没能保存到历史：" + (t.message ?: "未知")
        }
    }

    /**
     * 懒建 agent 循环。
     *
     * 每次 send 时重建而不是 remember 一个：它的构造参数里有 gatewayProvider，
     * 而登录态可能在设置页被换掉 —— 旧的闭包会拿着已失效的账号。
     */
    private fun runner(repo: NoteRepository, gateway: () -> LlmGateway?): AgentRunner =
        runner ?: AgentRunner(
            repo = repo,
            pending = pendingStore,
            gatewayProvider = gateway,
            sessionId = { sessionId },
            attachments = AttachmentStore(context),
            webSearchEnabled = { features.isUsable(FeatureRegistry.WEB_SEARCH) },
            web = web,
            // 现读而不是传值：用户刚在设置里批准了一条记忆，
            // 下一轮就该生效 —— 而 runner 实例是复用的。
            memorySection = { if (memoryOn()) memory.promptSection() else "" },
        ).also { runner = it }

    /** 换模型。 */
    fun selectModel(m: String) {
        model = m
        choices.put(ChoiceStore.MODEL, m)
        if (sessionId != 0L) store.touchSession(sessionId, m)
    }

    /** 删会话。 */
    fun deleteSession(id: Long) {
        store.deleteSession(id)
        if (id == sessionId) newSession()
    }
}
