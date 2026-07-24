# whisper-transcribe proguard rules
-keep class com.parkerxin.whisper.whisper.** { *; }
-keepclassmembers class com.parkerxin.whisper.whisper.WhisperBridge {
    native <methods>;
}
