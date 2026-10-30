package com.taverndream.sd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 内置的 Stable Diffusion 后端（移植自 local-dream 的 BackendService）。
 * 以后台前台服务方式执行 native 可执行文件，并在 127.0.0.1:8081 提供 HTTP 接口。
 */

/** 生图后端状态。 */
sealed class SdState {
    data object Idle : SdState()
    data object Starting : SdState()
    data object Running : SdState()
    data class Error(val message: String) : SdState()
}

class SdBackendService : Service() {

    private var process: Process? = null
    private var launchedAsRoot = false
    private val lastLines = ArrayDeque<String>()
    private lateinit var runtimeDir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "SdBackend"
        private const val EXECUTABLE_NAME = "libstable_diffusion_core.so"
        private const val RUNTIME_DIR = "runtime_libs"
        private const val SU = "/system/bin/su"
        private const val PID_FILE = "sd_backend.pid"
        private const val LAUNCH_SCRIPT = "sd_launch.sh"
        private const val CHANNEL_ID = "sd_backend"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.taverndream.SD_START"
        const val ACTION_STOP = "com.taverndream.SD_STOP"
        const val EXTRA_MODEL_ID = "model_id"
        const val PORT = 8081


        private val _state = MutableStateFlow<SdState>(SdState.Idle)
        val state: StateFlow<SdState> = _state

        fun isRunning(): Boolean = _state.value == SdState.Running

