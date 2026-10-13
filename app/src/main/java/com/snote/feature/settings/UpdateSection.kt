package com.snote.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import com.snote.feature.update.UpdateChecker
import com.snote.feature.update.UpdateResult
import com.snote.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 检查更新。
 *
 * ## 为什么必须用户手动点
 *
 * 本应用承诺数据不出设备。自动检查更新会带来一条**定期向 GitHub 发出的请求** ——
 * 那等于告诉对方「这台设备装了 Steno、大概什么时候在用、IP 在哪」。
 * 对一个本地优先的笔记应用，这个代价不值得，哪怕它换来一点方便。
 *
 * 所以这里没有后台轮询、没有启动时检查，只有一个按钮。
 *
 * ## 为什么下载完还要用户再点一次
 *
 * 安卓不允许普通应用静默安装，这是系统级防线。我们能做的是把包下好、
 * 把系统安装器叫起来，最后那一下确认必须在系统界面里完成。
 */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    /** 检查到的新版本；null = 对话框关闭。 */
    var pending by remember { mutableStateOf<UpdateResult.Available?>(null) }

    fun check() {
        busy = true
        status = "正在检查…"
        scope.launch {
            val r = withContext(Dispatchers.IO) { UpdateChecker.check() }
            busy = false
            when (r) {
                is UpdateResult.UpToDate -> status = "已是最新版本（" + UpdateChecker.currentVersion() + "）。"
                is UpdateResult.Failed -> status = r.message
                is UpdateResult.Available -> {
                    status = null
                    pending = r
                }
            }
        }
    }

    fun downloadAndInstall(v: UpdateResult.Available) {
        pending = null
        busy = true
        status = "正在下载…"
        scope.launch {
            val apk = withContext(Dispatchers.IO) {
                UpdateChecker.download(context, v.apkUrl, v.sha256) { done, total ->
                    // 进度回调在 IO 线程，这里只更新一个字符串变量 ——
                    // Compose 的 state 写入本身是线程安全的，够用。
                    status = if (total > 0) {
                        "正在下载 " + (done * 100 / total) + "%…"
                    } else {
                        "已下载 " + UpdateChecker.humanSize(done) + "…"
                    }
                }
            }
            busy = false
            if (apk == null) {
                status = "下载失败，请检查网络后重试。"
                return@launch
            }
            val intent = UpdateChecker.installIntent(context, apk)
            if (intent == null) {
                status = "下载完成，但无法调用系统安装器。"
                return@launch
            }
            status = "下载完成，请在系统界面里确认安装。"
            runCatching { context.startActivity(intent) }
                .onFailure { status = "打不开系统安装器；请到系统设置里允许本应用「安装未知应用」。"; }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("检查更新", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "当前版本 " + UpdateChecker.currentVersion() + "。更新包从项目 GitHub 发布页获取——" +
                "只有你点这个按钮时才会联网，应用不会在后台自动检查。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.md))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { check() }, enabled = !busy) {
                Text(if (busy) "处理中…" else "检查更新")
            }
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

    val v = pending
    if (v != null) {
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("发现新版本 " + v.versionName) },
            text = {
                Column {
                    Text(
                        UpdateChecker.humanSize(v.sizeBytes) + " · 下载后由系统安装器覆盖安装，数据保留。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (v.notes.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            // release notes 可能很长，截断 —— 完整内容用户可以到发布页看。
                            v.notes.take(400) + if (v.notes.length > 400) "…" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { downloadAndInstall(v) }) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("稍后") }
            }
        )
    }
}
