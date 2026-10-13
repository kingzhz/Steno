package com.snote.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.snote.ui.theme.Spacing

/**
 * 一张卡片的标签与别名编辑。
 *
 * ## 为什么两者放在同一个对话框
 *
 * 它们都是「这张卡的身份信息」：标签说明它属于哪一类，别名说明它还叫什么。
 * 分成两个入口会让用户为了补一条信息点两次。
 *
 * ## 为什么必须已有 cardId
 *
 * 标签与别名都挂在 card_id 上。编辑器允许存在尚未落库的卡片（用户刚点
 * 「新建分节」，id = 0），此时写进去会挂到不存在的卡片上。因此调用方
 * 只在卡片已保存时才打开本对话框。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CardMetaDialog(
    cardId: Long,
    cardTitle: String,
    tags: List<String>,
    aliases: List<String>,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    onAddAlias: (String) -> Unit,
    onRemoveAlias: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // 每次增删后由调用方重新传入新列表，这里靠 key 重建来刷新输入框状态。
    var tagInput by remember { mutableStateOf("") }
    var aliasInput by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cardTitle.ifBlank { "未命名卡片" }) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("标签", style = MaterialTheme.typography.titleSmall)
                Text(
                    "给卡片归类，一张卡可以有多个。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                if (tags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        tags.forEach { t ->
                            AssistChip(
                                onClick = { onRemoveTag(t) },
                                label = { Text(t + " ✕") }
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = tagInput,
                        onValueChange = { tagInput = it },
                        label = { Text("加标签") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (tagInput.isNotBlank()) {
                            onAddTag(tagInput)
                            tagInput = ""
                        }
                    }) { Text("添加") }
                }

                Spacer(Modifier.height(Spacing.lg))

                Text("别名", style = MaterialTheme.typography.titleSmall)
                Text(
                    "这张卡的其他叫法，用于侧边栏跳转。不参与自动链接，避免普通词误链。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                if (aliases.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        aliases.forEach { a ->
                            AssistChip(
                                onClick = { onRemoveAlias(a) },
                                label = { Text(a + " ✕") }
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.sm))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = aliasInput,
                        onValueChange = { aliasInput = it },
                        label = { Text("加别名") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = {
                        if (aliasInput.isNotBlank()) {
                            onAddAlias(aliasInput)
                            aliasInput = ""
                        }
                    }) { Text("添加") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        dismissButton = null
    )
}
