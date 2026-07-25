package com.parkerxin.whisper.whisper

import androidx.annotation.Keep

data class Segment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

data class TranscribeResult(
    val segments: List<Segment>,
    val fullText: String,
)

object WhisperBridge {

    private var ctxPtr: Long = 0
    private var isLoaded = false

    /** Set by ViewModel; called from JNI thread when progress updates (0-100) */
    var progressListener: ((Int) -> Unit)? = null

    init {
        try {
            System.loadLibrary("whisper-jni")
        } catch (e: UnsatisfiedLinkError) {
            // Library not yet compiled
        }
    }

    // --- Native methods ---

    private external fun nativeInit(modelPath: String, bridgeObj: WhisperBridge): Long
    private external fun nativeFree(ctx: Long)
    private external fun nativeTranscribe(
        ctx: Long,
        audioPath: String,
        language: String,
        nThreads: Int,
    ): String

    // --- Called from JNI (progress callback) ---
    @Keep
    fun onProgress(progress: Int) {
        progressListener?.invoke(progress)
    }

    // --- Public API ---

    fun loadModel(modelPath: String) {
        if (isLoaded) {
            nativeFree(ctxPtr)
            isLoaded = false
        }
        ctxPtr = nativeInit(modelPath, this)
        if (ctxPtr == 0L) {
            throw RuntimeException("无法加载模型: $modelPath")
        }
        isLoaded = true
    }

    fun isModelLoaded(): Boolean = isLoaded

    fun transcribe(
        audioPath: String,
        language: String = "auto",
        nThreads: Int = minOf(Runtime.getRuntime().availableProcessors(), 3),
    ): TranscribeResult {
        if (!isLoaded) {
            throw IllegalStateException("模型未加载")
        }

        android.util.Log.i("WhisperBridge", "开始转写: path=$audioPath, lang=$language, threads=$nThreads")
        val json = nativeTranscribe(ctxPtr, audioPath, language, nThreads)
        android.util.Log.i("WhisperBridge", "转写完成, JSON 长度: ${json.length}")
        return parseJsonResult(json)
    }

    fun release() {
        progressListener = null
        if (isLoaded) {
            nativeFree(ctxPtr)
            ctxPtr = 0
            isLoaded = false
        }
    }

    // --- JSON parsing ---

    private fun parseJsonResult(json: String): TranscribeResult {
        val segments = mutableListOf<Segment>()
        val fullText = StringBuilder()

        var i = 0
        while (i < json.length) {
            if (json[i] == '[') {
                i++
                val (startMs, ni1) = parseLong(json, i); i = ni1
                i = skipComma(json, i)
                val (endMs, ni2) = parseLong(json, i); i = ni2
                i = skipComma(json, i)
                val (text, ni3) = parseString(json, i); i = ni3
                i = skipToNext(json, i)

                if (text.isNotBlank()) {
                    segments.add(Segment(startMs, endMs, text))
                    fullText.append(text)
                }
            } else {
                i++
            }
        }

        return TranscribeResult(segments, fullText.toString())
    }

    private fun parseLong(s: String, start: Int): Pair<Long, Int> {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        var negative = false
        if (i < s.length && s[i] == '-') { negative = true; i++ }
        var value = 0L
        while (i < s.length && s[i].isDigit()) {
            value = value * 10 + (s[i] - '0')
            i++
        }
        return (if (negative) -value else value) to i
    }

    private fun parseString(s: String, start: Int): Pair<String, Int> {
        var i = start
        while (i < s.length && s[i] != '"') i++
        if (i >= s.length) return "" to i
        i++
        val sb = StringBuilder()
        while (i < s.length && s[i] != '"') {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                }
                i += 2
            } else {
                sb.append(s[i])
                i++
            }
        }
        i++
        return sb.toString() to i
    }

    private fun skipComma(s: String, start: Int): Int {
        var i = start
        while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++
        return i
    }

    private fun skipToNext(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i] != ']') i++
        return i + 1
    }
}
