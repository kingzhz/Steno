package com.snote.core.feature

/**
 * 全功能注册表。新增功能必须在此登记，否则开关面板看不到、也无法正确卸载。
 */
object FeatureRegistry {

    val WIKI = FeatureDef(
        id = "wiki",
        title = "双链与跳转",
        summary = "[[链接]]、侧边栏可跳转名词、反向链接。link/alias 属源数据，关闭时不删除。",
        cost = FeatureCost.FREE,
        rebuildNote = null
    )

    val SEARCH = FeatureDef(
        id = "search",
        title = "全文检索",
        summary = "bigram 分词 + FTS 索引，本地重建。",
        derivedKinds = setOf("fts"),
        cost = FeatureCost.LOCAL_HEAVY,
        rebuildNote = "约 1 万张卡片预计 10-30 秒，本地 CPU，不花钱。"
    )

    val THUMBS = FeatureDef(
        id = "thumbs",
        title = "附件缩略图",
        summary = "列表滚动用的缩略图缓存。关闭即删除，原图保留。",
        derivedKinds = setOf("thumb"),
        cost = FeatureCost.LOCAL_HEAVY,
        rebuildNote = "重新打开后按需重建，打开哪张生成哪张。"
    )

    val DEDUP = FeatureDef(
        id = "dedup",
        title = "附件去重",
        summary = "按内容哈希去重，同一文件只存一份。写入期行为，关闭时不删任何数据。",
        cost = FeatureCost.FREE
    )

    val OCR = FeatureDef(
        id = "ocr",
        title = "OCR 文字识别",
        summary = "扫描件/图片转文字。重建需调用外部 API。",
        requires = setOf(DEDUP.id),
        derivedKinds = setOf("ocr"),
        cost = FeatureCost.PAID_API,
        rebuildNote = "按文档逐页调用识别接口，耗时与费用随附件量线性增长。",
        defaultEnabled = false
    )

    val VECTOR = FeatureDef(
        id = "vector",
        title = "语义检索",
        summary = "向量召回，用于模糊描述查询。重建需调用嵌入 API。",
        derivedKinds = setOf("vector"),
        cost = FeatureCost.PAID_API,
        rebuildNote = "每张卡片调用一次嵌入接口，只补源变更或版本过旧的条目。",
        defaultEnabled = false
    )

    val AGENT = FeatureDef(
        id = "agent",
        title = "知识库 Agent",
        summary = "对话 + 工具循环，可对知识库增删改查。写入默认走待审。" +
            "关闭后对话入口不可用，历史对话与消息保留不删。",
        requires = setOf(SEARCH.id),
        // 对话历史属源数据（用户自己说的话），刻意不登记 derivedKinds：
        // 关功能只让入口不可用，绝不卸载这里的行。
        cost = FeatureCost.FREE
    )

    /**
     * 联网搜索。
     *
     * ## 为什么它是行为开关，derivedKinds 留空
     *
     * 它不产生任何派生物（没有索引、没有缩略图、没有缓存表），所以关掉时
     * **不删任何东西** —— 这也符合「关功能 ≠ 删源数据」的总原则。
     * 开关的作用是两件事：决定 AgentRunner 要不要把 web_search 的声明发给模型，
     * 以及决定工具真被调用时执行还是拒绝。
     *
     * ## 为什么默认开着
     *
     * 它是纯出网、无花费、无副作用的能力，且失败会优雅降级。
     * 但用户可能出于隐私考虑不想让对话内容离开设备，所以必须给一个能关的开关。
     *
     * ## 为什么依赖 AGENT
     *
     * 它只在对话里作为工具存在，没有 agent 就没有它。
     */
    val WEB_SEARCH = FeatureDef(
        id = "web_search",
        title = "联网搜索",
        summary = "让对话能搜互联网（免密钥）。关掉后模型看不到这个工具，" +
            "对话内容不会离开设备。不产生任何派生物，关闭时不删数据。",
        requires = setOf(AGENT.id),
        cost = FeatureCost.FREE
    )

    /**
     * 长期记忆。
     *
     * ## 它管什么
     *
     * 关掉时两件事同时停：①记忆不再注入 system 提示词（对话不再「记得」用户）；
     * ②kb_remember 工具声明不再发给模型（它看不到就不会申请）。
     *
     * ## 关掉不删数据
     *
     * 记忆是用户与模型共同积累的资产，属**源数据** —— derivedKinds 留空，
     * 关掉只让入口不可用。下次打开，之前记的还在。
     */
    val MEMORY = FeatureDef(
        id = "memory",
        title = "长期记忆",
        summary = "让对话记住关于你的长期事实（身份、偏好、习惯）。" +
            "模型申请记录时会先进待审，你同意后才生效。关闭后对话不再读取记忆，" +
            "已记的内容保留不删。",
        requires = setOf(AGENT.id),
        cost = FeatureCost.FREE
    )

    val ALL: List<FeatureDef> =
        listOf(WIKI, SEARCH, THUMBS, DEDUP, OCR, VECTOR, AGENT, WEB_SEARCH, MEMORY)

    fun byId(id: String): FeatureDef? = ALL.firstOrNull { it.id == id }

    /** 关掉 ids 后，哪些功能会因为依赖缺失而被迫停用（含传递闭包）。 */
    fun dependentsOf(ids: Set<String>): Set<String> {
        val out = LinkedHashSet<String>()
        var grew = true
        while (grew) {
            grew = false
            for (f in ALL) {
                if (f.id in out || f.id in ids) continue
                if (f.requires.any { it in ids || it in out }) {
                    out.add(f.id)
                    grew = true
                }
            }
        }
        return out
    }
}
