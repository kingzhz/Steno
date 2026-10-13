package com.snote.feature.notes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.snote.core.search.LinkIndex
import com.snote.ui.theme.Spacing

/** 撤销栈上限。超了丢最旧的一步，避免长时间编辑吃内存。 */
private const val MAX_UNDO = 100

/** 输入停顿多久算「一次编辑」，打撤销点并入盘。 */
private const val IDLE_MS = 1200L

/**
 * 卡片式编辑器。
 *
 * ## 不教用户语法
 *
 * 界面上没有任何标记符号 —— 一张卡片就是一个标题框 + 一个内容框，
 * 底部「+ 新建分节」加一块。链接也全自动：正文里出现别的卡片名时自动建链，
 * 并在这里把那些字染成强调色。用户从头到尾不需要知道 [[ ]] 或 ## 存在。
 *
 * ## 跳转只在侧边栏
 *
 * 正文里的高亮**不可点** —— TextField 内可点击文本会和选区、输入法抢手势。
 * 点右上角「链接」滑出侧边栏，那里列出反向链接与出链，点条目才跳。
 *
 * ## 保存
 *
 *   自动 —— 输入停顿 1.2 秒落盘，标题栏显示「已保存 HH:mm」；
 *   手动 —— 右上角「保存」，点了立刻落盘；
 *   兜底 —— 按返回时再存一次。
 *
 * ## 撤销 / 重做
 *
 * 快照栈，单位是「一次停顿的编辑」，上限 100 步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    repo: NoteRepository,
    docId: Long,
    onBack: () -> Unit,
    onOpenDoc: (Long) -> Unit,
    /**
     * 从检索页跳进来时要定位到的卡片 id；0 = 不需要定位。
     *
     * 刻意不叫「高亮卡片」：定位与高亮是同一个动作的两面 ——
     * 只滚不亮，用户看不出停在哪；只亮不滚，长文档里那一圈框在屏幕外。
     */
    focusCardId: Long = 0L,
) {
    val initial = remember(docId) { repo.loadDraft(docId) }

    if (initial == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("编辑") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("返回") } }
                )
            }
        ) { padding ->
            Text(
                "文档不存在（可能已被删除）",
                modifier = Modifier.padding(padding).padding(Spacing.lg)
            )
        }
        return
    }

    var state by remember(docId) { mutableStateOf(initial) }
    /** 上一次已压入撤销栈的状态，用于判断「这一段编辑是否已记账」。 */
    var stable by remember(docId) { mutableStateOf(initial) }
    /** 上一次真正写进库的内容，用来避免保存后回填 id 触发二次保存。 */
    var lastSaved by remember(docId) { mutableStateOf(initial) }

    val undoStack = remember(docId) { mutableStateListOf<DocDraft>() }
    val redoStack = remember(docId) { mutableStateListOf<DocDraft>() }
    var savedAt by remember(docId) { mutableStateOf<Long?>(null) }
    var dirty by remember(docId) { mutableStateOf(false) }

    var pendingDelete by remember { mutableStateOf<Int?>(null) }
    var panelOpen by remember { mutableStateOf(false) }

    val context = LocalContext.current

    /** 标签与别名：共用一个 store，与 repo 用同一个 helper。 */
    val tagStore = remember { TagStore(repo.dbHelper) }

    /**
     * 附件与功能开关。
     *
     * 去重开关直接读 FeatureStateStore：用户可能在设置里关掉去重，
     * 而编辑器不会因为切页面重建，所以每次导入时现读，不缓存。
     */
    val attachmentStore = remember { com.snote.feature.attachments.AttachmentStore(context) }
    val featureState = remember { com.snote.core.feature.FeatureStateStore(context) }
    val featureController = remember { com.snote.core.feature.FeatureController(context) }

    /**
     * 双链功能是否可用。
     *
     * 关掉后：侧边栏入口消失、正文不高亮。link 表里的行不删（源数据），
     * 重新打开就恢复 —— 这是「关功能不等于删数据」在界面上的落点。
     */
    val wikiUsable = featureController.isUsable(com.snote.core.feature.FeatureRegistry.WIKI)
    var attachmentVersion by remember(docId) { mutableStateOf(0) }

    /** 正在编辑标签/别名的卡片 id；null = 对话框关闭。 */
    var metaCardId by remember(docId) { mutableStateOf<Long?>(null) }
    /** 标签/别名改动的版本号，驱动对话框内的列表刷新。 */
    var metaVersion by remember(docId) { mutableStateOf(0) }
    /** 每次保存后自增，驱动高亮词典与侧边栏重新读取。 */
    var linkVersion by remember(docId) { mutableStateOf(0) }

    // 高亮词典：其他卡片的标题。保存后才刷新（编辑中的新标题还不在库里）。
    // 关掉双链功能时给一个空词典：正文不再上色，但字符与光标行为完全不变
    // （LinkHighlightTransformation 是恒等映射，只加样式不改文本）。
    val linkIndex = remember(docId, linkVersion, wikiUsable) {
        if (wikiUsable) repo.linkIndex() else LinkIndex(emptyList())
    }
    val accent: Color = MaterialTheme.colorScheme.primary

    /** 编辑器滚动位置；跳转定位要驱动它滚到某张卡。 */
    val scroll = rememberScrollState()

    /**
     * 每张卡的 y 坐标（相对内容列顶部）。
     *
     * 为什么不用 key 的索引算位置：卡片高度随正文长短差几十倍，
     * 只能量不能算。用 mutableStateMapOf 而不是普通 Map，是因为
     * onGloballyPositioned 会在布局完成后才填充它 —— 普通 Map 改了不触发重组，
     * LanchedEffect 拿到的会永远是空表。
     */
    val cardTops = remember(docId) { mutableStateMapOf<Long, Int>() }

    /** 当前要高亮的卡片；进入时来自检索页，用户一点编辑就清掉。 */
    var focus by remember(docId, focusCardId) { mutableStateOf(focusCardId) }

    /**
     * 还没滚过去的定位目标；滚完立刻归零。
     *
     * 为什么不能直接用 focus 当触发条件：卡片坐标是布局完成后才填进 cardTops 的，
     * 表一变（比如用户后来又加了一张卡）就会重跑副作用，把视图又拽回那张卡上。
     * 定位是一次性动作，所以用一个滚完就清的目标变量。
     */
    var pendingScroll by remember(docId, focusCardId) { mutableStateOf(focusCardId) }

    fun commit(next: DocDraft) {
        // 用户开始动手就不再高亮：那一圈强调色是「你要找的是这张」，
        // 找到之后还挂着，会让人以为卡片进了什么特殊状态。
        if (focus != 0L) focus = 0L
        state = next
    }

    // 布局完成、坐标齐了再滚；滚完把目标清掉，避免后续布局变化又把它拽回来。
    LaunchedEffect(pendingScroll, cardTops.size) {
        val target = pendingScroll
        if (target == 0L) return@LaunchedEffect
        val y = cardTops[target] ?: return@LaunchedEffect
        // 留一段余量：目标停在顶端时，上下文（上一张卡的尾巴）也还看得见。
        scroll.animateScrollTo((y - 96).coerceAtLeast(0))
        pendingScroll = 0L
    }

    fun saveNow() {
        val saved = repo.saveDraft(state)
        lastSaved = saved
        savedAt = System.currentTimeMillis()
        if (saved != state) {
            stable = saved
            state = saved
        }
        dirty = false
        linkVersion++
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeAt(undoStack.size - 1)
        redoStack.add(state)
        if (redoStack.size > MAX_UNDO) redoStack.removeAt(0)
        stable = prev
        state = prev
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.size - 1)
        undoStack.add(state)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        stable = next
        state = next
    }

    fun leave() {
        saveNow()
        onBack()
    }

    BackHandler(enabled = !panelOpen) { leave() }

    // 防抖：每次输入变化都会取消上一次等待；停够 IDLE_MS 才落盘。
    LaunchedEffect(state) {
        if (state == lastSaved) {
            dirty = false
            return@LaunchedEffect
        }
        dirty = true
        delay(IDLE_MS)

        if (state != stable) {
            undoStack.add(stable)
            if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
            redoStack.clear()
            stable = state
        }

        val saved = repo.saveDraft(state)
        lastSaved = saved
        savedAt = System.currentTimeMillis()
        if (saved != state) {
            stable = saved
            state = saved
        }
        dirty = false
        linkVersion++
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            when {
                                dirty -> "编辑中…"
                                savedAt != null -> "已保存 " + formatClock(savedAt!!)
                                else -> "编辑"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        TextButton(onClick = { leave() }) { Text("返回") }
                    },
                    actions = {
                        TextButton(onClick = { undo() }, enabled = undoStack.isNotEmpty()) { Text("撤销") }
                        TextButton(onClick = { redo() }, enabled = redoStack.isNotEmpty()) { Text("重做") }
                        if (wikiUsable) {
                            TextButton(onClick = { panelOpen = true }) { Text("链接") }
                        }
                        TextButton(onClick = { saveNow() }) { Text("保存") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(scroll)
                    .padding(horizontal = Spacing.lg)
            ) {
                Spacer(Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { commit(state.copy(title = it)) },
                    label = { Text("笔记标题") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(Spacing.lg))

                state.cards.forEachIndexed { index, card ->
                    // 只有已落库的卡片才可能被检索命中（新卡 id = 0），
                    // 所以量位置也只对有 id 的卡有意义。
                    val focused = card.id != 0L && card.id == focus
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onGloballyPositioned { coords ->
                                if (card.id != 0L) cardTops[card.id] = coords.positionInParent().y.toInt()
                            },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        // 描边而不是填色：填色会和「卡片是容器」的既有视觉语言打架，
                        // 一圈 2dp 的强调色边足够把视线钉住，又不改变卡片本身的样子。
                        border = if (focused) BorderStroke(2.dp, accent) else null
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "卡片 " + (index + 1),
                                    style = MaterialTheme.typography.labelLarge,
                                    // 结构标题用正文色：它是这一块的标题，不是附注。
                                    // 用次要色会与下面的输入框正文糊成同一层级。
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                // 只有已落库的卡片才能打标签（新卡 id = 0，标签会挂空）。
                                if (card.id > 0) {
                                    TextButton(onClick = { metaCardId = card.id }) { Text("标签") }
                                }
                                TextButton(onClick = { pendingDelete = index }) { Text("删除") }
                            }
                            OutlinedTextField(
                                value = card.title,
                                onValueChange = { t ->
                                    commit(
                                        state.copy(
                                            cards = state.cards.toMutableList().also {
                                                it[index] = card.copy(title = t)
                                            }
                                        )
                                    )
                                },
                                label = { Text("卡片标题") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(Spacing.sm))
                            OutlinedTextField(
                                value = card.body,
                                onValueChange = { b ->
                                    commit(
                                        state.copy(
                                            cards = state.cards.toMutableList().also {
                                                it[index] = card.copy(body = b)
                                            }
                                        )
                                    )
                                },
                                label = { Text("内容") },
                                minLines = 3,
                                visualTransformation = LinkHighlightTransformation(
                                    index = linkIndex,
                                    selfCardId = card.id,
                                    color = accent
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            // 标签/别名预览：让用户不点开也知道这张卡带了什么。
                            if (card.id > 0) {
                                val tags = remember(card.id, metaVersion) { tagStore.tagsOf(card.id) }
                                val aliases = remember(card.id, metaVersion) { tagStore.aliasesOf(card.id) }
                                if (tags.isNotEmpty() || aliases.isNotEmpty()) {
                                    Spacer(Modifier.height(Spacing.sm))
                                    Text(
                                        buildString {
                                            if (tags.isNotEmpty()) append("标签：" + tags.joinToString("、"))
                                            if (tags.isNotEmpty() && aliases.isNotEmpty()) append("　")
                                            if (aliases.isNotEmpty()) append("别名：" + aliases.joinToString("、"))
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.md))
                }

                OutlinedButton(
                    onClick = {
                        commit(state.copy(cards = state.cards + CardDraft(0L, "", "")))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ 新建分节") }

                Spacer(Modifier.height(Spacing.xl))

                // 附件区。key 到 attachmentVersion 只是为了让「导入后立刻出现」
                // 这件事不依赖内部 version 与这里的同步。
                AttachmentPanel(
                    store = attachmentStore,
                    dedupEnabled = featureState.isEnabled(
                        com.snote.core.feature.FeatureRegistry.DEDUP.id
                    ),
                    onChanged = { attachmentVersion++ }
                )

                Spacer(Modifier.height(Spacing.xl))
            }
        }

        val metaId = metaCardId
        if (metaId != null) {
            // key 到 metaVersion：每次增删都重新读一遍，否则刚加的标签不显示。
            val tags = remember(metaId, metaVersion) { tagStore.tagsOf(metaId) }
            val aliases = remember(metaId, metaVersion) { tagStore.aliasesOf(metaId) }
            val cardTitle = state.cards.firstOrNull { it.id == metaId }?.title ?: ""
            CardMetaDialog(
                cardId = metaId,
                cardTitle = cardTitle,
                tags = tags,
                aliases = aliases,
                onAddTag = {
                    tagStore.addTag(metaId, it)
                    metaVersion++
                },
                onRemoveTag = {
                    tagStore.removeTag(metaId, it)
                    metaVersion++
                },
                onAddAlias = {
                    tagStore.addAlias(metaId, it)
                    metaVersion++
                },
                onRemoveAlias = {
                    tagStore.removeAlias(metaId, it)
                    metaVersion++
                },
                onDismiss = { metaCardId = null }
            )
        }

        if (panelOpen) {
            LinkPanel(
                repo = repo,
                linkVersion = linkVersion,
                index = linkIndex,
                cards = state.cards,
                noteTitle = state.title,
                onClose = { panelOpen = false },
                onOpenDoc = { docId2 ->
                    panelOpen = false
                    onOpenDoc(docId2)
                }
            )
        }
    }

    val delIndex = pendingDelete
    if (delIndex != null) {
        val cardTitle = state.cards.getOrNull(delIndex)?.title.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这张卡片") },
            text = {
                Text(
                    if (cardTitle.isBlank()) "这张卡片的内容会被删除。"
                    else "「" + cardTitle + "」的内容会被删除。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val next = state.cards.toMutableList().also { it.removeAt(delIndex) }
                    commit(state.copy(cards = if (next.isEmpty()) listOf(CardDraft(0L, "", "")) else next))
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

/**
 * 右侧滑出的链接面板。
 *
 * 分两区：**反向链接**（谁提到我）与**相关**（我提到谁）。
 * 反链是发现的入口 —— 它告诉你「这个概念在别处也被用到了」。
 */
@Composable
private fun LinkPanel(
    repo: NoteRepository,
    linkVersion: Int,
    index: LinkIndex,
    cards: List<CardDraft>,
    noteTitle: String,
    onClose: () -> Unit,
    onOpenDoc: (Long) -> Unit,
) {
    // 以当前文档的第一张已落库卡片为主视角；没有则退化为全空的提示。
    val mainCardId = cards.firstOrNull { it.id > 0 }?.id ?: 0L
    val backs = remember(linkVersion, mainCardId) { repo.backlinks(mainCardId) }
    val outs = remember(linkVersion, mainCardId) { repo.outlinks(mainCardId) }

    // 正文里出现但还没建卡的候选词（红链）。
    val known = remember(index) { index.allTerms() }
    val reds = remember(linkVersion, known) {
        val text = cards.joinToString("\n") { it.title + "\n" + it.body }
        suggestMissingTerms(known, text, noteTitle)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable { onClose() }
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(0.82f)
                .background(MaterialTheme.colorScheme.surface)
                .padding(Spacing.lg)
                .verticalScroll(rememberScrollState())
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("链接", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("关闭") }
            }
            Spacer(Modifier.height(Spacing.lg))

            SectionTitle("反向链接（谁提到我）")
            if (backs.isEmpty()) {
                Hint("还没有别的卡片提到这里。")
            } else {
                backs.forEach { r ->
                    LinkRow(r.cardTitle, r.docTitle, onClick = { onOpenDoc(r.docId) })
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionTitle("相关（我提到谁）")
            if (outs.isEmpty()) {
                Hint("正文里还没出现别的卡片名。")
            } else {
                outs.forEach { r ->
                    LinkRow(r.cardTitle, r.docTitle, onClick = { onOpenDoc(r.docId) })
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionTitle("可能值得建卡")
            if (reds.isEmpty()) {
                Hint("暂无候选。")
            } else {
                reds.forEach { term ->
                    LinkRow(term, "正文中出现，尚未建卡", onClick = { })
                }
            }

            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        // 分区标题（「反向链接（谁提到我）」这类）是结构，不是附注 ——
        // 用次要色会让它和下面的列表项分不开，用户扫不到分区边界。
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(Modifier.height(Spacing.sm))
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun LinkRow(title: String, sub: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title.ifBlank { "未命名卡片" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text("跳转", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * 粗筛「像专有名词、但库里还没有」的候选：连续 2-8 个汉字且不是已有卡片名。
 *
 * 刻意不做分词 —— 这一版只求给个人提个醒，宁可多列几个也不要漏掉真正想建的概念。
 */
private fun suggestMissingTerms(known: Set<String>, text: String, selfTitle: String): List<String> {
    val counts = LinkedHashMap<String, Int>()
    val sb = StringBuilder()
    fun flush() {
        val s = sb.toString()
        sb.setLength(0)
        if (s.length < 2 || s.length > 8) return
        if (s in known) return
        if (s == selfTitle.trim()) return
        counts[s] = (counts[s] ?: 0) + 1
    }
    for (ch in text) {
        if (ch.code in 0x4E00..0x9FFF) sb.append(ch) else flush()
    }
    flush()
    return counts.entries
        .filter { it.value >= 2 }
        .sortedByDescending { it.value }
        .take(8)
        .map { it.key }
}

private fun formatClock(ms: Long): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))