package com.taverndream.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taverndream.AppViewModel
import com.taverndream.data.Settings
import com.taverndream.sd.SdBackendService
import com.taverndream.sd.SdState
import com.taverndream.sd.SdModelManager
import com.taverndream.sd.UpscalerManager
import com.taverndream.llm.LlmModelManager
import com.taverndream.llm.LocalLlmEngine

/**
 * 设置页（二级页面）。
 * 所有编辑先落本地状态，点右上角「保存」才写回 SharedPreferences。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val s = vm.settings
    val ctx = LocalContext.current

    var baseUrl by remember { mutableStateOf(s.baseUrl) }
    var apiKey by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.model) }
    var userName by remember { mutableStateOf(s.userName) }
    var mirror by remember { mutableStateOf(s.hfMirror) }
    var sdModel by remember { mutableStateOf(s.sdModelId) }
    var temperature by remember { mutableStateOf(s.temperature) }
    var sdSteps by remember { mutableStateOf(s.sdSteps) }
    var backend by remember { mutableStateOf(s.chatBackend) }
    var localModel by remember { mutableStateOf(s.localModelId) }
    var threads by remember { mutableStateOf(s.llmThreads) }
    var autoImage by remember { mutableStateOf(s.autoImage) }
    var negative by remember { mutableStateOf(s.sdNegative) }
    var ratio by remember { mutableStateOf(s.sdRatio) }
    var upscale by remember { mutableStateOf(s.sdUpscale) }
    var lockAppearance by remember { mutableStateOf(s.lockAppearance) }
    var fixedSeed by remember { mutableStateOf(s.fixedSeed) }
    var styleTags by remember { mutableStateOf(s.styleTags) }
    var themeMode by remember { mutableStateOf(s.themeMode) }
    var disableThinking by remember { mutableStateOf(s.disableThinking) }

    fun save() {
        s.baseUrl = baseUrl
        s.apiKey = apiKey
        s.model = model
        s.userName = userName
        s.hfMirror = mirror
        s.sdModelId = sdModel
        s.temperature = temperature
        s.sdSteps = sdSteps
        s.chatBackend = backend
        s.localModelId = localModel
        s.llmThreads = threads
        s.autoImage = autoImage
        s.sdNegative = negative
        s.sdRatio = ratio
        s.sdUpscale = upscale
        s.lockAppearance = lockAppearance
        s.fixedSeed = fixedSeed
        s.styleTags = styleTags
        s.disableThinking = disableThinking
        if (s.themeMode != themeMode) vm.applyTheme(themeMode)
        vm.toast = "设置已保存"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        save()
                        onBack()
                    }) { Text("保存") }
                },
            )
        },
    ) { p ->
        SelectionContainer {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(p)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            SectionHeader("外观")
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("主题", fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("system" to "跟随系统", "light" to "浅色", "dark" to "深色").forEach { (k, label) ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .selectable(
                                        selected = themeMode == k,
                                        onClick = { themeMode = k; vm.applyTheme(k) },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = themeMode == k, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            SectionHeader("对话")
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("对话引擎", fontWeight = FontWeight.Bold)
                    EngineRow(
                        title = "外部 API",
                        subtitle = "联网 + API Key，回复质量最好",
                        selected = backend == "api",
                    ) { backend = "api" }
                    EngineRow(
                        title = "本地模型（离线）",
                        subtitle = "MNN + Qwen3，断网也能聊，速度较慢",
                        selected = backend == "local",
                    ) { backend = "local" }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("对话模型（OpenAI 兼容）", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base URL") },
                        supportingText = { Text("例：https://api.openai.com/v1") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("模型名") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = userName,
                        onValueChange = { userName = it },
                        label = { Text("你的名字（{{user}}）") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("温度 ${"%.2f".format(temperature)}", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = temperature,
                        onValueChange = { temperature = it },
                        valueRange = 0f..1.5f,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("关闭思考模式", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Qwen3 这类模型会先「思考」再回答，关掉实测快 3~4 倍；若接口报 400 就关掉这个开关",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = disableThinking, onCheckedChange = { disableThinking = it })
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地对话模型（MNN）", fontWeight = FontWeight.Bold)
                    Text(
                        "模型常驻内存，占用约等于模型体积；生图前会自动卸载，下次对话重新加载",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    LlmModelManager.models.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = localModel == m.id, onClick = { localModel = m.id }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = localModel == m.id,
                                onClick = { localModel = m.id },
                            )
                            Column {
                                Text("${m.name} · ${m.approxSize}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${m.note} · ${if (LlmModelManager.isDownloaded(ctx, m)) "已下载" else "未下载"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    Text("推理线程 $threads", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = threads.toFloat(),
                        onValueChange = { threads = it.toInt() },
                        valueRange = 2f..8f,
                        steps = 5,
                    )

                    when (val st = vm.llmModelState) {
                        is LlmModelManager.State.Downloading -> {
                            if (st.total > 0) {
                                LinearProgressIndicator(
                                    progress = { st.bytes.toFloat() / st.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${st.file}  ${st.bytes / 1048576}MB / ${st.total / 1048576}MB",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    "${st.file}  ${st.bytes / 1048576}MB",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        is LlmModelManager.State.Failed ->
                            Text("失败：${st.message}", color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }

                    val llmDef = LlmModelManager.models.firstOrNull { it.id == localModel }
                    val llmReady = llmDef != null && LlmModelManager.isDownloaded(ctx, llmDef)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadLlmModel(localModel) }, enabled = !llmReady) {
                            Text(if (llmReady) "模型已就绪" else "下载模型")
                        }
                        OutlinedButton(onClick = { vm.unloadLocalLlm() }) {
                            Text("卸载")
                        }
                    }

                    Text(
                        when (val st = vm.llmState) {
                            is LocalLlmEngine.State.Ready -> "状态：已加载"
                            is LocalLlmEngine.State.Loading -> "状态：加载中（首次约 3~8 秒）"
                            is LocalLlmEngine.State.Failed -> "状态：${st.message}"
                            else -> "状态：未加载（首次对话时自动加载）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            SectionHeader("生图")
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("对话中自动配图", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("开启自动配图", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "你说「画一张…」或模型觉得画面值得画时，自动出图插进对话",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoImage, onCheckedChange = { autoImage = it })
                    }
                    OutlinedTextField(
                        value = negative,
                        onValueChange = { negative = it },
                        label = { Text("生图负面提示词") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("生图步数 $sdSteps（越大越细，越慢）", style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = sdSteps.toFloat(),
                        onValueChange = { sdSteps = it.toInt() },
                        valueRange = 8f..40f,
                        steps = 31,
                    )
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("生图输出", fontWeight = FontWeight.Bold)
                    Text(
                        "模型固定出 512×512，比例靠居中裁切实现",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("1:1" to "方图", "4:3" to "横图", "3:4" to "竖图").forEach { (k, label) ->
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .selectable(selected = ratio == k, onClick = { ratio = k }),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = ratio == k, onClick = null)
                                Text(label, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("4x 超分", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "出图后重建到长边 2048，多花约 0.7 秒",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = upscale, onCheckedChange = { upscale = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("锁定角色外观", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "每次出图都带上同一组外貌 tag，人物长相更接近",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = lockAppearance, onCheckedChange = { lockAppearance = it })
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("固定随机种子", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "同一角色用同一颗种子，脸更稳，但构图变化变少",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = fixedSeed, onCheckedChange = { fixedSeed = it })
                    }
                    OutlinedTextField(
                        value = styleTags,
                        onValueChange = { styleTags = it },
                        label = { Text("画风提示词（英文）") },
                        supportingText = { Text("每次出图都会带上，用来保持画风一致；留空则不追加") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val upReady = UpscalerManager.isDownloaded(ctx)
                    when (val st = vm.upscalerState) {
                        is UpscalerManager.State.Downloading -> {
                            LinearProgressIndicator(
                                progress = { st.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(st.progress * 100).toInt()}%  ${st.bytes / 1048576}MB / ${st.total / 1048576}MB",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        is UpscalerManager.State.Failed -> {
                            Text("超分模型下载失败：${st.message}", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { vm.downloadUpscaler() }) {
                                Text("重新下载")
                            }
                        }
                        else -> if (upReady) {
                            Text(
                                "超分模型已就绪",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Text(
                                "超分模型未下载（约 8MB）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = { vm.downloadUpscaler() }) {
                                Text("下载超分模型")
                            }
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("本地生图（内置 local-dream）", fontWeight = FontWeight.Bold)
                    Text(
                        "设备：${SdModelManager.soc()}  模型后缀：${SdModelManager.suffix() ?: "不支持 NPU"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    SdModelManager.models(vm.getApplication()).forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = sdModel == m.id, onClick = { sdModel = m.id }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = sdModel == m.id,
                                onClick = { sdModel = m.id },
                            )
                            Column {
                                Text(m.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "${m.approxSize} · ${if (SdModelManager.isDownloaded(vm.getApplication(), m)) "已下载" else "未下载"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = mirror,
                        onValueChange = { mirror = it },
                        label = { Text("模型下载源") },
                        supportingText = { Text("默认 aifasthub.com；hf-mirror.com 可用时也能填，海外可改回 https://huggingface.co/") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    val modelDef = SdModelManager.models(vm.getApplication()).firstOrNull { it.id == sdModel }
                    val downloaded = modelDef != null && SdModelManager.isDownloaded(vm.getApplication(), modelDef)

                    when (val st = vm.sdModelState) {
                        is SdModelManager.State.Downloading -> {
                            LinearProgressIndicator(
                                progress = { st.progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(st.progress * 100).toInt()}%  ${st.bytes / 1048576}MB / ${st.total / 1048576}MB",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        is SdModelManager.State.Extracting ->
                            Text("解压模型中…", style = MaterialTheme.typography.labelSmall)
                        is SdModelManager.State.Failed ->
                            Text("失败：${st.message}", color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.downloadSdModel(sdModel) }, enabled = !downloaded) {
                            Text(if (downloaded) "模型已就绪" else "下载模型")
                        }
                        OutlinedButton(onClick = {
                            if (vm.sdState is SdState.Running) vm.stopSdBackend()
                            else vm.startSdBackend(sdModel)
                        }) {
                            Text(if (vm.sdState is SdState.Running) "停止引擎" else "启动引擎")
                        }
                    }

                    Text(
                        when (val st = vm.sdState) {
                            is SdState.Running -> "引擎状态：运行中"
                            is SdState.Starting -> "引擎状态：启动中…"
                            is SdState.Error -> "引擎状态：${st.message}"
                            else -> "引擎状态：未启动"
                        },
                        style = MaterialTheme.typography.labelSmall,
                    )

                    Text(
                        "模型固定出 512×512，按「生图输出」比例裁切后超分",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }

            Text(
                "自用构建 · 生图内核来自 local-dream (CC BY-NC 4.0)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
        }
    }
}

/** 设置页的分组标题。 */
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun EngineRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
