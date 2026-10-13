package com.snote.feature.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import android.net.Uri
import com.snote.core.feature.FeatureController
import com.snote.core.feature.FeatureRegistry
import com.snote.core.settings.ChoiceStore
import com.snote.feature.agent.AgentEvent
import com.snote.feature.agent.AgentRunner
import com.snote.feature.agent.KbToolExecutor
import com.snote.feature.agent.PendingOp
import com.snote.feature.agent.PendingStore
import com.snote.feature.doc.DocText
import com.snote.feature.llm.ImageEncoder
import com.snote.feature.llm.LlmGateway
import com.snote.feature.llm.LlmMessage
import com.snote.feature.llm.ModelVision
import com.snote.feature.llm.ModelCatalog
import com.snote.feature.notes.NoteRepository
import com.snote.feature.web.WebSearch
import com.snote.ui.MarkdownText
import com.snote.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话里的一行。
 *
 * role 有四种取值，前两种是真正的对话轮次，后两种只是展示：
 *   user / assistant —— 送进模型上下文的真实对话；
 *   tool            —— 工具调用与结果，**不**进上下文（结果已由 tool 消息单独回给模型）；
 *   queued          —— 已进待审队列的写操作。
 *
 * 用 role 而不是另加字段来区分，是为了让 ChatStore 的两列（role/content）原样存下，
 * 重新打开历史时渲染方式不会丢。
 */
data class ChatLine(
    val role: String,
    val text: String,
    val opId: Long = 0L,
    /**
     * 发给模型的图片（原尺寸 data URL）。
     *
     * 只在**这一轮**有效：它体积大（一张约 200KB），存进历史会让表迅速膨胀。
     * 历史里回显用的是 [thumbs]。
     */
    val images: List<String> = emptyList(),

    /**
     * 图片的小图，**持久化**进 chat_message。
     *
     * 为什么不是二选一：模型要 1568 长边才看得清细节，而历史只需要
     * 「看得出是哪张图」。缩略图约 5KB，一百条带图消息也才 500KB，
     * 存得起；原图存不起（同规模 20MB）。界面优先用 thumbs 回显。
     */
    val thumbs: List<String> = emptyList(),
)

/** 默认模型：取插件实测可用的那个，列表拉不到时也能开聊。 */
const val DEFAULT_CODEBUDDY_MODEL = "hy4-preview"

