package com.parkerxin.whisper.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioDecoder {

    private const val TAG = "AudioDecoder"
    private const val TARGET_SAMPLE_RATE = 16000

    fun decodeToWav(context: android.content.Context, uri: Uri): String? {
        val wavFile = File(context.cacheDir, "decoded_audio.wav")
        if (wavFile.exists()) wavFile.delete()

        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return null
            }

            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Collect decoded PCM and track actual format
            val pcmBuffers = mutableListOf<ShortArray>()
            var sourceSampleRate = 44100  // default guess
            var sourceChannels = 2        // default guess

            var sawInputEOS = false
            var sawOutputEOS = false
            val bufferInfo = MediaCodec.BufferInfo()

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputIndex = codec.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize,
                                extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val buf = codec.getOutputBuffer(outputIndex)!!
                            buf.position(bufferInfo.offset)
                            val raw = ByteArray(bufferInfo.size)
                            buf.get(raw)

                            val shortBuf = ByteBuffer.wrap(raw)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer()
                            val samples = ShortArray(raw.size / 2)
                            shortBuf.get(samples)
                            pcmBuffers.add(samples)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        sourceSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        sourceChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        android.util.Log.i(TAG, "Output format: ${sourceSampleRate}Hz, ${sourceChannels}ch")
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (pcmBuffers.isEmpty()) return null

            // Flatten all PCM data
            val totalShorts = pcmBuffers.sumOf { it.size }
            val rawAudio = ShortArray(totalShorts)
            var offset = 0
            for (buf in pcmBuffers) {
                System.arraycopy(buf, 0, rawAudio, offset, buf.size)
                offset += buf.size
            }

            android.util.Log.i(TAG, "Decoded: ${totalShorts}shorts, ${sourceSampleRate}Hz, ${sourceChannels}ch")

            // Resample to 16kHz mono
            val resampled = resampleTo16kMono(rawAudio, sourceSampleRate, sourceChannels)
            android.util.Log.i(TAG, "Resampled: ${resampled.size} samples @ 16kHz mono")

            // Write WAV
            writeWavFile(wavFile, resampled, TARGET_SAMPLE_RATE)
            wavFile.absolutePath

        } catch (e: Exception) {
            android.util.Log.e(TAG, "Decode failed", e)
            null
        }
    }

    private fun resampleTo16kMono(
        raw: ShortArray,
        sourceRate: Int,
        channels: Int
    ): ShortArray {
        if (raw.isEmpty()) return ShortArray(0)

        // First: stereo → mono (average channels)
        val mono: ShortArray
        if (channels > 1) {
            val n = raw.size / channels
            mono = ShortArray(n)
            for (i in 0 until n) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += raw[i * channels + ch]
                }
                mono[i] = (sum / channels).toShort()
            }
        } else {
            mono = raw
        }

        // If already 16kHz, return as-is
        if (sourceRate == TARGET_SAMPLE_RATE) return mono

        // Downsample using linear interpolation
        val ratio = sourceRate.toDouble() / TARGET_SAMPLE_RATE
        val outLen = (mono.size / ratio).toInt()
        val out = ShortArray(outLen)

        for (i in 0 until outLen) {
            val srcIndex = i * ratio
            val srcFloor = srcIndex.toInt()
            val frac = srcIndex - srcFloor

            val v1 = mono[srcFloor]
            val v2 = if (srcFloor + 1 < mono.size) mono[srcFloor + 1] else v1
            out[i] = (v1 + (v2 - v1) * frac).toInt().toShort()
        }

        return out
    }

    private fun writeWavFile(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        val fileSize = 44 + dataSize

        FileOutputStream(file).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt(fileSize - 8)
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1)        // PCM
            header.putShort(1)        // mono
            header.putInt(sampleRate)
            header.putInt(sampleRate * 2)
            header.putShort(2)
            header.putShort(16)
            header.put("data".toByteArray())
            header.putInt(dataSize)
            fos.write(header.array())

            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(samples)
            fos.write(buf.array())
        }
    }
}
