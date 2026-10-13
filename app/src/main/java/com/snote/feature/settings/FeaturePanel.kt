package com.snote.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import com.snote.core.feature.FeatureController
import com.snote.core.feature.FeatureDef
import com.snote.core.feature.FeatureRegistry
import com.snote.core.feature.FeatureStateStore
import com.snote.feature.web.WebSearch
import com.snote.ui.theme.Spacing

/**
 * 功能开关面板。
 *
 * 同时是预算门（花钱功能开关必弹）的可运行参照实现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeaturePanel(
    onBack: () -> Unit,
    /** 打开记忆管理。从功能面板进它，是因为「长期记忆」本身就是个功能开关。 */
    onOpenMemory: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = remember { FeatureStateStore(context.applicationContext) }
    val controller = remember { FeatureController(context.applicationContext) }
    /**
     * 搜索器只为读用量：额度是「用户看不见就不知道要省」的东西，
     * 必须在开关这一页能看到今天用了多少。
     */
    val web = remember { WebSearch(context.applicationContext) }
    var usage by remember { mutableStateOf(web.usage()) }
    var enabled by remember { mutableStateOf(store.loadAll()) }
    var pending: Pair<FeatureDef, Boolean>? by remember { mutableStateOf(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    fun isOn(def: FeatureDef): Boolean = enabled[def.id] ?: def.defaultEnabled

    /**
     * 执行开关。
     *
     * 状态由 controller 写（它先做动作、成功了才写），这里只负责重新读一遍 ——
     * 不自己算状态，否则动作失败时界面会显示一个假的新状态。
     */
    fun apply(def: FeatureDef, next: Boolean) {
        val result = controller.setEnabled(def, next)
        enabled = store.loadAll()
        usage = web.usage()
        notice = result.message
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("功能开关") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
        notice?.let {
            Text(
                it,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            items(FeatureRegistry.ALL, key = { it.id }) { def ->
                // 依赖没开的功能不能操作：否则会得到一个「开着但用不了」的假状态。
                val blockedBy = def.requires.filterNot { isOn(FeatureRegistry.byId(it)!!) }
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
                            Text(def.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                def.summary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (blockedBy.isNotEmpty()) {
                                Text(
                                    "需要先开启：" + blockedBy.mapNotNull {
                                        FeatureRegistry.byId(it)?.title
                                    }.joinToString("、"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            // 长期记忆：给一个进管理页的入口。
                            // 放在功能项里面，是因为用户想「管记忆」时第一反应
                            // 是去功能开关里找它 —— 那正是他认识这个功能的地方。
                            if (def.id == FeatureRegistry.MEMORY.id && isOn(def)) {
                                Spacer(Modifier.height(Spacing.xs))
                                TextButton(onClick = onOpenMemory) { Text("管理记忆") }
                            }
                            // 联网搜索：把每个源今天的用量摆出来。
                            // 不显示的话，用户撞到上限只会看到「搜不到」，无从判断原因。
                            if (def.id == FeatureRegistry.WEB_SEARCH.id && isOn(def)) {
                                Spacer(Modifier.height(Spacing.sm))
                                usage.forEach { u ->
                                    Text(
                                        u.label + " 今天 " + u.used + " / " + u.limit,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (u.used >= u.limit)
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        Switch(
                            checked = isOn(def),
                            enabled = blockedBy.isEmpty(),
                            onCheckedChange = { next ->
                                // 花钱的功能：开和关都要弹预算门。
                                if (def.isPaid) pending = def to next else apply(def, next)
                            }
                        )
                    }
                }
            }
        }
        }
    }

    val p = pending
    if (p != null) {
        val def = p.first
        val next = p.second
        val affected = FeatureRegistry.dependentsOf(setOf(def.id))
            .mapNotNull { FeatureRegistry.byId(it)?.title }
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(if (next) "开启「" + def.title + "」" else "关闭「" + def.title + "」") },
            text = {
                Column {
                    if (next) {
                        Text(def.rebuildNote ?: "开启后需要重建派生物。")
                        Text(
                            "重建会调用外部接口并产生费用，且不可逆。",
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text("关闭将卸载该功能的索引与派生文件；源数据（卡片、原文、链接）不会删除。")
                        if (affected.isNotEmpty()) {
                            Text("以下功能依赖它，会一并停用：" + affected.joinToString("、"))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { apply(def, next); pending = null }) { Text("确认") }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } }
        )
    }
}
