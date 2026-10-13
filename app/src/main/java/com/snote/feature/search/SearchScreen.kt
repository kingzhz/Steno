package com.snote.feature.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.snote.feature.notes.NoteRepository
import com.snote.feature.notes.SearchHit
import com.snote.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 全文检索页。
 *
 * 索引是派生物：关掉「全文检索」功能时 fts_card 整表被删，这里就会搜不到东西。
 * 那种情况下不是报错，而是明确告诉用户「功能关了」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    repo: NoteRepository,
    onOpenDoc: (Long) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val indexOn = remember { repo.isFtsPresent() }

    // 输入防抖：每敲一个字都取消上一次等待。
    LaunchedEffect(query, indexOn) {
        val q = query.trim()
        if (q.isEmpty() || !indexOn) {
            hits = emptyList()
            searched = false
            return@LaunchedEffect
        }
        delay(180)
        hits = repo.search(q)
        searched = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("检索") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("输入关键词") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (!indexOn) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "全文检索功能已关闭。到「功能」里打开并重建索引后即可搜索。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            if (hits.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (!searched) "输入关键词开始搜索" else "没有匹配的卡片",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(hits, key = { it.cardId }) { h ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(Spacing.md)
                            ) {
                                Text(
                                    h.cardTitle.ifBlank { "未命名卡片" },
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "来自：" + h.docTitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (h.snippet.isNotEmpty()) {
                                    Text(h.snippet, style = MaterialTheme.typography.bodyMedium)
                                }
                                TextButton(onClick = { onOpenDoc(h.docId) }) { Text("打开笔记") }
                            }
                        }
                    }
                }
            }
        }
    }
}
