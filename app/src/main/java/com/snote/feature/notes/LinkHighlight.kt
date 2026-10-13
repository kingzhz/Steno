package com.snote.feature.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.snote.core.search.LinkIndex

/**
 * 正文里的卡片名着色。
 *
 * 只**上色**，不插入、不删除任何字符 —— 偏移映射恒等，
 * 因此光标位置、选区、输入法行为全部与没加这一层时完全一致。
 *
 * 为什么不做成可点击的富文本：TextField 里塞可点击 Span 会和选中、输入法抢手势，
 * 所以跳转统一走侧边栏，正文只负责「让你看见这里有关系」。
 */
class LinkHighlightTransformation(
    private val index: LinkIndex,
    private val selfCardId: Long,
    private val color: Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        if (index.isEmpty) return TransformedText(text, OffsetMapping.Identity)
        val hits = index.scan(text.text, selfCardId = selfCardId)
        if (hits.isEmpty()) return TransformedText(text, OffsetMapping.Identity)
        val builder = AnnotatedString.Builder(text)
        for (h in hits) {
            builder.addStyle(SpanStyle(color = color), h.start, h.end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
