package com.taverndream.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.taverndream.AppViewModel
import com.taverndream.data.CharacterCard
import com.taverndream.data.ChatMessage
import com.taverndream.sd.SdState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, card: CharacterCard, snackbar: SnackbarHostState) {
    var input by remember { mutableStateOf("") }
    var showImageDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var introExpanded by remember { mutableStateOf(true) }
    var showIntroDialog by remember { mutableStateOf(false) }
    var zoomImage by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()

    val visualCount = vm.messages.size + if (vm.streamingText.isNotEmpty()) 1 else 0

    // 只有用户已经在底部时才自动跟随；用户往上翻就不抢他的位置
    val atBottom = remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            atBottom.value = last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(vm.messages.size) {
        if (atBottom.value && visualCount > 0) {
            runCatching { listState.animateScrollToItem(visualCount - 1) }
        }
    }
    LaunchedEffect(vm.streamingText.length) {
        if (atBottom.value && visualCount > 0) {
            runCatching { listState.scrollToItem(visualCount - 1) }
        }
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(card.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { vm.closeChat() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "菜单")
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("手动生图") },
                                onClick = {
                                    menuOpen = false
                                    showImageDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("查看开局设定") },
                                onClick = {
                                    menuOpen = false
                                    showIntroDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("重新整理设定与外观") },
                                onClick = {
                                    menuOpen = false
                                    vm.regenerateMeta()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("清空聊天") },
                                onClick = {
                                    menuOpen = false
                                    showClearDialog = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Column {
                    // 生图在后台跑，进度只放在输入栏上方，不占聊天内容
                    if (vm.imageBusy) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 14.dp, end = 14.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                            Text(
                                "  后台画图中 ${vm.imageProgress}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("说点什么…") },
                        maxLines = 5,
                    )
                    IconButton(
                        onClick = { vm.generateSceneImage() },
                        enabled = !vm.imageBusy && !vm.busy,
                    ) {
                        Text(
                            "🎨",
                            fontSize = 20.sp,
                            modifier = Modifier.padding(end = 2.dp),
                        )
                    }
                    IconButton(
                        onClick = {
                            val t = input
                            input = ""
                            vm.send(t)
                        },
                        enabled = !vm.busy && input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                    }
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (vm.metaLoading || !vm.characterMeta?.intro.isNullOrBlank()) {
                item {
                    IntroCard(
                        text = vm.characterMeta?.intro.orEmpty(),
                        loading = vm.metaLoading,
                        expanded = introExpanded,
                        onToggle = { introExpanded = !introExpanded },
                    )
                }
            }
            itemsIndexed(vm.messages) { _, msg ->
                MessageBubble(
                    msg = msg,
                    onImageClick = { p -> zoomImage = p },
                    onCopy = { text ->
                        clipboard.setText(AnnotatedString(text))
                        vm.toast = "已复制"
                    },
                )
            }
            if (vm.streamingText.isNotEmpty()) {
                item {
                    MessageBubble(
                        msg = ChatMessage("assistant", vm.streamingText),
                        onImageClick = { },
                        onCopy = { text ->
                            clipboard.setText(AnnotatedString(text))
                            vm.toast = "已复制"
                        },
                    )
                }
            }
            if (vm.busy && vm.streamingText.isEmpty()) {
                item {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        val loadingLocal = vm.settings.chatBackend == "local" &&
                            vm.llmState is com.taverndream.llm.LocalLlmEngine.State.Loading
                        Text(
                            if (loadingLocal) " 正在加载本地模型（首次约 3~8 秒）…" else " 正在思考…",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空聊天") },
            text = { Text("会删除与「${card.name}」的全部消息，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearChat()
                    showClearDialog = false
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    if (showIntroDialog) {
        val intro = vm.characterMeta?.intro.orEmpty()
        AlertDialog(
            onDismissRequest = { showIntroDialog = false },
            title = { Text("开局设定 · 你是谁、现在在哪") },
            text = {
                if (intro.isBlank()) {
                    Text("还没有设定。可以在菜单里点「重新整理设定与外观」生成。")
                } else {
                    SelectionContainer { Text(intro, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIntroDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showImageDialog) {
        ImageDialog(vm = vm, card = card, onDismiss = { showImageDialog = false })
    }

    zoomImage?.let { path ->
        ImageZoomDialog(path = path, onDismiss = { zoomImage = null })
    }
}

/** 开局设定卡：世界观 / 你扮演谁 / 当前场景，点一下折叠。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IntroCard(text: String, loading: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .combinedClickable(onClick = onToggle, onLongClick = onToggle)
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (expanded) "▾ " else "▸ ",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    "开局设定 · 你是谁、现在在哪",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                if (loading) {
                    Spacer(Modifier.size(8.dp))
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text.ifBlank { "正在整理世界观和你的身份…" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    onImageClick: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    val isUser = msg.role == "user"
    val hasImage = !msg.imagePath.isNullOrBlank() && File(msg.imagePath).exists()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .combinedClickable(
                    onClick = { if (hasImage) onImageClick(msg.imagePath!!) },
                    onLongClick = {
                        if (msg.text.isNotBlank()) onCopy(msg.text)
                        else if (hasImage) onImageClick(msg.imagePath!!)
                    },
                )
                .padding(12.dp)
        ) {
            if (hasImage) {
                AsyncImage(
                    model = File(msg.imagePath!!),
                    contentDescription = "点击查看大图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // 系统原生长按选择：可以整段复制，也可以只选一部分
                SelectionContainer {
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 全屏看图：双指缩放、拖动平移，点背景或右上角关闭。 */
@Composable
private fun ImageZoomDialog(path: String, onDismiss: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = File(path),
                contentDescription = "大图",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y,
                    )
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset += pan
                        }
                    },
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Color.White)
            }
            Text(
                "双指缩放 · 拖动平移 · 点空白关闭",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp),
            )
        }
    }
}

@Composable
private fun ImageDialog(vm: AppViewModel, card: CharacterCard, onDismiss: () -> Unit) {
    val defaultPrompt = remember(card.id) {
        val desc = card.description.replace("\n", " ").take(120).trim()
        if (desc.isBlank()) "masterpiece, best quality, 1girl, solo,"
        else "$desc, masterpiece, best quality,"
    }
    var prompt by remember { mutableStateOf(defaultPrompt) }
    var negative by remember { mutableStateOf(vm.settings.sdNegative) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本地生图") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when (val s = vm.sdState) {
                        is SdState.Running -> "引擎已就绪"
                        is SdState.Starting -> "引擎启动中…"
                        is SdState.Error -> "引擎异常：${s.message}"
                        else -> "引擎未启动（生成时会自动启动）"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("提示词（英文效果更好）") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("负面提示词") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (vm.imageBusy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(vm.imageProgress, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { vm.generateImage(prompt, negative) },
                enabled = !vm.imageBusy,
            ) { Text(if (vm.imageBusy) "生成中" else "生成") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}
