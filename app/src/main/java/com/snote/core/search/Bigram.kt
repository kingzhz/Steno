package com.snote.core.search

/**
 * 零依赖中文分词：CJK 走 bigram，拉丁/数字整词。
 *
 * 为什么不用 FTS 自带分词器：unicode61 会把一整串连续汉字当成单个 token，
 * 搜「天气」命中不了「今天天气很好」。所以切分必须自己做，索引里存切好的 token 串，
 * FTS 只负责按空格拆。
 *
 * 词典识别出的专有名词（card 标题 / alias）由上层先按最长匹配替换成整体 token，
 * 见 [withDictionary] —— 这是「一份词典三处复用」的第一处。
 */
object Bigram {

    /** 判断是否为需要 bigram 切分的字符（CJK 统一表意文字）。 */
    fun isCjk(c: Char): Boolean {
        val code = c.code
        return (code in 0x4E00..0x9FFF) ||
            (code in 0x3400..0x4DBF) ||
            (code in 0xF900..0xFAFF)
    }

    private fun isWordChar(c: Char): Boolean =
        c.isLetterOrDigit() && !isCjk(c)

    /**
     * 切成以空格分隔的 token 串。
     * 例：「今天天气ABC123好」-> 「今天 天天 天气 ABC123 好」
     */
    fun tokenize(text: String, dictionary: List<String> = emptyList()): String {
        if (text.isEmpty()) return ""
        val out = ArrayList<String>(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isCjk(c)) {
                // 先试词典最长匹配，命中则整词作为一个 token，不再拆 bigram。
                val hit = dictionary.firstOrNull { w -> w.isNotEmpty() && text.startsWith(w, i) }
                if (hit != null) {
                    out.add(hit)
                    i += hit.length
                    continue
                }
                if (i + 1 < text.length && isCjk(text[i + 1])) {
                    out.add(text.substring(i, i + 2))
                } else {
                    out.add(c.toString())
                }
                i += 1
            } else if (isWordChar(c)) {
                var j = i
                while (j < text.length && isWordChar(text[j])) j += 1
                out.add(text.substring(i, j).lowercase())
                i = j
            } else {
                i += 1
            }
        }
        return out.joinToString(" ")
    }

    /** 把用户查询转成 FTS MATCH 表达式（token 之间是 AND）。 */
    fun matchExpression(query: String, dictionary: List<String> = emptyList()): String {
        val tokens = tokenize(query, dictionary).split(' ').filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" AND ") { "\"" + it + "\"" }
    }
}
