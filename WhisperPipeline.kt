package com.nethsara.aiplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.URL

object WhisperPipeline {
    private const val MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
    private const val MODEL_NAME = "ggml-base.bin"

    suspend fun ensureModel(context: Context): File = withContext(Dispatchers.IO) {
        val model = File(context.getExternalFilesDir("models"), MODEL_NAME)
        if (model.exists() && model.length() > 100_000_000L) return@withContext model
        model.parentFile?.mkdirs()
        val tmp = File(model.parent, "$MODEL_NAME.part")
        URL(MODEL_URL).openConnection().apply { connectTimeout = 20000; readTimeout = 60000 }.getInputStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output, 1024 * 1024) }
        }
        if (tmp.length() < 100_000_000L) { tmp.delete(); error("Whisper model download incomplete") }
        if (!tmp.renameTo(model)) { tmp.copyTo(model, overwrite = true); tmp.delete() }
        model
    }

    suspend fun videoToWav(context: Context, uri: Uri): File = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
            ?: error("Video audio track not found")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Audio MIME missing")
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0); decoder.start()
        val out = ArrayList<Short>(4_000_000)
        val info = MediaCodec.BufferInfo(); var inputDone = false; var outputDone = false; var sr = 16000; var ch = 1
        while (!outputDone) {
            if (!inputDone) {
                val idx = decoder.dequeueInputBuffer(10000)
                if (idx >= 0) {
                    val buf = decoder.getInputBuffer(idx)!!; buf.clear()
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) { decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { decoder.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
            val oi = decoder.dequeueOutputBuffer(info, 10000)
            if (oi >= 0) {
                val of = decoder.outputFormat
                sr = of.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val b = decoder.getOutputBuffer(oi)
                if (b != null && info.size > 0) {
                    b.position(info.offset); b.limit(info.offset + info.size)
                    while (b.remaining() >= 2) out.add(b.short)
                }
                decoder.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
            }
        }
        decoder.stop(); decoder.release(); extractor.release()
        val mono = if (ch > 1) out.chunked(ch).map { frame -> frame.take(ch).map { it.toInt() }.average().toInt().toShort() } else out
        val resampled = if (sr == 16000) mono else resample(mono, sr, 16000)
        val wav = File(context.cacheDir, "nethsara_whisper_audio.wav")
        RandomAccessFile(wav, "rw").use { f ->
            f.setLength(0); writeWavHeader(f, resampled.size * 2, 16000, 1)
            resampled.forEach { f.writeShort(java.lang.Short.reverseBytes(it).toInt()) }
        }
        wav
    }

    private fun resample(input: List<Short>, from: Int, to: Int): List<Short> {
        val n = (input.size.toLong() * to / from).toInt(); val result = ArrayList<Short>(n)
        for (i in 0 until n) { val p = i.toDouble() * from / to; val a=p.toInt().coerceIn(0,input.lastIndex); val b=(a+1).coerceAtMost(input.lastIndex); val frac=p-a; result.add((input[a]*(1-frac)+input[b]*frac).toInt().coerceIn(-32768,32767).toShort()) }
        return result
    }
    private fun writeWavHeader(f: RandomAccessFile, bytes: Int, rate: Int, channels: Int) {
        f.writeBytes("RIFF"); writeLE(f, bytes+36); f.writeBytes("WAVEfmt "); writeLE(f,16); writeLE(f,1,2); writeLE(f,channels,2); writeLE(f,rate); writeLE(f,rate*channels*2); writeLE(f,channels*2,2); writeLE(f,16,2); f.writeBytes("data"); writeLE(f,bytes)
    }
    private fun writeLE(f: RandomAccessFile, v: Int, n: Int = 4) { repeat(n) { f.write(v ushr (8*it) and 255) } }
}
