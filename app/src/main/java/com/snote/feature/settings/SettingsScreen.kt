package com.snote.feature.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.snote.core.credential.CredentialStore
import com.snote.core.settings.ChoiceStore
import com.snote.core.credential.LoginStage
import com.snote.core.credential.CodeBuddyAccount
import com.snote.core.credential.CodeBuddyClient
import com.snote.feature.llm.ModelCatalog
import com.snote.feature.llm.ModelVision
import com.snote.ui.theme.Accent
import com.snote.ui.theme.Spacing
import com.snote.ui.theme.ThemePreset
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AI 服务设置：CodeBuddy 账号管理。
 *
 * ## 登录流程是「唤起浏览器 + 后台轮询」
 *
 * 点「登录」→ 系统浏览器打开授权页 → 用户在浏览器里完成 → 本页显示轮询进度 →
 * 成功即落到账号列表。**没有回调地址**，所以不需要 deep link，也不需要监听端口。
 *
 * 登录必须在浏览器里做：那是服务商的登录页，有验证码与二次验证，
 * WebView 里跑可能被判定为「不受信环境」。用系统浏览器最稳。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    preset: ThemePreset = ThemePreset.LIGHT,
    accent: Accent = Accent.SKY,
    onThemeChange: (ThemePreset, Accent) -> Unit = { _, _ -> },
    /** 当前材质：经典或液态玻璃。 */
    /** 模型清单，识图模型下拉要用。null 时不显示该项（例如还没登录）。 */
    catalog: ModelCatalog? = null,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CredentialStore(context.applicationContext) }
    val client = remember { CodeBuddyClient() }
    val scope = rememberCoroutineScope()

    var accounts by remember { mutableStateOf(store.list()) }
    var status by remember { mutableStateOf("") }
    var loggingIn by remember { mutableStateOf(false) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var pendingRemove by remember { mutableStateOf<CodeBuddyAccount?>(null) }

    fun refreshAccounts() {
        accounts = store.list()
    }

    fun startLogin() {
        if (loggingIn) return
        loggingIn = true
        status = "正在获取登录地址…"
        loginJob = scope.launch {
            client.login { url ->
                // 用系统浏览器打开：Intent.ACTION_VIEW 交给自己选择的浏览器。
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                } catch (t: Throwable) {
                    status = "打不开浏览器，请手动访问：\n" + url
                }
            }.collect { stage ->
                when (stage) {
                    is LoginStage.Waiting -> status = "已打开浏览器，请在网页里完成登录…"
                    is LoginStage.Polling -> status = "等待授权中（第 " + stage.attempt + " 次查询）"
                    is LoginStage.Succeeded -> {
                        store.upsert(stage.account)
                        refreshAccounts()
                        status = "登录成功：" + stage.account.label
                        loggingIn = false
                    }
                    is LoginStage.Failed -> {
                        status = "登录失败：" + stage.message
                        loggingIn = false
                    }
                }
            }
            loggingIn = false
        }
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
        ) {
            Spacer(Modifier.height(Spacing.md))

            AppearanceSection(
                preset = preset,
                accent = accent,
                onThemeChange = onThemeChange,
            )

            Spacer(Modifier.height(Spacing.lg))

            VisionModelSection(catalog = catalog)

            Spacer(Modifier.height(Spacing.lg))

            Text("CodeBuddy 账号", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "点「登录」会在浏览器里打开授权页，登录完成后本页会自动识别。支持多个账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(Spacing.md))

            if (accounts.isEmpty()) {
                Text(
                    "还没有账号。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                accounts.forEach { acc ->
                    val active = store.activeId() == acc.id
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    acc.label + if (active) "（当前）" else "",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "有效期至 " + formatExpiry(acc.expiresAtMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!active) {
                                TextButton(onClick = {
                                    store.setActive(acc.id)
                                    refreshAccounts()
                                    status = "已切换到 " + acc.label
                                }) { Text("切换") }
                            }
                            TextButton(onClick = { pendingRemove = acc }) { Text("删除") }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Button(onClick = { startLogin() }, enabled = !loggingIn) {
                    Text(if (loggingIn) "登录中…" else "登录新账号")
                }
                if (loggingIn) {
                    OutlinedButton(onClick = {
                        loginJob?.cancel()
                        loggingIn = false
                        status = "已取消登录"
                    }) { Text("取消") }
                }
            }

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(Spacing.xl))
            Text(
                "凭据以明文存放在本机应用私有目录，不会上传。\n" +
                    "登录态来自 CodeBuddy 官方，与 App 无关；退出登录只能删账号。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xl))
        }
    }

    val target = pendingRemove
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("删除账号") },
            text = { Text("「" + target.label + "」的本地凭据会被删除，需要重新登录才能再用。") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(target.id)
                    refreshAccounts()
                    status = "已删除 " + target.label
                    pendingRemove = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("取消") } }
        )
    }
}

