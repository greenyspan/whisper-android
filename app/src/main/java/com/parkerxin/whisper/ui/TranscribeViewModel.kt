package com.parkerxin.whisper.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parkerxin.whisper.WhisperApp
import com.parkerxin.whisper.data.AudioDecoder
import com.parkerxin.whisper.data.FormatConverter
import com.parkerxin.whisper.data.Models
import com.parkerxin.whisper.whisper.TranscribeResult
import com.parkerxin.whisper.whisper.WhisperBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class OutputFormat(val label: String, val extension: String) {
    TXT("纯文本", ".txt"),
    SRT("SRT 字幕", ".srt"),
    TIMELINE("带时间轴文本", ".txt"),
}

enum class AppState {
    IDLE,
    DOWNLOADING_MODEL,
    DECODING,
    TRANSCRIBING,
    DONE,
    ERROR,
}

data class AppUiState(
    val state: AppState = AppState.IDLE,
    val selectedFile: Uri? = null,
    val fileName: String = "",
    val selectedModel: String = "small",
    val selectedLanguage: String = "auto",
    val outputFormat: OutputFormat = OutputFormat.TXT,
    val progress: Float = 0f,
    val statusMessage: String = "选择音频文件开始",
    val result: TranscribeResult? = null,
    val outputPath: String? = null,
    val errorMessage: String? = null,
)

class TranscribeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private val logFile = File(WhisperApp.instance.filesDir, "whisper_debug.log")

    private fun log(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "[$ts] $msg"
        Log.i("WhisperVM", line)
        try {
            logFile.appendText("$line\n")
        } catch (_: Exception) {}
    }

    fun selectFile(uri: Uri, fileName: String) {
        _uiState.value = _uiState.value.copy(selectedFile = uri, fileName = fileName, errorMessage = null)
    }

    fun selectModel(model: String) {
        _uiState.value = _uiState.value.copy(selectedModel = model)
    }

    fun selectLanguage(language: String) {
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    fun selectOutputFormat(format: OutputFormat) {
        _uiState.value = _uiState.value.copy(outputFormat = format)
    }

    fun startTranscribe() {
        val state = _uiState.value
        if (state.selectedFile == null) return

        // Clear old log
        try { logFile.writeText("") } catch (_: Exception) {}

        viewModelScope.launch {
            try {
                val modelKey = state.selectedModel
                log("=== 开始转写 ===")
                log("模型: $modelKey, 语言: ${state.selectedLanguage}, 格式: ${state.outputFormat}")
                log("文件: ${state.fileName}")

                // Step 1: Ensure model is downloaded
                if (!Models.isModelDownloaded(modelKey)) {
                    log("步骤1: 下载模型")
                    _uiState.value = _uiState.value.copy(
                        state = AppState.DOWNLOADING_MODEL,
                        statusMessage = "正在下载模型…",
                        progress = 0f,
                    )
                    val result = Models.downloadModel(modelKey) { progress ->
                        _uiState.value = _uiState.value.copy(progress = progress)
                    }
                    if (result.isFailure) {
                        val err = result.exceptionOrNull()?.message ?: "未知"
                        log("模型下载失败: $err")
                        _uiState.value = _uiState.value.copy(
                            state = AppState.ERROR,
                            errorMessage = "模型下载失败: $err",
                        )
                        return@launch
                    }
                    log("模型下载完成")
                } else {
                    log("模型已存在，跳过下载")
                }

                // Step 2: Load model
                log("步骤2: 加载模型")
                val modelPath = Models.getModelPath(modelKey)
                log("模型路径: $modelPath (存在=${File(modelPath).exists()}, 大小=${File(modelPath).length()})")
                withContext(Dispatchers.IO) {
                    WhisperBridge.loadModel(modelPath)
                }
                log("模型加载成功")

                // Step 3: Decode audio
                log("步骤3: 解码音频")
                _uiState.value = _uiState.value.copy(
                    state = AppState.DECODING,
                    statusMessage = "正在解码音频…",
                )
                val audioPath = withContext(Dispatchers.IO) {
                    prepareAudio(state.selectedFile!!)
                }
                log("音频就绪: $audioPath (存在=${File(audioPath).exists()}, 大小=${File(audioPath).length()})")

                // Step 4: Transcribe
                log("步骤4: 转写")
                _uiState.value = _uiState.value.copy(
                    state = AppState.TRANSCRIBING,
                    statusMessage = "正在转写…",
                )

                val result = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(300_000L) {  // 5 min timeout
                        WhisperBridge.transcribe(
                            audioPath = audioPath,
                            language = state.selectedLanguage,
                        )
                    }
                }

                if (result == null) {
                    log("转写超时（5分钟）")
                    _uiState.value = _uiState.value.copy(
                        state = AppState.ERROR,
                        errorMessage = "转写超时。请尝试更短的音频。\n调试日志: ${logFile.absolutePath}",
                    )
                    return@launch
                }

                log("转写完成: ${result.segments.size} 个片段")

                // Step 5: Save output
                val outputPath = saveOutput(result, state.outputFormat)
                log("输出已保存: $outputPath")

                _uiState.value = _uiState.value.copy(
                    state = AppState.DONE,
                    statusMessage = "转写完成",
                    result = result,
                    outputPath = outputPath,
                )

            } catch (e: Exception) {
                log("异常: ${e.javaClass.simpleName}: ${e.message}")
                Log.e("WhisperVM", "转写异常", e)
                _uiState.value = _uiState.value.copy(
                    state = AppState.ERROR,
                    errorMessage = "${e.message}\n\n调试日志: ${logFile.absolutePath}",
                )
            }
        }
    }

    fun reset() {
        log("重置")
        WhisperBridge.release()
        _uiState.value = AppUiState()
    }

    private fun prepareAudio(uri: Uri): String {
        val context = WhisperApp.instance
        log("  开始解码: $uri")
        val t0 = System.currentTimeMillis()
        val wavPath = AudioDecoder.decodeToWav(context, uri)
        val elapsed = System.currentTimeMillis() - t0
        if (wavPath != null) {
            log("  解码成功 (${elapsed}ms): $wavPath")
            return wavPath
        }
        log("  解码失败，尝试直接复制 (${elapsed}ms)")
        val cacheFile = File(context.cacheDir, "input_audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        log("  复制完成: ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
        return cacheFile.absolutePath
    }

    private fun saveOutput(result: TranscribeResult, format: OutputFormat): String {
        val context = WhisperApp.instance
        val text = when (format) {
            OutputFormat.TXT -> FormatConverter.toTxt(result.segments)
            OutputFormat.SRT -> FormatConverter.toSrt(result.segments)
            OutputFormat.TIMELINE -> FormatConverter.toTimeline(result.segments)
        }
        val fileName = "transcribe_${System.currentTimeMillis()}${format.extension}"
        val outFile = File(context.filesDir, fileName)
        outFile.writeText(text)
        return outFile.absolutePath
    }

    override fun onCleared() {
        super.onCleared()
        WhisperBridge.release()
    }
}
