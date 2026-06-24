package com.hfad.mantou.data.api

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * 录制 16kHz / 单声道 / 16-bit PCM 的 WAV 文件，给 MiMo ASR 用。
 * MediaRecorder 不直接支持 wav/mp3，因此用 AudioRecord 自己拼 WAV header。
 */
class WavAudioRecorder(private val outputFile: File) {

    private val sampleRate = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bytesPerSample = 2
    private val channelCount = 1

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var running = false
    private var bytesWritten: Long = 0L

    @SuppressLint("MissingPermission")
    fun start() {
        require(!running) { "WavAudioRecorder 已在录制" }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        require(minBuffer > 0) { "无法获取录音缓冲区大小: $minBuffer" }
        val bufferSize = minBuffer * 2

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("AudioRecord 初始化失败")
        }

        outputFile.parentFile?.mkdirs()
        val raf = RandomAccessFile(outputFile, "rw")
        raf.setLength(0)
        writeWavHeaderPlaceholder(raf)

        record.startRecording()
        recorder = record
        running = true
        bytesWritten = 0L

        recordThread = Thread({
            val buffer = ByteArray(bufferSize)
            try {
                while (running) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        raf.write(buffer, 0, read)
                        bytesWritten += read
                    } else if (read < 0) {
                        Log.w(TAG, "AudioRecord.read 返回 $read")
                        break
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "录音写入失败", t)
            } finally {
                runCatching { finalizeWavHeader(raf, bytesWritten) }
                runCatching { raf.close() }
            }
        }, "WavAudioRecorder").also { it.start() }
    }

    /** 停止录音；返回写入的 PCM 字节数（不含 WAV header）。 */
    fun stop(): Long {
        if (!running) return bytesWritten
        running = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recordThread?.join(1500) }
        recordThread = null
        return bytesWritten
    }

    fun cancel() {
        running = false
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { recordThread?.join(800) }
        recordThread = null
    }

    private fun writeWavHeaderPlaceholder(raf: RandomAccessFile) {
        raf.write(ByteArray(WAV_HEADER_SIZE))
    }

    private fun finalizeWavHeader(raf: RandomAccessFile, pcmBytes: Long) {
        val byteRate = sampleRate * channelCount * bytesPerSample
        val blockAlign = channelCount * bytesPerSample
        val dataSize = pcmBytes.toInt()
        val chunkSize = 36 + dataSize

        raf.seek(0)
        raf.write("RIFF".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(chunkSize)
        raf.write("WAVE".toByteArray(Charsets.US_ASCII))

        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(16) // PCM fmt chunk size
        raf.writeShortLE(1) // PCM format
        raf.writeShortLE(channelCount)
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(blockAlign)
        raf.writeShortLE(bytesPerSample * 8)

        raf.write("data".toByteArray(Charsets.US_ASCII))
        raf.writeIntLE(dataSize)
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }

    companion object {
        private const val TAG = "WavAudioRecorder"
        private const val WAV_HEADER_SIZE = 44
    }
}
