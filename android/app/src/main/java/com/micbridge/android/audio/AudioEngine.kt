package com.micbridge.android.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 核心音频采集引擎
 * 
 * 规格规范：
 * - 采样率：48000 Hz
 * - 声道数：单声道 Mono (AudioFormat.CHANNEL_IN_MONO)
 * - 编码位深：16-bit PCM (AudioFormat.ENCODING_PCM_16BIT)
 * - 单包帧长：10ms = 480 个采样点 = 960 字节净荷
 * - 音频源：MediaRecorder.AudioSource.VOICE_COMMUNICATION (优先硬件级回声消除 AEC、噪声抑制 NS 和自动增益 AGC)
 * 
 * 数据包协议格式 (12 字节头部 + 960 字节 PCM = 972 字节):
 * [0..3]   Magic: 0x4D494331 ("MIC1")
 * [4..5]   Sequence Number: 2 字节无符号递增序号 (0~65535 循环)
 * [6..7]   Payload Length: 2 字节有效载荷长度 (960)
 * [8..11]  Timestamp: 4 字节单调递增毫秒时间戳
 * [12..]   Raw PCM Data: 960 字节 (480 个 16-bit 采样点)
 */
class AudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "AudioEngine"

        const val SAMPLE_RATE = 48000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // 10ms 音频对应采样点数与字节大小
        const val SAMPLES_PER_10MS = SAMPLE_RATE / 100 // 480 采样点
        const val BYTES_PER_SAMPLE = 2 // 16-bit = 2 字节
        const val PAYLOAD_SIZE = SAMPLES_PER_10MS * BYTES_PER_SAMPLE // 960 字节

        const val HEADER_SIZE = 12
        const val TOTAL_PACKET_SIZE = HEADER_SIZE + PAYLOAD_SIZE // 972 字节

        val MAGIC_BYTES = byteArrayOf('M'.code.toByte(), 'I'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())
    }

    /**
     * 音频数据帧回调接口
     */
    fun interface AudioPacketCallback {
        fun onAudioPacket(packetData: ByteArray, offset: Int, length: Int, dbLevel: Float)
    }

    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var packetSequence: Short = 0
    private var callback: AudioPacketCallback? = null

    /**
     * 设置音频数据接收回调
     */
    fun setCallback(callback: AudioPacketCallback?) {
        this.callback = callback
    }

    /**
     * 启动音频采集引擎
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(): Boolean {
        if (isRecording) {
            Log.w(TAG, "AudioEngine is already running")
            return true
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(TAG, "Invalid AudioRecord parameters: minBufferSize=$minBufferSize")
            return false
        }

        // 内部双缓冲或更大缓冲区，避免录音线程偶尔调度延迟导致底层溢出丢包
        val internalBufferSize = maxOf(minBufferSize, PAYLOAD_SIZE * 8)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                internalBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize with VOICE_COMMUNICATION, fallback to MIC")
                audioRecord?.release()
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    internalBufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize completely")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            // 激活底层硬件音效消除器 (AEC / NS / AGC)
            val audioSessionId = audioRecord!!.audioSessionId
            setupHardwareAudioEffects(audioSessionId)

            audioRecord?.startRecording()
            isRecording = true
            packetSequence = 0

            recordingThread = Thread({ recordLoop() }, "MicBridge-AudioRecordThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }

            Log.i(TAG, "AudioEngine started successfully: 48kHz, 16bit mono, 10ms chunk (960B)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception starting AudioRecord", e)
            stop()
            return false
        }
    }

    /**
     * 硬件回声消除与降噪初始化
     */
    private fun setupHardwareAudioEffects(sessionId: Int) {
        if (sessionId == 0) return

        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "AcousticEchoCanceler enabled on session $sessionId")
                }
            } else {
                Log.w(TAG, "AcousticEchoCanceler not available on this device")
            }

            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "NoiseSuppressor enabled on session $sessionId")
                }
            } else {
                Log.w(TAG, "NoiseSuppressor not available on this device")
            }

            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply {
                    enabled = true
                    Log.i(TAG, "AutomaticGainControl enabled on session $sessionId")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting up hardware audio effects", e)
        }
    }

    /**
     * 音频采集与封包循环
     */
    private fun recordLoop() {
        // 分配 972 字节缓冲区 (12 字节头部 + 960 字节载荷)
        val packetBuffer = ByteArray(TOTAL_PACKET_SIZE)
        val headerBuffer = ByteBuffer.wrap(packetBuffer, 0, HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)

        // 预写入 Magic "MIC1" (0x4D494331)
        packetBuffer[0] = 'M'.code.toByte()
        packetBuffer[1] = 'I'.code.toByte()
        packetBuffer[2] = 'C'.code.toByte()
        packetBuffer[3] = '1'.code.toByte()

        val record = audioRecord ?: return

        var accumulatedBytes = 0

        while (isRecording) {
            val bytesNeeded = PAYLOAD_SIZE - accumulatedBytes
            val readBytes = record.read(packetBuffer, HEADER_SIZE + accumulatedBytes, bytesNeeded)

            if (readBytes > 0) {
                accumulatedBytes += readBytes
                if (accumulatedBytes == PAYLOAD_SIZE) {
                    // 已集齐 10ms 完整 960 字节载荷
                    val currentSeq = packetSequence++
                    val timestamp = (System.currentTimeMillis() and 0xFFFFFFFFL).toInt()

                    // 写入头部字段: Seq(2B) + Length(2B) + Timestamp(4B)
                    headerBuffer.position(4)
                    headerBuffer.putShort(currentSeq)
                    headerBuffer.putShort(PAYLOAD_SIZE.toShort())
                    headerBuffer.putInt(timestamp)

                    // 计算当前帧的 RMS 分贝值 (用于前端 UI 音量计实时动态呈现)
                    val db = calculateRmsDb(packetBuffer, HEADER_SIZE, PAYLOAD_SIZE)

                    // 回调送给传输通道 (各传输通道自行负责异步网络/USB传输)
                    callback?.onAudioPacket(packetBuffer, 0, TOTAL_PACKET_SIZE, db)

                    accumulatedBytes = 0
                }
            } else if (readBytes < 0) {
                Log.w(TAG, "AudioRecord read error: $readBytes")
                if (!isRecording) break
                try {
                    Thread.sleep(5)
                } catch (_: InterruptedException) {}
            }
        }
    }

    /**
     * 计算 PCM 16-bit 单声道的瞬时 RMS 归一化幅度
     * 返回 0.0 ~ 1.0 的平滑音量比例，供 UI 进度条使用
     */
    private fun calculateRmsDb(buffer: ByteArray, offset: Int, length: Int): Float {
        var sumSquares = 0.0
        val sampleCount = length / 2
        var maxSample = 0

        for (i in 0 until sampleCount) {
            val idx = offset + i * 2
            val low = buffer[idx].toInt() and 0xFF
            val high = buffer[idx + 1].toInt()
            val sample = (high shl 8) or low
            val absSample = abs(sample)
            if (absSample > maxSample) maxSample = absSample
            sumSquares += sample.toDouble() * sample.toDouble()
        }

        if (sampleCount == 0 || maxSample == 0) return 0f

        val rms = sqrt(sumSquares / sampleCount)
        val normalized = (rms / 32768.0).toFloat()
        return normalized.coerceIn(0f, 1f)
    }

    /**
     * 停止采集并释放所有硬件与音效资源
     */
    @Synchronized
    fun stop() {
        isRecording = false
        recordingThread?.interrupt()
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Exception stopping AudioRecord", e)
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Exception releasing AudioRecord", e)
        }
        audioRecord = null

        echoCanceler?.release()
        echoCanceler = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        gainControl?.release()
        gainControl = null

        Log.i(TAG, "AudioEngine stopped and resources released")
    }

    fun isRunning(): Boolean = isRecording
}
