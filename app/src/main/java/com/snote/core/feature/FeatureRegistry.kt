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

    /**
     * OCR 文字识别。
     *
     * ## 为什么是免费的本地引擎，而不是付费 API
     *
     * 原设计写的是「调用外部 API」。改成端上 Tesseract 有两个硬理由：
     *   ① 本应用承诺「数据全在设备上」，把扫描件传给别人等于自毁承诺 ——
     *      而扫描件恰恰是身份证、合同、病历这类最敏感的东西；
     *   ② 识别本身不花钱、离线可用、没有配额，就不该挂一个预算门。
     *
     * 代价是模型体积（chi_sim 2.4MB + eng 4.1MB 打进 apk）与首次解压耗时，
     * 这个取舍与 PdfBox 一致 —— 都是「用几 MB 换数据不出设备」。
     *
     * ## 它的派生物是什么
     *
     * 识别结果写进 derived 表（kind = "ocr"），与原图分开存。
     * 关掉功能时删这些行、不删附件本身 —— 重新打开要重新识别，那正是
     * 「派生物可删可重建」的意思。
     */
    val OCR = FeatureDef(
        id = "ocr",
        title = "OCR 文字识别",
        summary = "扫描件/图片转文字，识别在本机完成，不上传、不花钱。",
        requires = setOf(DEDUP.id),
        derivedKinds = setOf("ocr"),
        cost = FeatureCost.LOCAL_HEAVY,
        rebuildNote = "识别在本机 CPU 完成，不花钱；一张图通常几秒。中途关掉功能会丢弃已有结果。",
        defaultEnabled = false
    )

    // 这里原本有一个「语义检索 VECTOR」开关，已删除。
    //
    // 为什么删：它只登记了 id / derivedKinds / 预算门文案，没有任何实现 ——
    // 打开它除了弹一次预算门什么都不发生，用户会以为「开了就能模糊搜」，
    // 而实际还是 bigram 精确匹配。注册表里挂着一个不存在的功能，比没有它更坏。
    //
    // 为什么不是「补上实现」：端上中文嵌入模型最小的 bge-small-zh 也要 ~96MB
    // 权重 + ONNX Runtime，而个人库几百到几千张卡的量级，bigram + FTS5 已经够用。
    // 本项目为了省 2MB 宁可不用 material-icons-extended，为一个边际收益背 100MB
    // 是自相矛盾的。真要做，该做成「可选下载的模型包」而不是默认内置。

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
        listOf(WIKI, SEARCH, THUMBS, DEDUP, OCR, AGENT, WEB_SEARCH, MEMORY)

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
