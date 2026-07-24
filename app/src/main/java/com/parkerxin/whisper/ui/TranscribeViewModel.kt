package com.parkerxin.whisper.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

enum class OutputFormat(val label: String, val extension: String) {
    TXT("纯文本", ".txt"),
    SRT("SRT 字幕", ".srt"),
    TIMELINE("带时间轴文本", ".txt"),
}

enum class AppState {
    IDLE,
    DOWNLOADING_MODEL,
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

    fun selectFile(uri: Uri, fileName: String) {
        _uiState.value = _uiState.value.copy(
            selectedFile = uri,
            fileName = fileName,
            errorMessage = null,
        )
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

                    val result = Models.downloadModel(modelKey) { progress ->
                        _uiState.value = _uiState.value.copy(progress = progress)
                    }

                    if (result.isFailure) {
                        _uiState.value = _uiState.value.copy(
                            state = AppState.ERROR,
                            errorMessage = "模型下载失败: ${result.exceptionOrNull()?.message}",
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

                // Step 3: Transcribe
                _uiState.value = _uiState.value.copy(
                    state = AppState.TRANSCRIBING,
                    statusMessage = "正在转写…",
                )

                val audioPath = copyAudioToCache(state.selectedFile!!)

                val result = withContext(Dispatchers.IO) {
                    WhisperBridge.transcribe(
                        audioPath = audioPath,
                        language = state.selectedLanguage,
                    )
                }

                // Step 4: Save output
                val outputPath = saveOutput(result, state.outputFormat)

                _uiState.value = _uiState.value.copy(
                    state = AppState.DONE,
                    statusMessage = "转写完成",
                    result = result,
                    outputPath = outputPath,
                )

            } catch (e: Exception) {
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

    private suspend fun copyAudioToCache(uri: Uri): String = withContext(Dispatchers.IO) {
        val context = com.parkerxin.whisper.WhisperApp.instance
        val cacheFile = java.io.File(context.cacheDir, "input_audio")
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        cacheFile.absolutePath
    }

    private fun saveOutput(result: TranscribeResult, format: OutputFormat): String {
        val context = com.parkerxin.whisper.WhisperApp.instance
        val text = when (format) {
            OutputFormat.TXT -> FormatConverter.toTxt(result.segments)
            OutputFormat.SRT -> FormatConverter.toSrt(result.segments)
            OutputFormat.TIMELINE -> FormatConverter.toTimeline(result.segments)
        }

        val fileName = "transcribe_${System.currentTimeMillis()}${format.extension}"
        val outFile = java.io.File(context.filesDir, fileName)
        outFile.writeText(text)
        return outFile.absolutePath
    }

    override fun onCleared() {
        super.onCleared()
        WhisperBridge.release()
    }
}
