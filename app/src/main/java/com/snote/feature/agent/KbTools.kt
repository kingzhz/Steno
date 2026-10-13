package com.snote.feature.agent

import com.snote.feature.attachments.AttachmentStore
import com.snote.feature.notes.CardDraft
import com.snote.feature.web.WebSearch
import com.snote.feature.notes.DocDraft
import com.snote.feature.notes.NoteRepository
import org.json.JSONArray
import org.json.JSONObject

/**
 * agent 能对知识库做的事。
 *
 * ## 为什么工具声明与执行放在同一个文件
 *
 * 工具的 JSON Schema 和它的实现**必须一起改**：只改 schema 会让模型按新参数调用、
 * 而实现还在读旧键，表现为「模型明明传了参数，执行却说没收到」。放一起能一眼看到。
 *
 * ## 读写分权
 *
 * 读操作（search/list/get）直接执行 —— 它们不改任何数据，多问一次只是烦人。
 * 写操作（create/update/delete）**不在这里执行**：本类只负责把调用原样记进
 * pending_op 表，等用户在界面上点同意。执行写操作的是 [KbToolExecutor]。
 *
 * 这样分的结果是：agent 永远无法在用户不知情时改动知识库。
 *
 * ## 唯一的例外：web_search
 *
 * 它也是只读，但会**出网**，所以有两条额外纪律：受功能开关控制（关掉时声明都不发，
 * 见 [declarations]），以及失败必须自报家门而不是伪装成「没结果」——
 * 搜索失败与「搜到了但无关」对模型的含义完全不同，混为一谈会让它基于空结果下结论。
 */
object KbTools {

    // ==================== 工具声明 ====================

    /**
     * 联网搜索的工具名。
     *
     * 单独拎出来是因为它是**唯一一个会出网的只读工具**：其余 kb_* 都只碰本地 SQLite，
     * 而它会发 HTTP、有超时、可能失败，也会把内容带出设备。因此它受功能开关控制，
     * 关掉时连声明都不发给模型（见 [declarations]）—— 声明了却拒绝执行比不声明更糟，
     * 模型会反复重试一个永远失败的调用。
     */
    const val WEB_SEARCH = "web_search"

    /** 「记住一条」的工具名。与 kb_create_doc 同属写工具，走待审。 */
    const val REMEMBER = "kb_remember"

