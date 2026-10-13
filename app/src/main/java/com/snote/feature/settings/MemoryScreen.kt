package com.snote.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.snote.feature.memory.Memory
import com.snote.feature.memory.MemoryStore
import com.snote.ui.theme.Spacing

/**
 * 记忆管理。
 *
 * ## 为什么用户必须能直接编辑
 *
 * 记忆是「今后每一轮对话都生效」的东西，它一旦记错，症状是**回答一直不对劲**
 * 而不是某处报错 —— 用户很难定位到是记忆的问题，更没法自己修。所以光能看得见
 * 不够，必须能改能删。
 *
 * ## 为什么要标出「谁记的」
 *
 * 模型记的和用户写的混在一起时，用户排查「它为什么这么答」会毫无头绪。
 * 一行小字标出来，至少能快速缩小范围。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MemoryStore(context.applicationContext) }
    var items by remember { mutableStateOf(store.list()) }
    var editing by remember { mutableStateOf<Memory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Memory?>(null) }

    fun reload() {
        items = store.list()
    }

    val used = items.sumOf { it.text.length }
    val limit = MemoryStore.MAX_CHARS
    val full = used >= limit

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("记忆") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Text(
                    "这些内容会加进每次对话的开头，让 AI 记住关于你的长期事实。",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(Spacing.sm))
                // 用量摆在显眼处：撞上限时用户得先知道「快满了」，
                // 否则只会看到「没记住」而不知为何。
                Text(
                    "已用 " + used + " / " + limit + " 字",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (full) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = { adding = true },
                    enabled = !full,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (full) "记忆已满，请先清理" else "新增一条") }
                Spacer(Modifier.height(Spacing.md))
            }

            if (items.isEmpty()) {
                Text(
                    "还没有记忆。你可以自己加一条，AI 也会在需要时申请记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = Spacing.lg, end = Spacing.lg, bottom = Spacing.lg
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(items, key = { it.id }) { m ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(Spacing.md)) {
                                Text(m.text, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(Spacing.xs))
                                Text(
                                    if (m.source == MemoryStore.SOURCE_AGENT) "AI 记录" else "你写的",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(Spacing.xs))
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    TextButton(onClick = { editing = m }) { Text("编辑") }
                                    TextButton(onClick = { pendingDelete = m }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---------- 新增 / 编辑 ----------
    val editTarget = editing
    if (adding || editTarget != null) {
        var text by remember(adding, editTarget) {
            mutableStateOf(editTarget?.text ?: "")
        }
        AlertDialog(
            onDismissRequest = { adding = false; editing = null },
            title = { Text(if (editTarget == null) "新增记忆" else "编辑记忆") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("例如：我是 Android 开发者，用 Kotlin") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val t = text.trim()
                        if (t.isNotEmpty()) {
                            if (editTarget == null) store.add(t, MemoryStore.SOURCE_USER)
                            else store.update(editTarget.id, t)
                            reload()
                        }
                        adding = false
                        editing = null
                    },
                    enabled = text.isNotBlank()
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { adding = false; editing = null }) { Text("取消") }
            }
        )
    }

    // ---------- 删除 ----------
    val del = pendingDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这条记忆") },
            text = { Text("「" + del.text + "」\n\n删除后不再影响后续对话。") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(del.id)
                    reload()
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
