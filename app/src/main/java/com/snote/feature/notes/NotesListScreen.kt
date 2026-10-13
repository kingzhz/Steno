package com.snote.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.snote.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 笔记列表。
 *
 * 空列表时不留白屏 —— 直接告诉用户下一步点哪里。
 */
@Composable
fun NotesListScreen(
    repo: NoteRepository,
    onOpen: (Long) -> Unit,
    onOpenFeatures: () -> Unit,
    onOpenSearch: () -> Unit,
    /** 全文检索关掉时隐藏检索入口。 */
    showSearchEntry: Boolean = true,
    onOpenTags: () -> Unit = {},
) {
    var docs by remember { mutableStateOf<List<DocSummary>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<DocSummary?>(null) }

    LaunchedEffect(reload) { docs = repo.listDocs() }

    NotesListContent(
        docs = docs,
        showSearchEntry = showSearchEntry,
        onOpen = onOpen,
        onOpenFeatures = onOpenFeatures,
        onOpenSearch = onOpenSearch,
        onOpenTags = onOpenTags,
        onCreate = { onOpen(repo.createDoc("未命名")) },
        onRequestDelete = { pendingDelete = it },
    )

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「" + target.title + "」") },
            text = { Text("这篇文档及其卡片、链接会一并删除，无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteDoc(target.id)
                    pendingDelete = null
                    reload++
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/**
 * 笔记列表的**无状态**内容。
 *
 * 与 [NotesListScreen] 分开是为了能预览：那一半要构造 NoteRepository（真数据库），
 * 而 Preview 在 IDE 里跑隔离渲染，没有 Application 也没有库文件，
 * 一执行 LaunchedEffect 就会崩。这一半只吃数据、只吐回调，因此随便喂假数据。
 *
 * 顺带的好处：它是纯函数式的，将来写界面测试也不用搭数据库。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListContent(
    docs: List<DocSummary>,
    showSearchEntry: Boolean = true,
    onOpen: (Long) -> Unit = {},
    onOpenFeatures: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenTags: () -> Unit = {},
    onCreate: () -> Unit = {},
    onRequestDelete: (DocSummary) -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("笔记") },
                // 没有「对话」按钮：对话已经是底部 Tab 的主窗口，
                // 顶部再放一个是同一个目的地的重复入口。
                actions = {
                    if (showSearchEntry) {
                        TextButton(onClick = onOpenSearch) { Text("检索") }
                    }
                    TextButton(onClick = onOpenTags) { Text("标签") }
                    TextButton(onClick = onOpenFeatures) { Text("功能") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) { Text("新建") }
        }
    ) { padding ->
        if (docs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("还没有笔记，点右下角新建", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                items(docs, key = { it.id }) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(d.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    d.cardCount.toString() + " 张卡片 · " + formatTime(d.updatedAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onOpen(d.id) }) { Text("打开") }
                            TextButton(onClick = { onRequestDelete(d) }) { Text("删除") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