    /** 给模型看的工具清单（OpenAI function-calling 格式的 JSON 字符串）。 */
    private val CORE_DECLARATIONS: List<String> = listOf(
        fn(
            "kb_search",
            "在知识库里检索卡片。返回命中的卡片标题、所属文档与摘要。不确定库里有什么时先用它。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", prop("string", "检索词，可以是关键词或一句话"))
                    put("limit", prop("integer", "最多返回几条，默认 10"))
                })
                put("required", JSONArray().put("query"))
            }
        ),
        fn(
            "kb_list_docs",
            "列出知识库里所有文档的标题与卡片数量。用于了解知识库全貌。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        fn(
            "kb_get_doc",
            "读取一篇文档的全部卡片内容。修改文档前必须先读它。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("doc_id", prop("integer", "文档 id，从 kb_list_docs 或 kb_search 得到"))
                })
                put("required", JSONArray().put("doc_id"))
            }
        ),
        fn(
            "kb_list_attachments",
            "列出知识库里的附件（用户上传的资料）。回答涉及原文依据的问题前先看它。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        fn(
            "kb_create_doc",
            "新建一篇文档。会进入待审，用户同意后才真正写入。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("title", prop("string", "文档标题"))
                    put("cards", JSONObject().apply {
                        put("type", "array")
                        put("description", "卡片列表，每项含 title 与 body")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("title", prop("string", "卡片标题"))
                                put("body", prop("string", "卡片正文"))
                            })
                            put("required", JSONArray().put("title").put("body"))
                        })
                    })
                })
                put("required", JSONArray().put("title"))
            }
        ),
        fn(
            "kb_update_doc",
            "修改一篇已有文档的标题或卡片内容。会进入待审。传入的 cards 会**整篇替换**原有卡片，" +
                "所以要先 kb_get_doc 拿到现有内容再改。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("doc_id", prop("integer", "要改的文档 id"))
                    put("title", prop("string", "新标题，不改就省略"))
                    put("cards", JSONObject().apply {
                        put("type", "array")
                        put("description", "替换后的完整卡片列表")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put("title", prop("string", "卡片标题"))
                                put("body", prop("string", "卡片正文"))
                            })
                            put("required", JSONArray().put("title").put("body"))
                        })
                    })
                })
                put("required", JSONArray().put("doc_id"))
            }
        ),
        fn(
            "kb_delete_doc",
            "删除一篇文档及其全部卡片。会进入待审，且不可恢复。",
            JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("doc_id", prop("integer", "要删除的文档 id"))
                })
                put("required", JSONArray().put("doc_id"))
            }
        ),
    )

    /**
     * 联网搜索的声明。
     *
     * 与知识库工具分开维护，因为它受开关控制：关掉时整条声明都不发。
     */
    private val WEB_SEARCH_DECLARATION: String = fn(
        WEB_SEARCH,
        "联网搜索互联网。知识库之外、或需要**最新信息**时才用它：" +
            "新闻时事、当前日期、实时价格、新发布的版本与文档等。" +
            "返回若干条标题、链接与摘要，引用时请带上链接。",
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", prop("string", "搜索词，用关键词或一句话描述要查什么"))
                put("limit", prop("integer", "最多返回几条，默认 5，上限 8"))
            })
            put("required", JSONArray().put("query"))
        }
    )

    /**
     * 「记住这条」的声明。
     *
     * ## 为什么它是写工具（走待审）
     *
     * 它影响的是**之后每一轮对话**，而不是某一份文档。模型误记一条
     * 「用户喜欢简短回答」，此后每次回答都会受影响 —— 这比误建一篇文档
     * 严重得多，而且用户很难定位到原因。所以必须由用户点头。
     *
     * ## 为什么描述里强调「只记长期成立的事」
     *
     * 模型很容易把「这次问的那篇文档标题」也当成记忆记下来。那不是记忆，
     * 是上下文 —— 写进长期记忆反而会污染后续所有对话。
     */
    private val REMEMBER_DECLARATION: String = fn(
        REMEMBER,
        "记住一条关于用户的长期事实（身份、偏好、习惯、长期项目等），" +
            "之后所有对话都会用到。**只记长期成立的事**，不要记这次对话的临时内容" +
            "（某篇文档的标题、刚问的某个问题）。会进入待审，等用户同意后才真正写入。" +
            "不要记录用户明确说过要保密的内容。",
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("text", prop("string", "要记住的一句话，用陈述句写清事实本身，例如「用户是 Android 开发者」"))
            })
            put("required", JSONArray().put("text"))
        }
    )

    /**
     * 组装本次请求的工具声明。
     *
     * @param webEnabled 联网搜索开关是否打开。关闭时不发它的声明 ——
     *   只发 kb_* 那 7 个，模型看不到它自然不会去调。
     */
    fun declarations(webEnabled: Boolean, memoryEnabled: Boolean = false): List<String> {
        val out = ArrayList<String>(CORE_DECLARATIONS)
        if (webEnabled) out.add(WEB_SEARCH_DECLARATION)
        if (memoryEnabled) out.add(REMEMBER_DECLARATION)
        return out
    }

    /** 需要用户点头才能执行的工具。不在此集合内的一律视为只读，直接跑。 */
    val WRITE_TOOLS: Set<String> =
        setOf("kb_create_doc", "kb_update_doc", "kb_delete_doc", REMEMBER)

    fun isWrite(tool: String): Boolean = tool in WRITE_TOOLS

    // ==================== 只读执行 ====================

    /**
     * 执行只读工具，返回给模型看的文本结果。
     *
     * 返回纯文本而不是 JSON：模型读文本的可靠性更高，且这些结果最终也要展示给用户，
     * 同一份字符串两处都能用。
     */
    suspend fun runReadOnly(
        repo: NoteRepository,
        tool: String,
        argsJson: String,
        attachments: AttachmentStore? = null,
        /** 联网搜索。为 null 时该工具返回「不可用」——它需要 Context，只能由调用方注入。 */
        web: WebSearch? = null,
    ): String {
        val args = try {
            JSONObject(argsJson.ifBlank { "{}" })
        } catch (t: Throwable) {
            JSONObject()
        }
        return try {
            when (tool) {
                "kb_search" -> search(repo, args.optString("query"), args.optInt("limit", 10))
                "kb_list_docs" -> listDocs(repo)
                "kb_get_doc" -> getDoc(repo, args.optLong("doc_id", 0L))
                "kb_list_attachments" -> listAttachments(attachments)
                // 联网搜索：唯一会出网的一个。它自己吞掉所有异常并返回人话，
                // 所以这里的 catch 基本只为其余分支兜底。
                WEB_SEARCH -> web!!.search(args.optString("query"), args.optInt("limit", 5))
                // 记忆是写工具，必须走待审。走到这里说明分派逻辑出了问题，
                // 明确报出来而不是静默执行 —— 静默写入等于绕过了用户的同意。
                REMEMBER -> "kb_remember 是写操作，必须经用户确认，不能直接执行。"
                else -> "未知工具：" + tool
            }
        } catch (t: Throwable) {
            "执行失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    private fun search(repo: NoteRepository, query: String, limit: Int): String {
        if (query.isBlank()) return "检索词为空。"
        val hits = repo.search(query, limit.coerceIn(1, 50))
        if (hits.isEmpty()) return "没有找到与「" + query + "」相关的卡片。"
        val sb = StringBuilder("找到 " + hits.size + " 条：\n")
        hits.forEach { h ->
            sb.append("- [doc ").append(h.docId).append(" / card ").append(h.cardId).append("] ")
                .append(h.cardTitle).append("（文档：").append(h.docTitle).append("）\n")
            if (h.snippet.isNotBlank()) sb.append("  ").append(h.snippet).append("\n")
        }
        return sb.toString()
    }

    private fun listDocs(repo: NoteRepository): String {
        val docs = repo.listDocs()
        if (docs.isEmpty()) return "知识库还是空的。"
        val sb = StringBuilder("共 " + docs.size + " 篇文档：\n")
        docs.forEach { d ->
            sb.append("- [doc ").append(d.id).append("] ").append(d.title)
                .append("（").append(d.cardCount).append(" 张卡片）\n")
        }
        return sb.toString()
    }

    /**
     * 列附件。
     *
     * 只给元信息（文件名、大小、类型），不把文件内容喂给模型 —— 图片与 PDF
     * 无法直接作为文本传入，而本项目的模型路由也没有开图片输入。
     * agent 要引用原文，得由用户在对话里说明，或将来做文本抽取。
     */
    private fun listAttachments(store: AttachmentStore?): String {
        if (store == null) return "附件功能不可用。"
        val list = store.list()
        if (list.isEmpty()) return "知识库里还没有附件。"
        val sb = StringBuilder("共 " + list.size + " 份附件：\n")
        list.forEach { a ->
            val name = a.relPath.substringAfterLast('/').substringAfterLast('\\')
            sb.append("- ").append(name)
                .append("（").append(humanSize(a.bytes)).append("，")
                .append(a.mime ?: "未知类型").append("）\n")
        }
        return sb.toString()
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> bytes.toString() + " B"
        bytes < 1024 * 1024 -> (bytes / 1024).toString() + " KB"
        else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
    }

    private fun getDoc(repo: NoteRepository, docId: Long): String {
        if (docId <= 0L) return "doc_id 无效。"
        val draft = repo.loadDraft(docId) ?: return "找不到文档 " + docId + "。"
        val sb = StringBuilder("# ").append(draft.title).append("\n")
        draft.cards.forEachIndexed { i, c ->
            sb.append("\n## 卡片 ").append(i + 1).append("：").append(c.title).append("\n")
                .append(c.body).append("\n")
        }
        return sb.toString()
    }

    // ==================== 写操作摘要（供用户审批时看） ====================

    /**
     * 把一次写调用渲染成人话，用于待审列表与聊天里的确认卡片。
     *
     * 必须**完整**展示将写入的内容：只说「修改文档 3」而不显示改成什么，
     * 用户没法判断该不该同意，审批就成了走过场。
     */
    fun summarize(repo: NoteRepository, tool: String, argsJson: String): String {
        val args = try {
            JSONObject(argsJson.ifBlank { "{}" })
        } catch (t: Throwable) {
            return tool + "（参数无法解析）"
        }
        return try {
            when (tool) {
                "kb_create_doc" -> {
                    val title = args.optString("title")
                    val cards = args.optJSONArray("cards")
                    val n = cards?.length() ?: 0
                    "新建文档「" + title + "」，含 " + n + " 张卡片"
                }
                "kb_update_doc" -> {
                    val id = args.optLong("doc_id", 0L)
                    val old = repo.loadDraft(id)?.title ?: "（已不存在）"
                    val newTitle = args.optString("title").ifBlank { old }
                    val cards = args.optJSONArray("cards")
                    val n = cards?.length() ?: 0
                    "修改文档「" + old + "」→ 标题「" + newTitle + "」，卡片改为 " + n + " 张"
                }
                "kb_delete_doc" -> {
                    val id = args.optLong("doc_id", 0L)
                    val old = repo.loadDraft(id)?.title ?: "（已不存在）"
                    "删除文档「" + old + "」及其全部卡片（不可恢复）"
                }
                // 记忆的摘要就是它要记的原话 —— 审批时用户要看清的正是这一句。
                REMEMBER -> "记住：" + args.optString("text").trim()
                else -> tool
            }
        } catch (t: Throwable) {
            tool
        }
    }

    // ==================== 构造辅助 ====================

    private fun fn(name: String, description: String, parameters: JSONObject): String =
        JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters)
            })
        }.toString()

    private fun prop(type: String, description: String): JSONObject =
        JSONObject().put("type", type).put("description", description)
}

