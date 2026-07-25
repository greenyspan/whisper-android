package com.parkerxin.whisper.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import com.parkerxin.whisper.WhisperApp
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class ModelInfo(
    val key: String,
    val displayName: String,
    val size: String,
    val url: String,
    val fileName: String,
)

object Models {
    val all = listOf(
        ModelInfo(
            key = "tiny",
            displayName = "tiny（最快，~75MB）",
            size = "75MB",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin",
            fileName = "ggml-tiny.bin",
        ),
        ModelInfo(
            key = "base",
            displayName = "base（快，~140MB）",
            size = "140MB",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin",
            fileName = "ggml-base.bin",
        ),
        ModelInfo(
            key = "small",
            displayName = "small（平衡，~460MB，推荐）",
            size = "460MB",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin",
            fileName = "ggml-small.bin",
        ),
        ModelInfo(
            key = "medium",
            displayName = "medium（较准，~1.5GB）",
            size = "1.5GB",
            url = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin",
            fileName = "ggml-medium.bin",
        ),
    )

    private fun modelsDir(): File {
        // Save to public Downloads to survive app reinstalls
        val dir = File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS
            ), "WhisperModels"
        )
        dir.mkdirs()
        // Ensure readable by reinstalled app (different UID)
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
        return dir
    }

    fun getModelPath(key: String): String {
        val model = all.first { it.key == key }
        val f = File(modelsDir(), model.fileName)
        // Make existing file readable by current app
        f.setReadable(true, false)
        return f.absolutePath
    }

    fun isModelDownloaded(key: String): Boolean {
        val model = all.first { it.key == key }
        return File(modelsDir(), model.fileName).exists()
    }

    suspend fun downloadModel(
        key: String,
        onProgress: (Float) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val model = all.first { it.key == key }
            val outFile = File(modelsDir(), model.fileName)
            val tmpFile = File(modelsDir(), "${model.fileName}.tmp")

            if (outFile.exists()) {
                return@withContext Result.success(outFile.absolutePath)
            }

            tmpFile.parentFile?.mkdirs()

            val client = OkHttpClient.Builder()
                .followRedirects(true)
                .build()

            val request = Request.Builder()
                .url(model.url)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("下载失败: HTTP ${response.code}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(
                IOException("响应体为空")
            )

            val totalBytes = body.contentLength()
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tmpFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            onProgress(downloadedBytes.toFloat() / totalBytes)
                        }
                    }
                }
            }

            tmpFile.renameTo(outFile)
            outFile.setReadable(true, false) // world-readable for reinstall survival
            Result.success(outFile.absolutePath)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
