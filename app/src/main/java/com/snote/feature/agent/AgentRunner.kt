package com.snote.feature.agent

import com.snote.feature.attachments.AttachmentStore
import com.snote.feature.chat.SystemPrompt
import com.snote.feature.llm.LlmChunk
import com.snote.feature.llm.LlmGateway
import com.snote.feature.llm.LlmMessage
import com.snote.feature.notes.NoteRepository
import com.snote.feature.web.WebSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * agent 跑一轮对话时产生的事件。
 *
 * 刻意用事件流而不是「返回最终字符串」：工具调用、待审拦截、错误都必须**当场**
 * 让用户看见。若攒到最后一起返回，用户在等待期间完全不知道 agent 在干什么，
 * 而一轮里可能有 5 次工具调用、耗时十几秒。
 */
sealed interface AgentEvent {
    /** 模型输出的文字增量。 */
    data class Delta(val text: String) : AgentEvent

    /** agent 调用了一个只读工具，已执行。 */
    data class ToolUsed(val tool: String, val argsJson: String, val result: String) : AgentEvent

    /** agent 想执行写操作，已进待审队列等用户点同意。 */
    data class Queued(val op: PendingOp) : AgentEvent

    data class Failed(val message: String) : AgentEvent

    data object Done : AgentEvent
}

/**
 * agent 的一轮对话。
 *
 * ## 循环结构
 *
 * 模型可能连续调工具：说一句 → 调 kb_search → 拿结果 → 再调 kb_get_doc → 再回答。
 * 所以这里是个循环，每圈把「assistant 的 tool_calls + 每个 tool 的结果」追加进
 * messages 再问一次，直到模型不再调工具为止。
 *
 * ## 死循环防护
 *
 * 模型偶尔会反复调同一个工具停不下来。限制 [MAX_ROUNDS] 圈，超出就收尾并说明 ——
 * 没有上限的话，一次对话能把额度烧光。
 *
 * ## 写操作不执行
 *
 * 遇到写工具只入待审队列，然后**把「已排队等用户确认」作为工具结果回给模型**。
 * 这样模型知道自己的请求收到了、正在等人点同意，不会以为失败而重试。
 * 真正的执行发生在用户点同意之后（见 PendingStore 的注释）。
 *
 * ## system 提示词在这里补
 *
 * [run] 收到的 history 全是 user/assistant，应用自己的 system 内容（身份 + 当天日期）
 * 在这里拼在**最前面**。放在这一层而不是对话页，是因为「模型需要知道什么」属于
 * agent 循环的职责 —— 而日期必须每轮现取，历史里存的那份会过期。
 *
 * ## 联网搜索按开关决定要不要暴露
 *
 * 工具声明由 [KbTools.declarations] 按 [webSearchEnabled] 组装：关掉时模型根本
 * 看不到 web_search。这比「发了声明再拒绝执行」好 —— 后者会让模型反复重试一个
 * 注定失败的调用，白白烧掉圈数。
 */