/**
 * 对话页。
 *
 * ## 三件事：聊、选模型、翻历史
 *
 * 三者共用同一个页面而不是拆成三页，因为它们是同一件事的三个侧面 ——
 * 换模型要立刻作用在正在进行的对话上，翻历史要能接着聊。
 *
 * ## 落盘时机
 *
 * 只在**一轮问答结束后**写一次（用户消息 + 完整回复一起写）。流式过程中
 * 逐段落盘会让一次对话产生几十次写库，而中途崩溃丢掉半句话并无损失。
 *
 * ## 会话 id 的生命周期
 *
 * 首次发言前 sessionId = 0（还没有行）。第一条用户消息发出时创建会话行，
 * 随后每轮更新 updated_at —— 这样历史列表的排序 = 最近聊过的在最上面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    gateway: () -> LlmGateway?,
    catalog: ModelCatalog,
    repo: NoteRepository,
    onOpenSettings: () -> Unit,
) {
    // 所有会跨页面存活的状态都在 ViewModel 里 —— 这是「切页丢状态 / 回答被中断」
    // 两个 bug 的根治点，理由见 ChatViewModel 的类注释。
    val vm: ChatViewModel = viewModel()
    val context = LocalContext.current

    val store = vm.store
    val choices = vm.choices
    val pendingStore = vm.pendingStore

    val lines = vm.lines
    val input = vm.input
    val busy = vm.busy
    val notice = vm.notice
    // 这些东西仍由界面自己的 scope 管：它们都是「点一下就跑完」的短任务，
    // 不需要跨页面存活（选图/选文件的结果会立刻写进 ViewModel）。
    val scope = rememberCoroutineScope()

    // 会话 id / 待审集合 / 展开集合都在 ViewModel 里：它们要么被异步任务改写，
    // 要么需要在切页后仍与正在进行的请求保持一致。
    val sessionId = vm.sessionId
    val pendingCount = vm.pendingCount
    val pendingIds = vm.pendingIds

    // 已展开的工具行。用文本 hashCode 而非下标标识 —— 消息追加时下标会漂移。
    var expandedTools = vm.expandedTools

    fun refreshPending() = vm.refreshPending()

    fun replaceQueued(opId: Long, text: String) = vm.replaceQueued(opId, text)

    // 待发图片与文件也在 ViewModel 里 —— 这正是「选了照片切页就没了」的修复点。
    // 之前它们用 remember，而 remember 活不过一次 Tab 切换。
    val attached = vm.attached
    val attachedDocs = vm.attachedDocs

    /** 附件菜单是否展开。 */
    var showAttachMenu by remember { mutableStateOf(false) }

    /**
     * 拍照要写入的临时文件 Uri。
     *
     * 相机应用不返回图片数据，而是把照片写进你指定的 Uri —— 所以必须先
     * 创建一个临时文件并把它的 content:// 交给相机。用 FileProvider
     * （Android 7+ 传 file:// 会直接抛 FileUriExposedException）。
     */
    var pendingCaptureUri by remember { mutableStateOf<Uri?>(null) }

    /** 拍照。结果 Uri 就是刚才那个临时文件，与相册选图走同一条编码路径。 */
    val pickCamera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = pendingCaptureUri
        pendingCaptureUri = null
        if (uri == null) return@rememberLauncherForActivityResult
        if (!ok) {
            // 用户取消了拍照：把已经建出来的空临时文件删掉，不留垃圾。
            runCatching { context.contentResolver.delete(uri, null, null) }
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            vm.addImage(uri)
            // 已编码进内存，临时文件没用了。
            runCatching { context.contentResolver.delete(uri, null, null) }
        }
    }


    /** 文件选择器。OpenDocument 能按多个 mime 过滤，且拿到的是原始流。 */
    val pickDocs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            vm.notice = "正在读取文件…"
            vm.addDoc(uri, queryDisplayName(context, uri) ?: "文件", context.contentResolver.getType(uri))
        }
    }


    /** 系统选图器。用 GetContent 而不是 GetContentPreview：后者在部分机型上不给原始流。 */
    val pickImages = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val room = MAX_IMAGES_PER_SEND - vm.attached.size
            val take = uris.take(room)
            // 逐张走 ViewModel：解码与缩放都在它的 IO 上下文里做，
            // 而且加进去的图会落在 ViewModel 的列表里 —— 切页不会丢。
            take.forEach { vm.addImage(it) }
            if (uris.size > take.size) {
                vm.notice = "一次最多 " + MAX_IMAGES_PER_SEND + " 张，多余的已忽略。"
            }
        }
    }

    /** 当前模型（在 ViewModel 里，切页不丢）。 */
    val model = vm.model

    /** 模型清单（异步拉取，先给兜底表避免空菜单）。 */
    var models by remember { mutableStateOf(listOf(model)) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showPending by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ChatSessionSummary?>(null) }

    // 刻意不注册 BackHandler：本页是主窗口，按系统返回应交给系统处理
    // （退出 App），而不是被拦下来跳去别的页面 —— 之前它会把用户带到设置页。

    /** 载入一个历史会话。 */
    fun openSession(id: Long) = vm.openSession(id)

    /** 开一个全新会话。 */
    fun newSession() = vm.newSession()

    /**
     * 恢复上次的会话。
     *
     * ## 为什么需要它
     *
     * AppRoot 用 when(tab) 切主窗口，切走时本组件离开组合，**所有 remember
     * 状态被丢弃** —— sessionId 归零、lines 清空。所以用户聊到一半切去记笔记、
     * 再切回来，看到的会是一个空白对话页，之前的内容像被吞了。
     *
     * ## 为什么先校验会话还在
     *
     * 会话可能已被删（历史列表里删的，或上一次运行删的）。此时若照旧把
     * sessionId 设成那个 id，后面发消息会往一个不存在的会话里插行 ——
     * 外键约束下要么报错、要么留下一批永远查不到的孤儿消息。
     */
    fun restoreLastSession() {
        val savedId = choices.get(ChoiceStore.LAST_SESSION)?.toLongOrNull() ?: 0L
        if (savedId <= 0L) return
        if (store.listSessions().none { it.id == savedId }) {
            // 指向一个已不存在的会话：清掉标记，当新对话处理。
            choices.remove(ChoiceStore.LAST_SESSION)
            return
        }
        openSession(savedId)
    }

    // 进页面拉一次模型清单（有缓存则不发网络请求），并接上上次的会话。
    LaunchedEffect(Unit) {
        models = catalog.models()
        restoreLastSession()
    }

    /**
     * 发一轮。
     *
     * 实现整体搬进了 [ChatViewModel.send] —— 关键在于**协程现在跑在
     * viewModelScope 上**，切页不会再把它取消掉（那就是「切页回答中断报错」
     * 的根因）。这里只负责把界面持有的 gateway 传进去。
     */
    fun send() = vm.send(repo, gateway)
    /**
     * 中途停掉正在生成的回答。
     *
     * 之前没有这个按钮：切页会「意外地」停掉它，所以用户从没意识到
     * 「停」本该是一个显式动作。现在切页不再中断，就必须给一个正当出口 ——
     * 否则想停只能退出 App。
     */
    fun stop() = vm.stop()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("对话", style = MaterialTheme.typography.titleMedium)
                        // 模型名直接当副标题：不用点开就知道现在用哪个模型。
                        Text(
                            model,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { showModelSheet = true }
                        )
                    }
                },
                // 刻意没有「返回」按钮：对话是底部 Tab 切换的主窗口，
                // 「离开对话」的语义由底部按钮承担，顶栏再来一个会指向不明。
                actions = {
                    // 有待审项时把数量显示出来，否则用户不知道 agent 在等他点头。
                    TextButton(onClick = { showPending = true }) {
                        Text(if (pendingCount > 0) "待审 " + pendingCount else "待审")
                    }
                    TextButton(onClick = { showHistory = true }) { Text("历史") }
                    TextButton(onClick = { showModelSheet = true }) { Text("模型") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg)
        ) {
            if (lines.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "还没有对话。先在设置里登录 CodeBuddy，然后说点什么。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    items(lines.size) { i ->
                        val line = lines[i]
                        when (line.role) {
                            // 工具调用与待审：用弱化样式，别和真正的对话抢注意力。
                            "tool" -> {
                                // 长结果默认折叠，只留第一行（「调用 xxx {...}」）。
                                // 一次联网搜索的结果有几 KB，全展开会把下面的对话顶出屏幕；
                                // 而短结果（「已执行 kb_create_doc：…」）本来就只有一行，
                                // 折叠它没有意义，反而多一次点击。
                                val id = line.text.hashCode()
                                val long = line.text.length > TOOL_COLLAPSE_MAX
                                val open = !long || id in expandedTools
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .then(
                                            if (long) Modifier.clickable {
                                                expandedTools = if (open) expandedTools - id else expandedTools + id
                                            } else Modifier
                                        ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(Spacing.md)) {
                                        Text(
                                            text = if (open) line.text else firstLine(line.text),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        if (long) {
                                            Spacer(Modifier.height(Spacing.xs))
                                            Text(
                                                text = if (open) "收起" else "展开（共 " + line.text.length + " 字）",
                                                color = MaterialTheme.colorScheme.primary,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                            // 待审卡片。活没活着只认 pendingIds（即 pending_op 表），
                            // **不认 role**：切页重载会把早已审过的旧记录重新画成按钮，
                            // 点下去只能得到一句「这条已处理过了」。
                            "queued" -> {
                                val alive = line.opId > 0L && line.opId in pendingIds
                                val tint = if (alive) {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                }
                                val onTint = if (alive) {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = tint)
                                ) {
                                    Column(modifier = Modifier.padding(Spacing.md)) {
                                        Text(
                                            if (alive) "待你确认" else "已处理",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = onTint
                                        )
                                        Text(
                                            line.text,
                                            color = onTint,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (alive) {
                                            Spacer(Modifier.height(Spacing.sm))
                                            // 就地审批：不用跳到「待审」弹层，看到卡片就能决定。
                                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                                // 审批走 ViewModel：它才持有记忆存储
                                                // （kb_remember 的执行需要它），也才保证
                                                // 审批逻辑只有一份实现。
                                                Button(onClick = { vm.approveInline(i, line.opId, repo) }) {
                                                    Text("同意")
                                                }
                                                OutlinedButton(onClick = {
                                                    vm.rejectInline(i, line.opId, line.text)
                                                }) { Text("拒绝") }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                val mine = line.role == "user"
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (mine)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    // 用户自己打的字不解析 markdown（他写什么就显示什么）；
                                    // 模型的回复才渲染 —— 那是唯一会出现星号与反引号的地方。
                                    if (mine) {
                                        Column(modifier = Modifier.padding(Spacing.md)) {
                                            // 优先用缩略图回显。
                                            //
                                            // 两个来源不是冗余：[thumbs] 是持久化的（从库里读回来
                                            // 也有），[images] 只在这一轮的内存里。优先 thumbs 是为了
                                            // 「历史重载后仍看得到图」；本轮刚发完时两者都在，
                                            // 用 thumbs 显示也更省内存（原图那份要留给模型用）。
                                            val shown = line.thumbs.ifEmpty { line.images }
                                            shown.forEach { url ->
                                                AttachmentImage(
                                                    dataUrl = url,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .heightIn(max = 220.dp)
                                                        .padding(bottom = Spacing.sm)
                                                )
                                            }
                                            if (line.text.isNotEmpty()) {
                                                Text(
                                                    line.text,
                                                    color = MaterialTheme.colorScheme.onPrimary,
                                                    style = MaterialTheme.typography.bodyMedium
                                                )
                                            }
                                        }
                                    } else {
                                        MarkdownText(
                                            text = line.text.ifEmpty { "…" },
                                            modifier = Modifier.padding(Spacing.md),
                                            // 正文用 onSurface（近黑）。这里原本是
                                            // onSurfaceVariant —— 那是「时间戳、条数」这类
                                            // 次要文字的槽位；拿它渲染整段回复会让模型的话
                                            // 整段发灰，在浅底上对比度掉到勉强达标。
                                            color = MaterialTheme.colorScheme.onSurface,
                                            codeBackground = MaterialTheme.colorScheme.surface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            notice?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(Spacing.sm))
            }

            // 待发文件：只显示文件名与字数，不显示内容（内容可能几万字）。
            if (attachedDocs.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        attachedDocs.forEachIndexed { i, d ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "📄 " + d.name + "（" + d.text.length + " 字）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TextButton(onClick = { vm.removeDoc(i) }) { Text("移除") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
            }

            // 待发图片预览：让用户发之前能确认选了哪几张、能删掉选错的。
            if (attached.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    attached.forEachIndexed { i, att ->
                        Box {
                            AttachmentImage(
                                dataUrl = att.thumb,
                                modifier = Modifier.size(56.dp)
                            )
                            // 删除角标：选错一张不该逼用户全部重选。
                            Text(
                                "×",
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(20.dp)
                                    .clickable { vm.removeImage(i) },
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        attached.size.toString() + " 张",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.height(Spacing.sm))
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 附件入口收成一个「⋯」：图片、拍照、文件三件事是同一类操作
                // （给这一轮加素材），并排三个按钮会把输入框挤窄，而它们平时
                // 根本用不到。收进菜单后，输入框能占满剩下的宽度。
                Box {
                    TextButton(
                        onClick = { showAttachMenu = true },
                        enabled = !busy
                    ) { Text("⋯") }
                    DropdownMenu(
                        expanded = showAttachMenu,
                        onDismissRequest = { showAttachMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            enabled = attached.size < MAX_IMAGES_PER_SEND,
                            onClick = {
                                showAttachMenu = false
                                // 拍照不能直接用 GetContent —— 那只会打开相册。
                                // 必须用 TakePicture 并先申请一个临时文件 Uri：
                                // 相机应用会把照片写进那个 Uri 再回来。
                                pendingCaptureUri = createCaptureUri(context)
                                pendingCaptureUri?.let { pickCamera.launch(it) }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("从相册选图") },
                            enabled = attached.size < MAX_IMAGES_PER_SEND,
                            onClick = {
                                showAttachMenu = false
                                pickImages.launch("image/*")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("上传文件") },
                            enabled = attachedDocs.size < MAX_DOCS_PER_SEND,
                            onClick = {
                                showAttachMenu = false
                                pickDocs.launch(DOC_MIME_TYPES)
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { vm.onInputEdit(it) },
                    label = { Text("说点什么") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                // 生成中时这个按钮变成「停止」。
                //
                // 以前没有停止入口，是因为切页会「意外地」中断它 —— 用户从没意识到
                // 「停」该是个显式动作。现在切页不再中断，就必须给一个正当出口，
                // 否则想停只能退出 App。
                if (busy) {
                    OutlinedButton(onClick = { stop() }) { Text("停止") }
                } else {
                    Button(onClick = { send() }) { Text("发送") }
                }
            }
        }
    }

    // ---------- 模型选择 ----------
    if (showModelSheet) {
        ModalBottomSheet(onDismissRequest = { showModelSheet = false }) {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("选择模型", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        scope.launch {
                            models = catalog.models(force = true)
                        }
                    }) { Text("刷新") }
                }
                Spacer(Modifier.height(Spacing.sm))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(models.size) { i ->
                        val m = models[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    vm.selectModel(m)
                                    showModelSheet = false
                                }
                                .padding(vertical = Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                m,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (m == model)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurface
                            )
                            if (m == model) {
                                Spacer(Modifier.weight(1f))
                                Text("当前", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }

    // ---------- 待审写入 ----------
    if (showPending) {
        ModalBottomSheet(onDismissRequest = { showPending = false }) {
            // key 到 showPending：每次打开都重新读库，否则刚审完的项还挂在列表上。
            val ops = remember(showPending) { pendingStore.list() }
            Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("待审写入", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    if (ops.isNotEmpty()) {
                        TextButton(onClick = {
                            ops.forEach { pendingStore.remove(it.id) }
                            refreshPending()
                            showPending = false
                        }) { Text("全部拒绝") }
                    }
                }
                Spacer(Modifier.height(Spacing.sm))
                if (ops.isEmpty()) {
                    Text(
                        "没有待确认的操作。agent 想改知识库时会先放到这里。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.lg))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        items(ops.size) { i ->
                            val op = ops[i]
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(Spacing.md)) {
                                    Text(
                                        op.summary,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        op.tool,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(Spacing.sm))
                                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                        Button(onClick = {
                                            // 执行的是「当初那次调用」，不是当时的预览结果 ——
                                            // 所以 ViewModel 里重新解析 argsJson，库若已变也按最新执行。
                                            vm.approveFromSheet(op, repo)
                                            // 执行完关掉弹层：内容已变，列表上的其他项
                                            // 可能引用了刚被改动的文档，留着容易误点。
                                            showPending = false
                                        }) { Text("同意") }
                                        OutlinedButton(onClick = {
                                            vm.rejectFromSheet(op)
                                            showPending = false
                                        }) { Text("拒绝") }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }
    }

    // ---------- 历史会话 ----------
    if (showHistory) {
        ModalBottomSheet(onDismissRequest = { showHistory = false }) {
            val sessions = remember(showHistory) { store.listSessions() }
            Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("对话历史", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        newSession()
                        showHistory = false
                    }) { Text("新对话") }
                }
                Spacer(Modifier.height(Spacing.sm))
                if (sessions.isEmpty()) {
                    Text(
                        "还没有历史对话。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.lg))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(sessions.size) { i ->
                            val s = sessions[i]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        openSession(s.id)
                                        showHistory = false
                                    }
                                    .padding(vertical = Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        s.title.ifBlank { "未命名对话" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        formatTime(s.updatedAt) + " · " + s.messageCount + " 条 · " + s.model,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = { pendingDelete = s }) { Text("删除") }
                            }
                            HorizontalDivider()
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除对话") },
            text = { Text("「" + target.title.ifBlank { "未命名对话" } + "」及其 " + target.messageCount + " 条消息会被永久删除。") },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteSession(target.id)
                    // 删的正是当前会话就清屏，否则屏幕还留着已删的内容。
                    if (target.id == sessionId) newSession()
                    pendingDelete = null
                    showHistory = false
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))

/**
 * 把一次工具调用渲染成展示文本。
 *
 * 结果可能很长（kb_get_doc 会把整篇文档吐出来），截断到 [TOOL_RESULT_MAX] 字 ——
 * 聊天区不是读全文的地方，用户要全文可以点进文档看。
 */
internal fun toolLine(tool: String, argsJson: String, result: String): String {
    val head = "调用 " + tool + " " + argsJson
    val body = if (result.length <= TOOL_RESULT_MAX) result
    else result.substring(0, TOOL_RESULT_MAX) + "…（共 " + result.length + " 字）"
    return head + "\n" + body
}

/**
 * 工具结果在聊天区保留的最大字数。
 *
 * 从 400 放宽到 3000：400 是「怕它占屏」时代的产物，而折叠已经接管了那件事
 * —— 展开是用户的明确动作，那时他要的就是完整内容。一次 web_search 返回
 * 8 条结果约 900 字，400 会把后面 5 条直接吃掉，用户看不到却也不知道自己没看到。
 *
 * 仍然留一个上限：kb_get_doc 能把整篇文档吐出来（几万字），
 * 而聊天区不是读全文的地方 —— 那种情况用户该点进文档看。
 */
private const val TOOL_RESULT_MAX = 3000

/**
 * 取文本的第一行，并截到合理长度。
 *
 * 折叠态只显示「调用 web_search {"query":"…"}」这一行 —— 那是用户判断
 * 「这次调用了什么」所需要的全部信息；结果正文属于细节，展开才看。
 */
private fun firstLine(text: String): String {
    val head = text.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (head.length <= TOOL_HEAD_MAX) head else head.take(TOOL_HEAD_MAX) + "…"
}

/** 超过这个长度才值得折叠。短结果本来就只有一行，折叠它只会多一次点击。 */
private const val TOOL_COLLAPSE_MAX = 120

/** 折叠态标题最多显示多少字符。 */
/**
 * 已附加的文件。
 *
 * 只留抽取后的 text 与显示名：原文件不复制、不落库 —— 用户只是想
 * 「让 AI 读一下这份文件」，不是要把它存进知识库（那是附件面板的事）。
 */
private data class AttachedDoc(val name: String, val text: String, val note: String)

/**
 * 取文件的显示名。
 *
 * 用 OpenableColumns 查而不是 uri.lastPathSegment：后者拿到的是一串编码过的
 * 文档 id（形如 document/1234），显示给用户毫无意义。
 */
private fun queryDisplayName(context: android.content.Context, uri: android.net.Uri): String? =
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (t: Throwable) {
        null
    }

private const val TOOL_HEAD_MAX = 80


/**
 * 一次最多发几张图。
 *
 * 腾讯网关单请求图片视觉 token 上限约 100,000，缩到长边 1568 后单张约
 * 4,000 token —— 理论上能到 24 张。取 4 是产品判断而非技术限制：
 * 多图收益递减（「这几张图有什么共同点」很少见），而每张都要解码+编码，
 * 发 20 张会让用户等十几秒且看不到进度。
 */
private const val MAX_IMAGES_PER_SEND = 4

/**
 * 把 data URL 形态的图片显示出来。
 *
 * 不解码成 Bitmap 存进状态：那会让每张图占几 MB 内存，而对话里可能同时
 * 存在几十条带图消息。这里每次组合时现解，交给 Compose 的缓存管。
 */
@Composable
private fun AttachmentImage(dataUrl: String, modifier: Modifier = Modifier) {
    val bitmap = remember(dataUrl) {
        runCatching {
            val raw = android.util.Base64.decode(dataUrl.substringAfter(","), android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
        }.getOrNull()
    }
    if (bitmap == null) {
        // 解码失败就显示占位，不让整个列表崩掉。
        Text("（图片无法显示）", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = "已选图片",
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}

/** 两个换行。写成常量是因为嵌在 Kotlin 字符串里很难读。 */
private const val NEWLINE2 = "\n\n"

/**
 * 一次最多附几份文件。
 *
 * 每份最多 20000 字（DocText.MAX_CHARS），4 份就是 8 万字 —— 接近部分模型的
 * 上下文上限，且每字都要付费。再多不如让用户先自己摘一段。
 */
private const val MAX_DOCS_PER_SEND = 4

/**
 * 文件选择器接受的类型。
 *
 * 刻意不写「application 通吃」：那会把 exe、zip 也放进来，而它们注定解析
 * 失败 —— 与其让用户选完再被拒，不如一开始就选不到。
 */
private val DOC_MIME_TYPES = arrayOf(
    "text/plain",
    "text/markdown",
    "text/csv",
    "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
)

/**
 * 给拍照创建一个临时文件并返回它的 content Uri。
 *
 * ## 为什么要 FileProvider
 *
 * Android 7 起禁止把 file:// 交给别的应用（FileUriExposedException）：
 * 直接传文件路径会暴露本应用私有目录，也被视为不安全。必须走 FileProvider ——
 * 它把私有文件的访问权**临时授予**拿到 Uri 的那个应用（相机）。
 *
 * ## 失败返回 null
 *
 * 建不出临时文件（存储满、权限异常）时返回 null，由调用方忽略这次点击。
 * 不抛异常：拍不了照是小事，崩掉整个对话页是大事。
 */
private fun createCaptureUri(context: android.content.Context): android.net.Uri? =
    try {
        val dir = java.io.File(context.cacheDir, "capture").apply { mkdirs() }
        val file = java.io.File(dir, "shot-" + System.currentTimeMillis() + ".jpg")
        // authorities 写成包名 + .provider，与 AndroidManifest 里必须一致。
        androidx.core.content.FileProvider.getUriForFile(
            context,
            context.packageName + ".provider",
            file,
        )
    } catch (t: Throwable) {
        null
    }


