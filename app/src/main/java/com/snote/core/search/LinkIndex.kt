package com.snote.core.search

/**
 * 卡片名的多模式匹配（Aho-Corasick）。
 *
 * ## 为什么需要它
 *
 * 链接由软件自动生成，用户不写任何标记。保存卡片时把正文扫一遍，
 * 命中别的卡片名就建一条 link。词典规模是「卡片数 × (1 标题 + n 别名）」，
 * 单篇正文长度几十 KB —— 逐词 startsWith 是 O(词数 × 文本长)，卡片上千后会卡顿；
 * AC 自动机是 O(文本长 + 命中数)，与词典规模无关。
 *
 * ## 只在需要时构建，用完即弃
 *
 * 不常驻内存：构建成本是线性的，卡片表几千行也就几毫秒。
 * 常驻反而要处理「卡片改了词典没更新」的一致性问题。
 */
class LinkIndex(entries: List<Entry>) {

    /** 词典里的一条：命中后回填的目标卡片。 */
    data class Entry(val term: String, val cardId: Long, val docId: Long)

    /** 一条命中：在文本里的区间 + 目标卡片。 */
    data class Hit(val start: Int, val end: Int, val cardId: Long)

    private class Node {
        val next = HashMap<Char, Int>()
        var fail = 0
        val out: MutableList<Int> = ArrayList()
    }

    private val nodes = ArrayList<Node>()
    private val terms = ArrayList<Entry>()

    /** 词典条数（已过滤掉过短词）。 */
    val size: Int get() = terms.size

    val isEmpty: Boolean get() = terms.isEmpty()

    /** 词典里的全部词（供侧边栏算「正文提到但还没建卡」的候选）。 */
    fun allTerms(): Set<String> = terms.mapTo(HashSet()) { it.term }

    init {
        nodes.add(Node())
        for (e in entries) {
            val t = e.term.trim()
            // 只链长度 >= 2 的词：单字命中噪音太大（「的」「了」「在」都会链出去）。
            if (t.length < 2) continue
            terms.add(e)
            val termIdx = terms.size - 1
            var cur = 0
            for (c in t) {
                val nx = nodes[cur].next[c]
                if (nx != null) {
                    cur = nx
                } else {
                    nodes.add(Node())
                    val id = nodes.size - 1
                    nodes[cur].next[c] = id
                    cur = id
                }
            }
            nodes[cur].out.add(termIdx)
        }
        buildFail()
    }

    private fun buildFail() {
        val queue = ArrayDeque<Int>()
        for ((_, v) in nodes[0].next) {
            nodes[v].fail = 0
            queue.add(v)
        }
        while (queue.isNotEmpty()) {
            val u = queue.removeFirst()
            for ((c, v) in nodes[u].next) {
                var f = nodes[u].fail
                while (f != 0 && nodes[f].next[c] == null) f = nodes[f].fail
                val nf = nodes[f].next[c]
                nodes[v].fail = if (nf != null && nf != v) nf else 0
                // 合并失配链上的输出：否则会漏掉「后缀也是词典词」的情况。
                nodes[v].out.addAll(nodes[nodes[v].fail].out)
                queue.add(v)
            }
        }
    }

    /**
     * 扫描文本，返回**不重叠**的命中（最长优先）。
     *
     * 重叠时保留起点更靠前的；同起点保留最长的那个（长的通常是更具体的概念）。
     */
    fun scan(text: String, selfCardId: Long = -1L): List<Hit> {
        if (terms.isEmpty() || text.isEmpty()) return emptyList()
        val raw = ArrayList<Hit>()
        var cur = 0
        for (i in text.indices) {
            val c = text[i]
            while (cur != 0 && nodes[cur].next[c] == null) cur = nodes[cur].fail
            cur = nodes[cur].next[c] ?: 0
            for (ti in nodes[cur].out) {
                val e = terms[ti]
                val start = i - e.term.length + 1
                if (start < 0) continue
                if (e.cardId == selfCardId) continue
                raw.add(Hit(start, i + 1, e.cardId))
            }
        }
        if (raw.isEmpty()) return raw
        raw.sortWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val out = ArrayList<Hit>(raw.size)
        var lastEnd = -1
        for (h in raw) {
            if (h.start >= lastEnd) {
                out.add(h)
                lastEnd = h.end
            }
        }
        return out
    }
}