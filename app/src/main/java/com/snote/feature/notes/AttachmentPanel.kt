package com.snote.feature.notes

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.snote.core.feature.FeatureController
import com.snote.core.feature.FeatureRegistry
import com.snote.feature.attachments.Attachment
import com.snote.feature.attachments.AttachmentStore
import com.snote.feature.ocr.OcrEngine
import com.snote.ui.theme.Spacing
import java.io.File
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 文档的附件区。
 *
 * ## 附件挂在文档上，不挂在卡片上
 *
 * 用户上传的是一份资料（PDF、照片、录音），它是**整篇文档的证据**；
 * 挂到某张卡片上会强迫用户先想清楚「这份资料属于哪个知识点」，
 * 而多数时候他自己也不知道，是看完之后才切出卡片的。
 *
 * ## 缩略图按需生成
 *
 * 列表里只对图片生成缩略图，且生成一次就落盘复用。非图片（PDF 等）
 * 显示扩展名，不做渲染 —— 渲染 PDF 首页要引库，与体积约束冲突。
 */
@Composable
fun AttachmentPanel(
    store: AttachmentStore,
    dedupEnabled: Boolean,
    onChanged: () -> Unit,
) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    val items = remember(version) { store.list() }

    /**
     * OCR 是否可用。
     *
     * 与其它功能开关同一条纪律：关掉时入口必须**真的消失**，
     * 而不是点下去才报错 —— 后者会让用户以为功能坏了。
     */
    val featureController = remember { FeatureController(context.applicationContext) }
    val ocrUsable = featureController.isUsable(FeatureRegistry.OCR)

    // 单选：一次导入一份。多选会让「去重命中哪几个」的反馈变得很难读。
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val before = store.list().size
        val added = store.import(uri, dedup = dedupEnabled)
        when {
            added == null -> notice = "导入失败：读不到这个文件。"
            store.list().size == before -> notice = "这个文件已经在库里了（内容哈希相同），没有重复存。"
            else -> notice = "已导入。"
        }
        version++
        onChanged()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("附件", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                notice = null
                // "*/*"：不限定类型，用户可能导入 PDF、图片、文本、压缩包。
                picker.launch(arrayOf("*/*"))
            }) { Text("导入文件") }
        }

        if (!dedupEnabled) {
            Text(
                "去重已关闭：同一文件会各存一份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        notice?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(Spacing.sm))

        if (items.isEmpty()) {
            Text(
                "还没有附件。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            items.forEach { a ->
                AttachmentRow(
                    attachment = a,
                    store = store,
                    ocrUsable = ocrUsable,
                    onNotice = { notice = it },
                    onDelete = {
                        store.delete(a.id)
                        version++
                        onChanged()
                    }
                )
                Spacer(Modifier.height(Spacing.sm))
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: Attachment,
    store: AttachmentStore,
    /** OCR 功能是否可用；关掉时「识别文字」按钮不出现。 */
    ocrUsable: Boolean,
    onNotice: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(attachment.id) { mutableStateOf(false) }
    /** 识别结果就地展开给用户看，不弹窗 —— 他要的往往是复制其中一段。 */
    var ocrText by remember(attachment.id) { mutableStateOf<String?>(null) }

    // 只有图片才谈得上识别：Tesseract 吃的是位图，对 PDF 直接喂会失败。
    // 判定用 mime 前缀而不是扩展名 —— mime 来自系统，比文件名可靠。
    val isImage = attachment.mime?.startsWith("image/") == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val thumb = remember(attachment.id) { store.thumbnail(attachment) }
            if (thumb != null) {
                val bmp = remember(thumb.absolutePath) {
                    BitmapFactory.decodeFile(thumb.absolutePath)
                }
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = attachment.mime,
                        modifier = Modifier.size(48.dp),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(Spacing.md))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    nameOf(attachment),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    humanSize(attachment.bytes) + "　" + (attachment.mime ?: "未知类型"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (ocrUsable && isImage) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        ocrText = null
                        onNotice("正在识别…")
                        scope.launch {
                            // 识别是 CPU 重活，必须离开主线程，否则界面直接冻住。
                            val r = withContext(Dispatchers.IO) {
                                OcrEngine.recognize(context, store.fileOf(attachment))
                            }
                            busy = false
                            if (r.ok) {
                                ocrText = r.text.ifBlank { "（这张图里没有识别到文字）" }
                                onNotice("识别完成。")
                            } else {
                                onNotice(r.message)
                            }
                        }
                    }
                ) { Text(if (busy) "识别中…" else "识别文字") }
            }
            TextButton(onClick = onDelete) { Text("删除") }
        }
    }

    ocrText?.let { txt ->
        // 结果单独一张卡放在下面，而不是塞进上面那行的 Column ——
        // 识别出的文字可能几百字，挤在文件名旁边会把整行撑变形。
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                txt,
                modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/** 从 rel_path 取文件名。rel_path 形如 attachments/ab/<sha>.pdf。 */
private fun nameOf(a: Attachment): String =
    a.relPath.substringAfterLast(File.separatorChar).ifBlank { a.sha256.take(12) }

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> (bytes / 1024) .toString() + " KB"
    else -> String.format("%.1f MB", bytes / 1024.0 / 1024.0)
}