class AgentRunner(
    private val repo: NoteRepository,
    private val pending: PendingStore,
    private val gatewayProvider: () -> LlmGateway?,
    private val sessionId: () -> Long,
    /** 附件存储；kb_list_attachments 用它。为 null 时该工具返回「不可用」。 */
    private val attachments: AttachmentStore? = null,
    /**
     * 联网搜索开关。由功能框架决定（FeatureRegistry.WEB_SEARCH 自身开着且依赖满足）。
     *
     * 关闭时不只是拒绝调用，而是**连工具声明都不发** —— 模型看不到它就不会去调。
     * 传 true 却在这里拦下来是更糟的设计：模型会反复重试一个永远失败的调用。
     */
    private val webSearchEnabled: () -> Boolean = { false },
    /** 搜索器实例（需要 Context，只能从外面注入）。为 null 时该工具报「不可用」。 */
    private val web: WebSearch? = null,
    /**
     * 长期记忆。每轮把它拼进 system 提示词 —— 这是「全局」二字的落点。
     *
     * 用 lambda 而不是直接传字符串：记忆会在会话进行中被用户编辑（比如刚
     * 批准了一条），而 runner 实例是复用的。传值会让它一直用创建时那份快照。
     */
    private val memorySection: () -> String = { "" },
) {

    fun run(model: String, history: List<LlmMessage>): Flow<AgentEvent> = flow {
        val gw = gatewayProvider()
        if (gw == null) {
            emit(AgentEvent.Failed("尚未登录。到「设置」里添加 CodeBuddy 账号。"))
            emit(AgentEvent.Done)
            return@flow
        }

        // 工作副本：循环中不断追加 assistant / tool 消息。
        //
        // system 拼在最前：CodeBuddyClient.buildBody 只在首条不是 system 时才补兜底，
        // 这里给了它就把默认那句顶掉了。日期每轮现取 —— 历史里存的那份会过期。
        val messages = ArrayList<LlmMessage>(history.size + 1)
        // 联网开关先取一次：这一轮的「system 怎么说」与「发哪些工具声明」必须一致，
        // 中途读两次万一被用户改了，就会出现「说能搜但不给工具」的矛盾组合。
        // 注意别叫 web：那会遮蔽构造函数的 web（WebSearch 实例），
        // 表现为「Boolean 传给需要 WebSearch? 的参数」。
        val webOn = webSearchEnabled()
        messages.add(
            LlmMessage(
                role = "system",
                content = SystemPrompt.build(webEnabled = webOn, memorySection = memorySection()),
            )
        )
        messages.addAll(history)

        for (round in 0 until MAX_ROUNDS) {
            val calls = ArrayList<LlmChunk.ToolCall>()
            val textBuf = StringBuilder()

            try {
                // 记忆开关与 web 同理：关掉时连声明都不发，模型看不到就不会去申请。
                gw.chat(model, messages, KbTools.declarations(webOn, memorySection().isNotEmpty()))
                    .collect { chunk ->
                    when (chunk) {
                        is LlmChunk.Delta -> {
                            textBuf.append(chunk.text)
                            emit(AgentEvent.Delta(chunk.text))
                        }
                        is LlmChunk.ToolCall -> calls.add(chunk)
                        is LlmChunk.Failed -> emit(AgentEvent.Failed(chunk.message))
                        is LlmChunk.Done -> Unit
                    }
                }
            } catch (t: Throwable) {
                emit(AgentEvent.Failed(t.message ?: t.javaClass.simpleName))
                emit(AgentEvent.Done)
                return@flow
            }

            // 模型没调工具 = 它说完了，这一轮到此为止。
            if (calls.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }

            // 把 assistant 这条带 tool_calls 的消息记进历史，否则模型下一圈
            // 看不到自己刚才请求过什么，会重复请求。
            messages.add(
                LlmMessage(
                    role = "assistant",
                    content = textBuf.toString(),
                    toolCallsJson = buildToolCallsJson(calls),
                )
            )

            // 逐个执行/排队，并把结果作为 tool 消息回给模型。
            // 分发写在这里（而不是抽成 handleCall）是因为它要 emit —— emit 只在
            // flow 的收集上下文里合法，抽出去就必须靠缓冲区绕，反而更容易出错。
            for (call in calls) {
                if (call.name.isBlank()) {
                    messages.add(
                        LlmMessage(role = "tool", content = "工具调用缺少名称，已忽略。", toolCallId = call.id)
                    )
                    continue
                }

                val result: String
                if (KbTools.isWrite(call.name)) {
                    // 写操作：只入队，不执行。
                    val summary = KbTools.summarize(repo, call.name, call.argumentsJson)
                    val opId = pending.add(sessionId(), call.name, call.argumentsJson, summary)
                    val op = PendingOp(
                        id = opId,
                        sessionId = sessionId(),
                        tool = call.name,
                        argsJson = call.argumentsJson,
                        summary = summary,
                        createdAt = System.currentTimeMillis(),
                    )
                    emit(AgentEvent.Queued(op))
                    result = "已加入待审队列，等用户在界面上点「同意」后才会执行。操作：" + summary
                } else {
                    // suspend：web_search 会真的发 HTTP（自带超时与降级），
                    // 其余 kb_* 分支仍是本地同步调用，只是统一从这个入口走。
                    // 它耗时期间本 flow 挂起，UI 侧表现为「正在调用工具」，符合预期。
                    result = KbTools.runReadOnly(repo, call.name, call.argumentsJson, attachments, web)
                    emit(AgentEvent.ToolUsed(call.name, call.argumentsJson, result))
                }

                messages.add(LlmMessage(role = "tool", content = result, toolCallId = call.id))
            }
        }

        // 圈数用尽仍未收敛。
        emit(
            AgentEvent.Failed(
                "已达到单轮工具调用上限（" + MAX_ROUNDS + " 次），先停在这里。" +
                    "请把需求拆小一点再问，或直接告诉我要做什么。"
            )
        )
        emit(AgentEvent.Done)
    }

    companion object {
        /**
         * 单轮最多几次工具调用。
         *
         * 取 8：正常问答 1-3 次足够（搜索 → 读文档 → 回答），留出余量给
         * 「多篇文档对比」这类需求，又不至于让一次失控的对话烧掉太多额度。
         */
        const val MAX_ROUNDS = 8
    }
}

/** 把若干工具调用拼成 assistant 消息里的 tool_calls 字段。 */
private fun buildToolCallsJson(calls: List<LlmChunk.ToolCall>): String {
    val arr = JSONArray()
    calls.forEach { c ->
        arr.put(
            JSONObject().apply {
                put("id", c.id)
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", c.name)
                    // 参数必须是**字符串**而不是对象，否则服务端解析失败。
                    put("arguments", c.argumentsJson.ifBlank { "{}" })
                })
            }
        )
    }
    return arr.toString()
}