/**
 * 执行待审的写操作。
 *
 * 与 [KbTools.runReadOnly] 分开的原因：读是立即执行的，写必须等用户点同意 ——
 * 两条路径的**触发时机**不同，混在一个函数里会让「谁有权执行写」变得模糊。
 */
object KbToolExecutor {

    /**
     * 执行一次写操作。
     *
     * @return 给模型看的文本结果。
     */
    fun runWrite(
        repo: NoteRepository,
        tool: String,
        argsJson: String,
        /** 记忆存储。为 null 时 kb_remember 报「不可用」。 */
        memory: com.snote.feature.memory.MemoryStore? = null,
    ): String {
        val args = try {
            JSONObject(argsJson.ifBlank { "{}" })
        } catch (t: Throwable) {
            return "参数无法解析，操作未执行。"
        }
        return try {
            when (tool) {
                "kb_create_doc" -> createDoc(repo, args)
                "kb_update_doc" -> updateDoc(repo, args)
                "kb_delete_doc" -> deleteDoc(repo, args)
                KbTools.REMEMBER -> remember(memory, args)
                else -> "未知的写工具：" + tool
            }
        } catch (t: Throwable) {
            "执行失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }

    /**
     * 写入一条长期记忆。
     *
     * 两道闸门都在这里，而不是靠模型自觉：
     *   · 判重 —— 模型措辞常变，但完全相同的句子重复记没有意义；
     *   · 限量 —— 记忆每轮都要注入，长到吃掉上下文会让回答变差而用户毫无感知。
     */
    private fun remember(
        memory: com.snote.feature.memory.MemoryStore?,
        args: JSONObject,
    ): String {
        if (memory == null) return "记忆功能不可用。"
        val text = args.optString("text").trim()
        if (text.isEmpty()) return "要记的内容为空，未写入。"

        if (memory.contains(text)) return "这条已经记过了，未重复写入。"

        val after = memory.totalChars() + text.length
        if (after > com.snote.feature.memory.MemoryStore.MAX_CHARS) {
            // 明确报出上限与当前占用 —— 否则用户只会看到「没记住」而无从下手。
            return "记忆已达上限（" + com.snote.feature.memory.MemoryStore.MAX_CHARS +
                " 字，当前 " + memory.totalChars() +
                " 字），未写入。请到「设置 → 记忆」里清理一些再试。"
        }

        memory.add(text, com.snote.feature.memory.MemoryStore.SOURCE_AGENT)
        return "已记住：" + text
    }

    private fun createDoc(repo: NoteRepository, args: JSONObject): String {
        val title = args.optString("title").ifBlank { "未命名" }
        val docId = repo.createDoc(title)
        val cards = readCards(args.optJSONArray("cards"))
        if (cards.isNotEmpty()) {
            repo.saveDraft(DocDraft(docId, title, cards))
        }
        return "已创建文档「" + title + "」（doc " + docId + "），含 " + cards.size + " 张卡片。"
    }

    private fun updateDoc(repo: NoteRepository, args: JSONObject): String {
        val docId = args.optLong("doc_id", 0L)
        val old = repo.loadDraft(docId) ?: return "找不到文档 " + docId + "，未做任何修改。"
        val title = args.optString("title").ifBlank { old.title }
        // cards 缺省时保留原卡片 —— 只改标题的调用很常见，不该把卡片清空。
        val cards = if (args.has("cards")) readCards(args.optJSONArray("cards")) else old.cards
        repo.saveDraft(DocDraft(docId, title, cards))
        return "已更新文档「" + title + "」（doc " + docId + "），现有 " + cards.size + " 张卡片。"
    }

    private fun deleteDoc(repo: NoteRepository, args: JSONObject): String {
        val docId = args.optLong("doc_id", 0L)
        val old = repo.loadDraft(docId) ?: return "找不到文档 " + docId + "，未做任何修改。"
        repo.deleteDoc(docId)
        return "已删除文档「" + old.title + "」（doc " + docId + "）。"
    }

    private fun readCards(arr: JSONArray?): List<CardDraft> {
        if (arr == null) return emptyList()
        val out = ArrayList<CardDraft>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                CardDraft(
                    id = 0L,
                    title = o.optString("title"),
                    body = o.optString("body"),
                )
            )
        }
        return out
    }
}
