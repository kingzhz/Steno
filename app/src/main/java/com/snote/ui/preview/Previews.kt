package com.snote.ui.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.snote.feature.notes.DocSummary
import com.snote.feature.notes.NotesListContent
import com.snote.feature.settings.AppearanceSection
import com.snote.ui.MarkdownText
import com.snote.ui.theme.Accent
import com.snote.ui.theme.SnoteTheme
import com.snote.ui.theme.Spacing
import com.snote.ui.theme.ThemePreset

/**
 * 全部界面预览集中在这里。
 *
 * ## 为什么集中而不是散在业务文件里
 *
 * 预览用的假数据（下面那些假文档、假对话）如果放在业务文件里，会被正式包编译进去
 * 并可能被误当真实常量引用。集中在一个文件里，将来要排除也只改一处。
 *
 * ## 预览为什么必须用无状态组件
 *
 * IDE 的 Preview 在隔离环境里渲染：没有 Application、没有数据库文件。
 * 任何在渲染期间真的去读库的组件（LaunchedEffect + Repository）都会当场崩。
 * 所以下面一律用 *Content / *Section 这类只吃数据与回调的组件。
 *
 * ## 三套底色都各来一份
 *
 * 配色的观感差异只有并排看才判断得出来，所以 NotesListContent 对三种底色
 * 各出一个预览 —— 改配色时一眼就能比较，不必来回切。
 */
private val FAKE_DOCS = listOf(
    DocSummary(id = 1, title = "西门子 PLC 接线笔记", updatedAt = 1_760_000_000_000, cardCount = 4),
    DocSummary(id = 2, title = "Compose 状态提升", updatedAt = 1_759_900_000_000, cardCount = 2),
    DocSummary(id = 3, title = "SQLite FTS5 踩坑", updatedAt = 1_759_800_000_000, cardCount = 7),
)

@Preview(name = "笔记列表 · 白色", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun PreviewNotesLight() {
    SnoteTheme(preset = ThemePreset.LIGHT, accent = Accent.SKY) {
        NotesListContent(docs = FAKE_DOCS)
    }
}

@Preview(name = "笔记列表 · 淡蓝色", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun PreviewNotesSky() {
    SnoteTheme(preset = ThemePreset.SKY, accent = Accent.SKY) {
        NotesListContent(docs = FAKE_DOCS)
    }
}

@Preview(name = "笔记列表 · 黑色", showBackground = true, widthDp = 380, heightDp = 640)
@Composable
private fun PreviewNotesDark() {
    SnoteTheme(preset = ThemePreset.DARK, accent = Accent.SKY) {
        NotesListContent(docs = FAKE_DOCS)
    }
}

/** 空列表：验证「不留白屏」的提示文案与位置。 */
@Preview(name = "笔记列表 · 空", showBackground = true, widthDp = 380, heightDp = 480)
@Composable
private fun PreviewNotesEmpty() {
    SnoteTheme(preset = ThemePreset.LIGHT) {
        NotesListContent(docs = emptyList())
    }
}

@Preview(name = "外观设置", showBackground = true, widthDp = 380, heightDp = 320)
@Composable
private fun PreviewAppearance() {
    SnoteTheme(preset = ThemePreset.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                AppearanceSection(preset = ThemePreset.LIGHT, accent = Accent.SKY)
            }
        }
    }
}

@Preview(name = "外观设置 · 淡蓝底", showBackground = true, widthDp = 380, heightDp = 320)
@Composable
private fun PreviewAppearanceSky() {
    SnoteTheme(preset = ThemePreset.SKY, accent = Accent.MINT) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                AppearanceSection(preset = ThemePreset.SKY, accent = Accent.MINT)
            }
        }
    }
}

private const val MD_SAMPLE = """# 标题一级
## 标题二级

普通段落，含 **粗体**、*斜体* 与 `行内代码`。

- 列表项一
- 列表项二

> 引用一行

```
fun main() {
    println("代码块")
}
```
"""

/** markdown 渲染：验证星号不再裸露、代码块有底色。 */
@Preview(name = "Markdown 渲染", showBackground = true, widthDp = 380, heightDp = 520)
@Composable
private fun PreviewMarkdown() {
    SnoteTheme(preset = ThemePreset.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            MarkdownText(
                text = MD_SAMPLE,
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                codeBackground = MaterialTheme.colorScheme.surface,
            )
        }
    }
}

/** 长文本截断：确认超长正文不会把卡片撑爆。 */
@Preview(name = "Markdown 长文本", showBackground = true, widthDp = 380, heightDp = 300)
@Composable
private fun PreviewMarkdownLong() {
    SnoteTheme(preset = ThemePreset.LIGHT) {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
            Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
                Text(
                    "下面是一段很长的普通文本，用来确认换行与卡片高度是否正常：" +
                        "中文段落会在容器宽度处自动折行，不会横向溢出；" +
                        "如果出现横向滚动条，说明有组件没设宽度约束。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 底部栏 + FAB 的三底色对比。
 *
 * 单独出这三个预览是因为它们是**最容易被基线调色板污染的组件**：
 * NavigationBar 的背景取 surfaceContainer、选中项取 secondaryContainer、
 * FloatingActionButton 取 primaryContainer —— 这些角色一旦没在
 * ColorScheme 里定义，Material3 会静默回落到它自己的基线紫，
 * 而且在别的页面上看不出来（只有底部栏和 FAB 会用）。
 */
@Preview(name = "底部栏 + FAB · 白色", showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun PreviewChromeLight() {
    SnoteTheme(preset = ThemePreset.LIGHT, accent = Accent.SKY) {
        FakeChrome()
    }
}

@Preview(name = "底部栏 + FAB · 淡蓝色", showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun PreviewChromeSky() {
    SnoteTheme(preset = ThemePreset.SKY, accent = Accent.SKY) {
        FakeChrome()
    }
}

@Preview(name = "底部栏 + FAB · 黑色", showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun PreviewChromeDark() {
    SnoteTheme(preset = ThemePreset.DARK, accent = Accent.SKY) {
        FakeChrome()
    }
}

/**
 * 复刻 AppRoot 的骨架：底部两个 Tab + 一个 FAB。
 *
 * 刻意不去调真的 AppRoot（它要 NoteRepository 与 CredentialStore），
 * 这里只重建那几行会取容器色的组件 —— 目的就是让容器色的问题能在
 * 预览里暴露出来。
 */
@Composable
private fun FakeChrome() {
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = {}) { Text("新建") }
        },
        bottomBar = {
            NavigationBar {
                listOf(
                    "笔记" to Icons.Filled.Edit,
                    "对话" to Icons.Filled.Email,
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = i == 0,
                        onClick = {},
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg)) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(Spacing.md)) {
                    Text("卡片标题", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "副标题：2 张卡片 · 10-11 20:00",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Button(onClick = {}) { Text("主要按钮") }
                OutlinedButton(onClick = {}) { Text("次要按钮") }
            }
        }
    }
}
