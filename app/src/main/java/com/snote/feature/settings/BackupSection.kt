package com.snote.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.snote.feature.backup.BackupStore
import com.snote.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 数据备份区。
 *
 * ## 为什么这件事必须由用户手动触发
 *
 * 应用刻意关掉了系统云备份（allowBackup=false，见 AndroidManifest 的注释）：
 * 附件目录可达 GB 级，丢进云备份是灾难。代价是**没有任何自动兜底**，
 * 所以必须给用户一个能自己把数据拿走的出口 —— 这一区就是那个出口。
 *
 * ## 恢复为什么要二次确认
 *
 * 恢复会覆盖当前全部笔记。用户把备份文件选错了（比如选了另一个 App 的 zip）
 * 最多是报错，但**选对了却不想覆盖**才是真正的损失。所以确认框里必须写清
 * 「现在的数据会被备份里的内容替换」，而不是含糊的「确定要恢复吗」。
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    val store = remember { BackupStore(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 待确认导入的来源 Uri；null = 对话框关闭。 */
    var pendingImport by remember { mutableStateOf<android.net.Uri?>(null) }

    /** 导出：让用户选保存位置（SAF），再往那个 Uri 里写。 */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        status = "正在导出…"
        scope.launch {
            // 备份可能几百 MB，必须在 IO 线程；主线程做会直接 ANR。
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { store.exportTo(it) }
                        ?: com.snote.feature.backup.BackupResult(false, "无法写入所选位置。")
                }.getOrElse {
                    com.snote.feature.backup.BackupResult(false, "导出失败：" + (it.message ?: "未知错误"))
                }
            }
            status = r.message
            busy = false
        }
    }

    /** 导入：先选文件，选完弹确认框，确认了才真正恢复。 */
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImport = uri
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("数据备份", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "备份包含全部笔记、对话、记忆与附件原文件，是一个 zip。" +
                "系统云备份对本应用已关闭（附件太大），所以换机或误清数据前请自己导出一份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(Spacing.md))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { exportLauncher.launch(BackupStore.suggestedName()) },
                enabled = !busy
            ) { Text("导出全库") }
            Spacer(Modifier.width(Spacing.sm))
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !busy
            ) { Text("从备份恢复") }
        }

        status?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }

    val target = pendingImport
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("从备份恢复") },
            text = {
                Text(
                    "现在的笔记、对话、记忆与附件会被备份里的内容替换，且无法撤销。" +
                        "\n\n如果当前数据还需要，请先点「导出全库」留一份。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    busy = true
                    status = "正在恢复…"
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(target)?.use {
                                    store.importFrom(it)
                                } ?: com.snote.feature.backup.BackupResult(false, "读不到所选文件。")
                            }.getOrElse {
                                com.snote.feature.backup.BackupResult(
                                    false,
                                    "恢复失败，数据未改动：" + (it.message ?: "未知错误")
                                )
                            }
                        }
                        status = r.message
                        busy = false
                    }
                }) { Text("覆盖恢复") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImport = null }) { Text("取消") }
            }
        )
    }
}
