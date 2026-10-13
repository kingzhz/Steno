package com.snote.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
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
import com.snote.core.db.KbOpenHelper
import com.snote.ui.theme.Spacing

/**
 * 标签浏览。
 *
 * ## 为什么标签需要独立一页
 *
 * 打标签本身在编辑器里就够了，但**标签的价值在于「按它找东西」** ——
 * 没有浏览入口，标签就只是卡片上的一行装饰。
 *
 * ## 两级视图
 *
 * 先列全部标签（带使用次数），点一个再列该标签下的卡片。做成两级而不是
 * 一页铺开：标签多了以后，把所有卡片全列出来反而找不到重点。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagScreen(
    helper: KbOpenHelper,
    /**
     * @param docId 文档 id
     * @param cardId 点中的那张卡 —— 与检索页一致，打开后要定位到它。
     *        标签页列出的本来就是「某张卡」，不定位等于把用户丢进文档让他自己找。
     */
    onOpenDoc: (Long, Long) -> Unit,
    onBack: () -> Unit,
) {
    val store = remember { TagStore(helper) }
    var version by remember { mutableStateOf(0) }
    /** 当前选中的标签；null = 停在标签列表。 */
    var selected by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<TagCount?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val tags = remember(version) { store.listTags() }

    BackHandler {
        if (selected != null) selected = null else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(selected ?: "标签") },
                navigationIcon = {
                    TextButton(onClick = {
                        if (selected != null) selected = null else onBack()
                    }) { Text("返回") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            notice?.let {
                Text(
                    it,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val cur = selected
            if (cur == null) {
                if (tags.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "还没有标签。在编辑器里点卡片的「标签」按钮添加。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(tags, key = { it.id }) { t ->
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
                                    Column(
                                        modifier = Modifier.weight(1f).clickable {
                                            selected = t.name
                                            notice = null
                                        }
                                    ) {
                                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            t.count.toString() + " 张卡片",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { pendingDelete = t }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
            } else {
                val cardIds = remember(cur, version) { store.cardsWithTag(cur) }
                if (cardIds.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("这个标签下没有卡片了。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(cardIds.size) { i ->
                            val info = remember(cardIds[i], version) { cardInfo(helper, cardIds[i]) }
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { info?.second?.let { onOpenDoc(it, cardIds[i]) } }
                                        .padding(Spacing.lg)
                                ) {
                                    Text(
                                        info?.first ?: "（卡片已删除）",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Text(
                                        info?.third ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除标签") },
            text = {
                Text(
                    "「" + target.name + "」会从 " + target.count + " 张卡片上摘掉。" +
                        "卡片本身不会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = store.deleteTag(target.name)
                    notice = "已从 " + n + " 张卡片上摘掉「" + target.name + "」。"
                    version++
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/** 查一张卡片的 (标题, 文档 id, 文档标题)。卡片不存在返回 null。 */
private fun cardInfo(helper: KbOpenHelper, cardId: Long): Triple<String, Long, String>? {
    helper.readableDatabase.rawQuery(
        "SELECT c.title, c.doc_id, d.title FROM card c JOIN doc d ON d.id = c.doc_id WHERE c.id = ?",
        arrayOf(cardId.toString())
    ).use { c ->
        if (!c.moveToFirst()) return null
        return Triple(c.getString(0) ?: "", c.getLong(1), c.getString(2) ?: "")
    }
}
