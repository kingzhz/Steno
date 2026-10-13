package com.snote.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.snote.core.credential.CodeBuddyClient
import com.snote.core.credential.CredentialStore
import com.snote.core.feature.FeatureController
import com.snote.core.feature.FeatureRegistry
import com.snote.feature.chat.ChatScreen
import com.snote.feature.llm.CodeBuddyGateway
import com.snote.feature.llm.LlmGateway
import com.snote.feature.llm.ModelCatalog
import com.snote.feature.notes.EditorScreen
import com.snote.feature.notes.NoteRepository
import com.snote.feature.notes.NotesListScreen
import com.snote.feature.notes.TagScreen
import com.snote.feature.search.SearchScreen
import com.snote.feature.settings.FeaturePanel
import com.snote.feature.settings.MemoryScreen
import com.snote.feature.settings.SettingsScreen
import com.snote.ui.theme.Accent
import com.snote.ui.theme.ThemePreset

/**
 * 顶层导航。
 *
 * ## 两层结构
 *
 * **底层**是笔记与对话两个主窗口，用底部按钮切换 —— 它们是并列的两件事，
 * 用户会来回跳：在对话里问出结论，切到笔记记下来。
 *
 * **上层**是编辑器、检索、设置这类「进入某个具体对象」的页面，用状态压栈，
 * 底部栏在这些页面隐藏（它们不是主窗口，不该有切窗口的入口）。
 *
 * 不引 navigation 库：页面少，一个 sealed interface 足够，还能省一条依赖。
 */
private sealed interface Screen {
    data object Notes : Screen
    /**
     * @param focusCardId 进编辑器后要定位并高亮的卡片；0 = 从列表进来，不需要定位。
     */
    data class Editor(val docId: Long, val focusCardId: Long = 0L) : Screen
    data object Features : Screen
    data object Memory : Screen
    data object Search : Screen
    data object Tags : Screen
    data object Settings : Screen
}

/** 两个主窗口。 */
private enum class Tab(val label: String, val icon: ImageVector) {
    Notes("笔记", Icons.Filled.Edit),
    // 用 Email 而不是 Chat：material-icons-core（Compose 自带的基础集）里没有
    // Chat 图标，引 material-icons-extended 会多背约 2MB，与体积约束冲突。
    Chat("对话", Icons.Filled.Email),
}

@Composable
fun AppRoot(
    /** 当前配色，由 MainActivity 持有并下发（主题要包住本组件，状态不能放这里）。 */
    preset: ThemePreset = ThemePreset.LIGHT,
    accent: Accent = Accent.SKY,
    /** 当前材质：经典实色或液态玻璃。 */
    /** 用户在设置页改配色时回调上去，由 MainActivity 落盘并重组主题。 */
    onThemeChange: (ThemePreset, Accent) -> Unit = { _, _ -> },
    /** 改材质时回调上去。 */
) {
    val context = LocalContext.current
    val repo = remember { NoteRepository(context.applicationContext) }
    val credentials = remember { CredentialStore(context.applicationContext) }
    val codeBuddy = remember { CodeBuddyClient() }
    val features = remember { FeatureController(context.applicationContext) }

    /** 上层页面；null = 停在主窗口。 */
    var overlay by remember { mutableStateOf<Screen?>(null) }

    /** 当前主窗口。 */
    var tab by remember { mutableStateOf(Tab.Notes) }

    /** 从对话页进设置时，返回要回对话页。 */
    var settingsFromChat by remember { mutableStateOf(false) }

    /**
     * 每次调用都重新读一次账号，不缓存 gateway。
     *
     * 用户刚在设置里登录完回到对话页，缓存住的旧实例还带着空凭据，
     * 会让他以为登录没生效。
     *
     * 刷新成功时顺带回写存储，避免每次开对话都拿一个已过期的 token 去撞 401。
     */
    fun gatewayOrNull(): LlmGateway? {
        if (credentials.active() == null) return null
        return CodeBuddyGateway(
            client = codeBuddy,
            accountProvider = { credentials.active() },
            onRefreshed = { credentials.upsert(it) },
        )
    }

    /**
     * 模型清单缓存。
     *
     * 必须 invalidate：登录态一变（新增/切换/删除账号），上一个账号能用的模型
     * 对新账号未必可用，缓存会给出一个选了就报错的清单。
     */
    val catalog = remember { ModelCatalog { gatewayOrNull() } }

    /** agent 功能是否可用（自身开着且依赖满足）。关掉后对话窗口整个不出现。 */
    val agentUsable = features.isUsable(FeatureRegistry.AGENT)

    // 上层页面按返回键回主窗口，不能直接退出 App。
    val current = overlay
    if (current != null) {
        BackHandler { overlay = null }
    }


    Scaffold(
        bottomBar = {
            // 只在主窗口显示底部栏：编辑器/设置里没有「切主窗口」的语义。
            if (current == null) {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        // agent 功能关掉后「对话」入口直接不可见 —— 关功能必须真的不可用。
                        if (t == Tab.Chat && !agentUsable) return@forEach
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (current) {
                null -> when (tab) {
                    Tab.Notes -> NotesListScreen(
                        repo = repo,
                        onOpen = { overlay = Screen.Editor(it) },
                        onOpenFeatures = { overlay = Screen.Features },
                        onOpenSearch = { overlay = Screen.Search },
                        showSearchEntry = features.isUsable(FeatureRegistry.SEARCH),
                        onOpenTags = { overlay = Screen.Tags },
                    )
                    Tab.Chat -> ChatScreen(
                        gateway = { gatewayOrNull() },
                        catalog = catalog,
                        repo = repo,
                        onOpenSettings = {
                            settingsFromChat = true
                            overlay = Screen.Settings
                        },
                    )
                }
                is Screen.Editor -> EditorScreen(
                    repo = repo,
                    docId = current.docId,
                    onBack = { overlay = null },
                    // 从编辑器内部跳转（链接面板、双链）不传 focus：
                    // 那是「换一篇看」，不是「找某一处」，没有目标卡片。
                    onOpenDoc = { overlay = Screen.Editor(it) },
                    focusCardId = current.focusCardId,
                )
                Screen.Search -> SearchScreen(
                    repo = repo,
                    // 命中项带着卡片 id 一起来：打开的是整篇文档，但落点是那一张卡。
                    onOpenDoc = { docId, cardId -> overlay = Screen.Editor(docId, cardId) },
                    onBack = { overlay = null },
                )
                Screen.Settings -> SettingsScreen(
                    preset = preset,
                    accent = accent,
                    onThemeChange = onThemeChange,
                    catalog = catalog,
                    onBack = {
                    // 登录态可能已变，模型清单必须作废 —— 否则回到对话页拿的还是旧账号的清单。
                    catalog.invalidate()
                    overlay = null
                    if (settingsFromChat) {
                        tab = Tab.Chat
                        settingsFromChat = false
                    }
                    },
                )
                Screen.Tags -> TagScreen(
                    helper = repo.dbHelper,
                    onOpenDoc = { docId, cardId -> overlay = Screen.Editor(docId, cardId) },
                    onBack = { overlay = null },
                )
                Screen.Features -> FeaturePanel(
                    onBack = { overlay = null },
                    onOpenMemory = { overlay = Screen.Memory },
                )
                Screen.Memory -> MemoryScreen(onBack = { overlay = null })
                // Screen.Notes 只在 tab 分支里用得到；走到这里说明状态异常，
                // 直接回主窗口而不是崩掉。
                Screen.Notes -> overlay = null
            }
        }
    }
}
