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

    /**
     * Decode any audio/video file to a 16kHz mono 16-bit WAV file.
     * Returns the WAV file path, or null on failure.
     */
    fun decodeToWav(context: android.content.Context, uri: Uri): String? {
        val wavFile = File(context.cacheDir, "decoded_audio.wav")
        if (wavFile.exists()) wavFile.delete()

        return try {
            val extractor = MediaExtractor()
            context.contentResolver.openInputStream(uri)?.use { input ->
                extractor.setDataSource(input.fd)
            } ?: run {
                extractor.release()
                return null
            }

            // Find the first audio track
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.trackFormat(i)
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

            val pcmData = mutableListOf<ShortArray>()
            val outputFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_RAW,
                16000, 1
            )
            outputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)

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
                            val pts = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                when {
                    outputIndex >= 0 -> {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                        if (bufferInfo.size > 0) {
                            // Convert to mono 16-bit PCM
                            val shorts = ByteArray(bufferInfo.size)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.get(shorts, 0, bufferInfo.size)

                            val shortBuf = ByteBuffer.wrap(shorts).order(
                                if (java.nio.ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
                                    ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
                            ).asShortBuffer()

                            val numShorts = bufferInfo.size / 2
                            val samples = ShortArray(numShorts)
                            shortBuf.get(samples)
                            pcmData.add(samples)
                        }
                        codec.releaseOutputBuffer(outputIndex, false)

                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // Format changed, continue
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()

            if (pcmData.isEmpty()) return null

            // Calculate total samples
            val totalSamples = pcmData.sumOf { it.size }
            val dataSize = totalSamples * 2 // 16-bit = 2 bytes per sample
            val fileSize = 44 + dataSize

            // Write WAV file
            FileOutputStream(wavFile).use { fos ->
                val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                // RIFF header
                buf.put("RIFF".toByteArray())
                buf.putInt(fileSize - 8)
                buf.put("WAVE".toByteArray())
                // fmt chunk
                buf.put("fmt ".toByteArray())
                buf.putInt(16) // chunk size
                buf.putShort(1) // PCM
                buf.putShort(1) // mono
                buf.putInt(16000) // sample rate
                buf.putInt(16000 * 2) // byte rate
                buf.putShort(2) // block align
                buf.putShort(16) // bits per sample
                // data chunk
                buf.put("data".toByteArray())
                buf.putInt(dataSize)
                fos.write(buf.array())

                // Write samples
                for (samples in pcmData) {
                    val out = ByteBuffer.allocate(samples.size * 2)
                        .order(ByteOrder.LITTLE_ENDIAN)
                    for (s in samples) {
                        out.putShort(s)
                    }
                    fos.write(out.array())
                }
            }

            wavFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("AudioDecoder", "Decode failed", e)
            null
        }
    }
}