/**
 * 外观设置区。
 *
 * 从 [SettingsScreen] 里抽出来是为了能单独预览：整页要构造 CredentialStore
 * 与 CodeBuddyClient（需要 Context），而 Preview 没有 Application。
 * 这一段只吃枚举与回调，因此在预览里能直接看三套底色与五个强调色的实际观感。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSection(
    preset: ThemePreset,
    accent: Accent,
    onThemeChange: (ThemePreset, Accent) -> Unit = { _, _ -> },
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("外观", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "底色",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ThemePreset.entries.forEach { p ->
                // 选中态用实心与描边的视觉差表示，不额外加文字 ——
                // 免得几个按钮的文字长度不一致导致排版跳动。
                if (p == preset) {
                    Button(onClick = { onThemeChange(p, accent) }) { Text(p.label) }
                } else {
                    OutlinedButton(onClick = { onThemeChange(p, accent) }) { Text(p.label) }
                }
            }
        }

        Spacer(Modifier.height(Spacing.md))
        Text(
            "强调色",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // 五个色块一行放不下，用 FlowRow 让它自然换行。
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Accent.entries.forEach { a ->
                if (a == accent) {
                    Button(onClick = { onThemeChange(preset, a) }) { Text(a.label) }
                } else {
                    OutlinedButton(onClick = { onThemeChange(preset, a) }) { Text(a.label) }
                }
            }
        }
    }
}

/**
 * 识图模型选择。
 *
 * ## 为什么需要它
 *
 * 当前模型不确定能识图时，含图的那一轮会临时换用它，下一轮自动换回
 * （见 ChatScreen.send 里的转交判定）。默认值给 deepseek-v4.1-flash：
 * 服务端两端点都声明它 supportsImages=true，且实测发纯红图能答出颜色。
 *
 * ## 为什么候选只列「确定能识图」的
 *
 * 服务端两个端点对同一模型会互相矛盾（glm-5.1 在一边 false、一边 true），
 * 把不确定的也列出来，用户选了一个其实不能识图的，会得到「换了模型还是
 * 看不了图」的困惑结果。宁可候选少几个，也要每个都能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisionModelSection(catalog: ModelCatalog?) {
    if (catalog == null) return
    val context = LocalContext.current
    val choices = remember { ChoiceStore(context.applicationContext) }
    var current by remember {
        mutableStateOf(choices.get(ChoiceStore.VISION_MODEL) ?: ChoiceStore.DEFAULT_VISION_MODEL)
    }
    var options by remember { mutableStateOf(listOf(current)) }
    var expanded by remember { mutableStateOf(false) }

    // 候选来自远端清单与已知能力表的交集：远端可能拉失败，那样只剩默认值，
    // 至少不会给出一个「选不了」的空下拉。
    LaunchedEffect(Unit) {
        val all = runCatching { catalog.models() }.getOrDefault(emptyList())
        options = (listOf(current) + ModelVision.visionCandidates(all)).distinct()
    }

    Text("识图模型", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Spacing.sm))
    Text(
        "当前模型不能识图时，含图片的那一轮会临时换用它，下一轮自动换回。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(Spacing.sm))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(current, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (expanded) "收起" else "更换",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (expanded) {
                HorizontalDivider()
                options.forEach { m ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                current = m
                                choices.put(ChoiceStore.VISION_MODEL, m)
                                expanded = false
                            }
                            .padding(Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            m,
                            modifier = Modifier.weight(1f),
                            color = if (m == current) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                        if (m == current) Text("当前", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun formatExpiry(ms: Long): String {
    if (ms <= 0) return "未知"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
}