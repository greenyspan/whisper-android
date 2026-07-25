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

enum class OutputFormat(val label: String, val extension: String, val mimeType: String) {
    TXT("纯文本", ".txt", "text/plain"),
    SRT("SRT 字幕", ".srt", "text/plain"),
    TIMELINE("带时间轴文本", ".txt", "text/plain"),
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
    val outputUri: Uri? = null,
    val outputFileName: String = "",
    val progress: Float = 0f,
    val statusMessage: String = "选择音频文件和保存位置后开始",
    val result: TranscribeResult? = null,
    val outputPath: String? = null,
    val errorMessage: String? = null,
    // Timing stats
    val transcribeStartMs: Long = 0,
    val transcribeEndMs: Long = 0,
    val audioDurationSec: Float = 0f,
)

class TranscribeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    fun selectFile(uri: Uri, fileName: String) {
        _uiState.value = _uiState.value.copy(selectedFile = uri, fileName = fileName, errorMessage = null)
    }

    fun selectOutput(uri: Uri, fileName: String) {
        _uiState.value = _uiState.value.copy(outputUri = uri, outputFileName = fileName, errorMessage = null)
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
        if (state.selectedFile == null || state.outputUri == null) return

        viewModelScope.launch {
            try {
                val modelKey = state.selectedModel

                // Step 1: Ensure model is downloaded
                if (!Models.isModelDownloaded(modelKey)) {
                    _uiState.value = _uiState.value.copy(
                        state = AppState.DOWNLOADING_MODEL,
                        statusMessage = "正在下载模型…",
                        progress = 0f,
                    )
                    val result = Models.downloadModel(modelKey) { p ->
                        _uiState.value = _uiState.value.copy(progress = p)
                    }
                    if (result.isFailure) {
                        val err = result.exceptionOrNull()?.message ?: "未知"
                        _uiState.value = _uiState.value.copy(
                            state = AppState.ERROR,
                            errorMessage = "模型下载失败: $err",
                        )
                        return@launch
                    }
                }

                // Step 2: Load model
                _uiState.value = _uiState.value.copy(
                    state = AppState.DOWNLOADING_MODEL,
                    statusMessage = "正在加载模型…",
                )
                val modelPath = Models.getModelPath(modelKey)
                withContext(Dispatchers.IO) {
                    WhisperBridge.loadModel(modelPath)
                }

                // Step 3: Decode audio
                _uiState.value = _uiState.value.copy(
                    state = AppState.DECODING,
                    statusMessage = "正在解码音频…",
                )
                val audioPath = withContext(Dispatchers.IO) {
                    prepareAudio(state.selectedFile!!)
                }

                // Step 4: Transcribe with progress
                val tStart = System.currentTimeMillis()
                _uiState.value = _uiState.value.copy(
                    state = AppState.TRANSCRIBING,
                    statusMessage = "正在转写…",
                    progress = 0f,
                    transcribeStartMs = tStart,
                )

                // Connect progress callback
                WhisperBridge.progressListener = { p ->
                    _uiState.value = _uiState.value.copy(progress = p / 100f)
                }

                val result = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(600_000L) {
                        WhisperBridge.transcribe(
                            audioPath = audioPath,
                            language = state.selectedLanguage,
                        )
                    }
                }
                WhisperBridge.progressListener = null

                if (result == null) {
                    _uiState.value = _uiState.value.copy(
                        state = AppState.ERROR,
                        errorMessage = "转写超时（10分钟）。请尝试更短的音频。",
                    )
                    return@launch
                }

                // Step 5: Save output
                val tEnd = System.currentTimeMillis()
                val audioSec = result.segments.lastOrNull()?.endMs?.div(1000f) ?: 0f

                _uiState.value = _uiState.value.copy(
                    statusMessage = "正在保存…",
                )
                saveOutput(state.outputUri!!, result, state.outputFormat)

                _uiState.value = _uiState.value.copy(
                    state = AppState.DONE,
                    statusMessage = "转写完成",
                    progress = 1f,
                    result = result,
                    outputPath = state.outputFileName,
                    transcribeEndMs = tEnd,
                    audioDurationSec = audioSec,
                )

            } catch (e: Exception) {
                Log.e("WhisperVM", "转写异常", e)
                _uiState.value = _uiState.value.copy(
                    state = AppState.ERROR,
                    errorMessage = e.message ?: "未知错误",
                )
            }
        }
    }

    fun reset() {
        WhisperBridge.release()
        _uiState.value = AppUiState()
    }

    private fun prepareAudio(uri: Uri): String {
        val context = WhisperApp.instance
        val wavPath = AudioDecoder.decodeToWav(context, uri)
        if (wavPath != null) return wavPath
        val cacheFile = File(context.cacheDir, "input_audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        return cacheFile.absolutePath
    }

    private fun saveOutput(uri: Uri, result: TranscribeResult, format: OutputFormat) {
        val text = when (format) {
            OutputFormat.TXT -> FormatConverter.toTxt(result.segments)
            OutputFormat.SRT -> FormatConverter.toSrt(result.segments)
            OutputFormat.TIMELINE -> FormatConverter.toTimeline(result.segments)
        }
        WhisperApp.instance.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    override fun onCleared() {
        super.onCleared()
        WhisperBridge.release()
    }
}
