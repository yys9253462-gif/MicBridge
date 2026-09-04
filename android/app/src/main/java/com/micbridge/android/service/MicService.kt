package com.micbridge.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.micbridge.android.audio.AudioEngine
import com.micbridge.android.transport.BluetoothSppSender
import com.micbridge.android.transport.ITransportSender
import com.micbridge.android.transport.UsbAdbSender
import com.micbridge.android.transport.UsbAoaSender
import com.micbridge.android.transport.WifiUdpSender
import com.micbridge.android.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 麦克风核心后台常驻保活服务
 */
class MicService : Service() {

    companion object {
        private const val TAG = "MicService"
        private const val CHANNEL_ID = "micbridge_audio_streaming"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_STREAMING = "com.micbridge.android.action.START_STREAMING"
        const val ACTION_STOP_STREAMING = "com.micbridge.android.action.STOP_STREAMING"
        const val EXTRA_TRANSPORT_TYPE = "extra_transport_type"
        const val EXTRA_TARGET_HOST = "extra_target_host"
        const val EXTRA_TARGET_PORT = "extra_target_port"
        const val EXTRA_BLUETOOTH_MAC = "extra_bluetooth_mac"
    }

    inner class LocalBinder : Binder() {
        fun getService(): MicService = this@MicService
    }

    private val binder = LocalBinder()

    private var audioEngine: AudioEngine? = null
    private var currentSender: ITransportSender? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // UI 观察的状态 Flow
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _currentVolume = MutableStateFlow(0f)
    val currentVolume: StateFlow<Float> = _currentVolume.asStateFlow()

    private val _statusText = MutableStateFlow("空闲中")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _transportType = MutableStateFlow(ITransportSender.Type.WIFI_UDP)
    val transportType: StateFlow<ITransportSender.Type> = _transportType.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _gainMultiplier = MutableStateFlow(1.0f)
    val gainMultiplier: StateFlow<Float> = _gainMultiplier.asStateFlow()

    // 统计指标
    private val _sentPackets = MutableStateFlow(0L)
    val sentPackets: StateFlow<Long> = _sentPackets.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MicService onCreate")
        createNotificationChannel()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MicBridge:AudioCaptureWakeLock").apply {
            setReferenceCounted(false)
        }

        audioEngine = AudioEngine(applicationContext)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_STREAMING -> {
                val typeOrdinal = intent.getIntExtra(EXTRA_TRANSPORT_TYPE, ITransportSender.Type.WIFI_UDP.ordinal)
                val type = ITransportSender.Type.entries.getOrElse(typeOrdinal) { ITransportSender.Type.WIFI_UDP }
                val targetHost = intent.getStringExtra(EXTRA_TARGET_HOST) ?: "255.255.255.255"
                val targetPort = intent.getIntExtra(EXTRA_TARGET_PORT, 18889)
                val bluetoothMac = intent.getStringExtra(EXTRA_BLUETOOTH_MAC)
                startStreaming(type, targetHost, targetPort, bluetoothMac)
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
        }
        return START_STICKY
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    fun setGain(gain: Float) {
        _gainMultiplier.value = gain.coerceIn(0.2f, 3.0f)
    }

    @Synchronized
    fun startStreaming(
        type: ITransportSender.Type,
        targetHost: String = "255.255.255.255",
        targetPort: Int = 18889,
        bluetoothMac: String? = null
    ) {
        if (_isStreaming.value) {
            stopStreaming()
        }

        _transportType.value = type
        _sentPackets.value = 0L
        startForegroundWithNotification("正在启动 [${type.displayName}] 音频传输...")

        // 锁定 CPU，防止黑屏采集丢包
        wakeLock?.acquire(24 * 60 * 60 * 1000L)

        // 初始化对应的传输发送器
        currentSender = when (type) {
            ITransportSender.Type.WIFI_UDP -> WifiUdpSender(
                context = applicationContext,
                targetHost = targetHost,
                targetPort = targetPort
            )
            ITransportSender.Type.USB_AOA -> UsbAoaSender(applicationContext)
            ITransportSender.Type.USB_ADB -> UsbAdbSender(18888)
            ITransportSender.Type.BLUETOOTH_SPP -> BluetoothSppSender(
                context = applicationContext,
                targetDeviceAddress = bluetoothMac
            )
        }

        currentSender?.start(object : ITransportSender.StatusListener {
            override fun onStatusChanged(connected: Boolean, message: String) {
                _statusText.value = message
                updateNotification(message)
            }

            override fun onError(error: String) {
                _statusText.value = "错误: $error"
                updateNotification("错误: $error")
            }
        })

        // 设置音频采集回调并打包装入传输通道
        audioEngine?.setCallback { packetData, offset, length, dbLevel ->
            if (_isMuted.value) {
                // 静音模式下将 PCM 载荷清零
                val headerSize = AudioEngine.HEADER_SIZE
                for (i in headerSize until length) {
                    packetData[i] = 0
                }
                _currentVolume.value = 0f
            } else {
                val gain = _gainMultiplier.value
                if (kotlin.math.abs(gain - 1.0f) > 0.05f) {
                    // 应用软件增益调节
                    applySoftwareGain(packetData, AudioEngine.HEADER_SIZE, length - AudioEngine.HEADER_SIZE, gain)
                }
                _currentVolume.value = dbLevel
            }

            currentSender?.sendAudioPacket(packetData, offset, length)
            _sentPackets.value += 1L
        }

        val success = audioEngine?.start() ?: false
        if (success) {
            _isStreaming.value = true
            _statusText.value = "正在串流传输 (${type.displayName})"
            updateNotification("音频串流中 - ${type.displayName}")
            Log.i(TAG, "Audio streaming started with type $type")
        } else {
            _statusText.value = "音频引擎启动失败，请检查麦克风权限"
            stopStreaming()
        }
    }

    private fun applySoftwareGain(buffer: ByteArray, offset: Int, length: Int, gain: Float) {
        val sampleCount = length / 2
        for (i in 0 until sampleCount) {
            val idx = offset + i * 2
            val low = buffer[idx].toInt() and 0xFF
            val high = buffer[idx + 1].toInt()
            var sample = (high shl 8) or low
            sample = (sample * gain).toInt().coerceIn(-32768, 32767)
            buffer[idx] = (sample and 0xFF).toByte()
            buffer[idx + 1] = ((sample shr 8) and 0xFF).toByte()
        }
    }

    @Synchronized
    fun stopStreaming() {
        if (!_isStreaming.value && currentSender == null) return

        audioEngine?.stop()
        audioEngine?.setCallback(null)

        currentSender?.stop()
        currentSender = null

        _isStreaming.value = false
        _currentVolume.value = 0f
        _statusText.value = "已停止推流"

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Audio streaming stopped")
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MicBridge 音频采集后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持麦克风持续采集并超低延迟发送给电脑端"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MicService::class.java).apply { action = ACTION_STOP_STREAMING },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MicBridge 麦克风串流中")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止传输", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundWithNotification(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        if (!_isStreaming.value) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
        Log.i(TAG, "MicService onDestroy")
    }
}