        fun start(context: Context, modelId: String) {
            // 已启动或启动中则不重复拉起，避免端口占用
            if (_state.value == SdState.Starting || _state.value == SdState.Running) return
            val i = Intent(context, SdBackendService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODEL_ID, modelId)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, SdBackendService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        runtimeDir = File(filesDir, RUNTIME_DIR)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBackend()
            _state.value = SdState.Idle
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, notification("Stable Diffusion 后端启动中…"))

        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID) ?: "anythingv5"
        val model = SdModelManager.models(this).firstOrNull { it.id == modelId }
        if (model == null) {
            _state.value = SdState.Error("未找到模型 $modelId")
            stopSelf()
            return START_NOT_STICKY
        }

        _state.value = SdState.Starting
        Thread {
            try {
                prepareRuntime()
                if (startProcess(model)) {
                    waitHealthy(model)
                } else {
                    _state.value = SdState.Error("后端启动失败")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "start failed", t)
                _state.value = SdState.Error(t.message ?: "启动异常")
            }
        }.apply { isDaemon = true }.start()

        return START_NOT_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "本地生图", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Tavern Dream 生图引擎")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    /** 把 assets/qnnlibs 里的 QNN 运行库解压到 filesDir/runtime_libs。 */
    private fun prepareRuntime() {
        if (!runtimeDir.exists()) runtimeDir.mkdirs()
        val names = assets.list("qnnlibs") ?: emptyArray()
        for (name in names) {
            val target = File(runtimeDir, name)
            val needCopy = !target.exists() ||
                    runCatching { assets.open("qnnlibs/$name").use { it.available().toLong() } }
                        .getOrDefault(-1L) != target.length()
            if (needCopy) {
                assets.open("qnnlibs/$name").use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            }
            target.setReadable(true, true)
            target.setExecutable(true, true)
        }
        runtimeDir.setReadable(true, true)
        runtimeDir.setExecutable(true, true)
    }

    private fun startProcess(model: SdModel): Boolean {
        val nativeDir = applicationInfo.nativeLibraryDir
        val exe = File(nativeDir, EXECUTABLE_NAME)
        if (!exe.exists()) {
            _state.value = SdState.Error("缺少 $EXECUTABLE_NAME（请确认 jniLibs 已打入）")
            return false
        }
        val modelDir = SdModelManager.dir(this, model.id)

        val cmd = mutableListOf<String>()
        if (model.runOnCpu) {
            cmd += listOf(
                "--clip", File(modelDir, "clip.mnn").absolutePath,
                "--unet", File(modelDir, "unet.mnn").absolutePath,
                "--vae_decoder", File(modelDir, "vae_decoder.mnn").absolutePath,
                "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                "--port", PORT.toString(),
                "--text_embedding_size", "768",
                "--cpu",
            )
            if (File(modelDir, "vae_encoder.mnn").exists()) {
                cmd += listOf("--vae_encoder", File(modelDir, "vae_encoder.mnn").absolutePath)
            }
        } else {
            val clipName = if (model.useCpuClip) "clip.mnn" else "clip.bin"
            cmd += listOf(
                "--clip", File(modelDir, clipName).absolutePath,
                "--unet", File(modelDir, "unet.bin").absolutePath,
                "--vae_decoder", File(modelDir, "vae_decoder.bin").absolutePath,
                "--tokenizer", File(modelDir, "tokenizer.json").absolutePath,
                "--backend", File(runtimeDir, "libQnnHtp.so").absolutePath,
                "--system_library", File(runtimeDir, "libQnnSystem.so").absolutePath,
                "--port", PORT.toString(),
                "--text_embedding_size", model.textEmbeddingSize.toString(),
            )
            if (File(modelDir, "vae_encoder.bin").exists()) {
                cmd += listOf("--vae_encoder", File(modelDir, "vae_encoder.bin").absolutePath)
            }
            if (model.useVpred || File(modelDir, "V_PRED").exists()) cmd += "--use_v_pred"
            if (model.useCpuClip) cmd += "--use_cpu_clip"
            if (model.isSdxl) cmd += "--sdxl"
        }

        val env = mutableMapOf<String, String>()
        val paths = mutableListOf(
            runtimeDir.absolutePath, "/system/lib64", "/vendor/lib64", "/vendor/lib64/egl"
        )
        runCatching {
            val mali = File("/system/vendor/lib64/egl/libGLES_mali.so")
            if (mali.exists()) {
                val parts = mali.canonicalPath.split("/")
                val soc = parts.getOrNull(parts.size - 2)
                if (soc != null) {
                    listOf("/vendor/lib64/$soc", "/vendor/lib64/egl/$soc").forEach { if (!paths.contains(it)) paths.add(it) }
                }
            }
        }
        env["LD_LIBRARY_PATH"] = paths.joinToString(":")
        env["DSP_LIBRARY_PATH"] = runtimeDir.absolutePath
        env["ADSP_LIBRARY_PATH"] = runtimeDir.absolutePath

        Log.i(TAG, "CMD: ${exe.absolutePath} ${cmd.joinToString(" ")}")

        // QNN 的 unsigned PD 在部分 ROM 上只对 root 开放；能拿到 root 就用 root 起，拿不到就直接起
        launchedAsRoot = hasRoot()
        // 上次异常退出（崩溃/覆盖安装）可能留下后端进程占着端口，先清掉再起
        if (launchedAsRoot) killStaleBackends()
        Log.i(TAG, "launch as root = $launchedAsRoot")
        val pb = if (launchedAsRoot) {
            val script = writeLauncherScript(exe, cmd, env)
            ProcessBuilder(SU, "-c", "sh ${script.absolutePath}").apply {
                redirectErrorStream(true)
            }
        } else {
            ProcessBuilder(listOf(exe.absolutePath) + cmd).apply {
                directory(File(nativeDir))
                redirectErrorStream(true)
                environment().putAll(env)
            }
        }
        process = pb.start()
        monitor(process!!)
        return true
    }

    /** 清掉可能残留的后端进程，避免端口被旧进程占着、请求打到旧模型上。 */
    private fun killStaleBackends() {
        runCatching {
            ProcessBuilder(SU, "-c", "pkill -f $EXECUTABLE_NAME")
                .redirectErrorStream(true)
                .start()
                .waitFor(3, TimeUnit.SECONDS)
            Thread.sleep(300)
        }
        pidFile().delete()
    }

    /** 检查是否有可用的 root（KernelSU / Magisk 的 su）。 */
    private fun hasRoot(): Boolean = runCatching {
        if (!File(SU).exists()) return false
        val p = ProcessBuilder(SU, "-c", "id").redirectErrorStream(true).start()
        // 首次授权时 KernelSU 会弹窗，给足时间
        if (!p.waitFor(20, TimeUnit.SECONDS)) {
            runCatching { p.destroy() }
            return false
        }
        val out = p.inputStream.bufferedReader().readText()
        out.contains("uid=0")
    }.getOrDefault(false)

    /** root 启动时 su 只是父进程，脚本里记下真实后端 pid 方便关闭。 */
    private fun writeLauncherScript(exe: File, args: List<String>, env: Map<String, String>): File {
        val sb = StringBuilder("#!/system/bin/sh\n")
        for ((k, v) in env) sb.append("export ").append(k).append('=').append(quote(v)).append('\n')
        sb.append("echo $$ > ").append(quote(pidFile().absolutePath)).append('\n')
        sb.append("exec ").append(quote(exe.absolutePath))
        for (a in args) sb.append(' ').append(quote(a))
        sb.append('\n')
        val script = File(filesDir, LAUNCH_SCRIPT)
        script.writeText(sb.toString())
        script.setReadable(true, true)
        script.setExecutable(true, true)
        return script
    }

    private fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun pidFile(): File = File(filesDir, PID_FILE)

    private fun monitor(p: Process) {
        Thread {
            runCatching {
                p.inputStream.bufferedReader().use { r ->
                    while (true) {
                        val line = r.readLine() ?: break
                        Log.d(TAG, "native: $line")
                        synchronized(lastLines) {
                            lastLines.addLast(line)
                            while (lastLines.size > 60) lastLines.removeFirst()
                        }
                    }
                }
                val code = p.waitFor()
                Log.w(TAG, "exit code $code")
                if (_state.value !is SdState.Idle) _state.value = SdState.Error(exitReason(code))
            }.onFailure { Log.e(TAG, "monitor", it) }
        }.apply { isDaemon = true }.start()
    }

    /** 把 native 最后几行报错拼进提示，方便直接看出原因。 */
    private fun exitReason(code: Int): String {
        val detail = synchronized(lastLines) {
            val errs = lastLines.filter { it.contains("ERROR") || it.contains("error") }
            (errs.takeLast(2).ifEmpty { lastLines.takeLast(2) })
                .joinToString(" | ")
                .replace(Regex("\\s+"), " ")
                .take(300)
        }
        val hint = if (!launchedAsRoot && detail.contains("createUnsignedPD"))
            "（本机 ROM 禁止第三方应用使用 NPU，需要授予 root）" else ""
        return "后端已退出（code=$code）$hint ${detail}".trim()
    }

    private fun waitHealthy(model: SdModel) {
        val client = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).build()
        scope.launch {
            for (i in 0 until 240) {
                delay(500)
                val ok = runCatching {
                    client.newCall(Request.Builder().url("http://127.0.0.1:$PORT/health").build())
                        .execute().use { it.isSuccessful }
                }.getOrDefault(false)
                if (ok) {
                    // 端口可能被残留的旧后端响应，自己拉起的进程才是判据
                    if (process?.isAlive != true) {
                        _state.value = SdState.Error("端口被占用的旧后端响应，请重试启动")
                        return@launch
                    }
                    _state.value = SdState.Running
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIFICATION_ID, notification("${model.name} 已就绪"))
                    return@launch
                }
                if (_state.value is SdState.Error) return@launch
            }
            _state.value = SdState.Error("后端启动超时")
        }
    }

    private fun stopBackend() {
        // root 启动时 exec 出来的后端不是 su 的子进程语义上的同一进程，按 pid 文件单独杀
        runCatching {
            val pf = pidFile()
            if (pf.exists()) {
                val pid = pf.readText().trim().toIntOrNull()
                pf.delete()
                if (pid != null && pid > 0) {
                    if (File(SU).exists()) {
                        ProcessBuilder(SU, "-c", "kill $pid")
                            .start().waitFor(3, TimeUnit.SECONDS)
                    }
                }
            }
        }
        process?.let {
            runCatching { it.destroy() }
            runCatching { if (!it.waitFor(3, TimeUnit.SECONDS)) it.destroyForcibly() }
            process = null
        }
    }

    override fun onDestroy() {
        stopBackend()
        _state.value = SdState.Idle
        super.onDestroy()
    }
}
