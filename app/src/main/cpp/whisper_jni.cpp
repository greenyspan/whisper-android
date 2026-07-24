#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <fstream>
#include <cstdint>
#include <android/log.h>
#include "whisper.h"

#define TAG "whisper-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// --- Simple WAV reader (16-bit PCM only) ---
static bool read_wav_f32(const std::string& path, std::vector<float>& samples) {
    std::ifstream file(path, std::ios::binary);
    if (!file) {
        LOGE("Cannot open file: %s", path.c_str());
        return false;
    }

    // Read WAV header (44 bytes standard)
    char header[44];
    file.read(header, 44);
    if (!file || file.gcount() < 44) {
        LOGE("Failed to read WAV header");
        return false;
    }

    // Verify RIFF/WAVE
    if (std::string(header, 4) != "RIFF" || std::string(header + 8, 4) != "WAVE") {
        LOGE("Not a valid WAV file");
        return false;
    }

    int16_t bitsPerSample = *(int16_t*)(header + 34);
    if (bitsPerSample != 16) {
        LOGE("Only 16-bit WAV supported, got %d bits", bitsPerSample);
        return false;
    }

    // Find data chunk
    file.seekg(36);
    while (file) {
        char chunkId[4];
        int32_t chunkSize;
        file.read(chunkId, 4);
        file.read((char*)&chunkSize, 4);
        if (!file) break;

        if (std::string(chunkId, 4) == "data") {
            int nSamples = chunkSize / 2;
            std::vector<int16_t> raw(nSamples);
            file.read((char*)raw.data(), chunkSize);

            samples.resize(nSamples);
            for (int i = 0; i < nSamples; i++) {
                samples[i] = raw[i] / 32768.0f;
            }
            LOGI("WAV loaded: %d samples", nSamples);
            return true;
        }
        file.seekg(chunkSize, std::ios::cur);
    }

    LOGE("No data chunk found in WAV");
    return false;
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_parkerxin_whisper_whisper_WhisperBridge_nativeInit(
    JNIEnv* env, jclass, jstring modelPath) {
    
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    
    env->ReleaseStringUTFChars(modelPath, path);
    
    if (ctx == nullptr) {
        LOGE("Failed to initialize whisper from: %s", path);
        return 0;
    }
    
    LOGI("Whisper context initialized successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_parkerxin_whisper_whisper_WhisperBridge_nativeFree(
    JNIEnv*, jclass, jlong ctxPtr) {
    
    if (ctxPtr != 0) {
        auto* ctx = reinterpret_cast<struct whisper_context*>(ctxPtr);
        whisper_free(ctx);
        LOGI("Whisper context freed");
    }
}

JNIEXPORT jstring JNICALL
Java_com_parkerxin_whisper_whisper_WhisperBridge_nativeTranscribe(
    JNIEnv* env, jclass,
    jlong ctxPtr,
    jstring audioPath,
    jstring language,
    jint nThreads) {
    
    if (ctxPtr == 0) {
        return env->NewStringUTF("[]");
    }
    
    auto* ctx = reinterpret_cast<struct whisper_context*>(ctxPtr);
    const char* audio = env->GetStringUTFChars(audioPath, nullptr);
    const char* lang = env->GetStringUTFChars(language, nullptr);
    
    // Read audio file into PCM float samples
    std::vector<float> pcmf32;
    if (!read_wav_f32(audio, pcmf32)) {
        env->ReleaseStringUTFChars(audioPath, audio);
        env->ReleaseStringUTFChars(language, lang);
        LOGE("Failed to read audio file");
        return env->NewStringUTF("[]");
    }
    
    // Full params
    struct whisper_full_params params = whisper_full_default_params(
        WHISPER_SAMPLING_GREEDY);
    
    params.n_threads = nThreads;
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.max_len = 0;
    params.language = (lang[0] == 'a' && lang[1] == 'u') ? nullptr : lang;
    
    LOGI("Starting transcription: audio=%s, lang=%s, threads=%d, samples=%zu",
         audio, lang, nThreads, pcmf32.size());
    
    int ret = whisper_full(ctx, params, pcmf32.data(), pcmf32.size());
    
    env->ReleaseStringUTFChars(audioPath, audio);
    env->ReleaseStringUTFChars(language, lang);
    
    if (ret != 0) {
        LOGE("Whisper transcription failed with code: %d", ret);
        return env->NewStringUTF("[]");
    }
    
    int n_segments = whisper_full_n_segments(ctx);
    LOGI("Transcription complete: %d segments", n_segments);
    
    // Get t0 for relative timing
    int64_t t0 = whisper_full_get_segment_t0(ctx, 0);
    
    // Build JSON array
    std::ostringstream json;
    json << "[";
    for (int i = 0; i < n_segments; i++) {
        if (i > 0) json << ",";
        auto seg = whisper_full_get_segment_text(ctx, i);
        int64_t start = whisper_full_get_segment_t0(ctx, i);
        int64_t end = whisper_full_get_segment_t1(ctx, i);
        
        std::string text(seg);
        json << "["
             << (start - t0) * 10 << ","
             << (end - t0) * 10 << ","
             << "\"";
        for (char c : text) {
            switch (c) {
                case '"': json << "\\\""; break;
                case '\\': json << "\\\\"; break;
                case '\n': json << "\\n"; break;
                case '\t': json << "\\t"; break;
                default: json << c;
            }
        }
        json << "\"]";
    }
    json << "]";
    
    std::string result = json.str();
    return env->NewStringUTF(result.c_str());
}

} // extern "C"
