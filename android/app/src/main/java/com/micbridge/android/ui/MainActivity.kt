package com.micbridge.android.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.micbridge.android.service.MicService
import com.micbridge.android.transport.ITransportSender
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 主界面 Activity
 * 
 * 采用 Jetpack Compose 构建：
 * 1. 动态权限申请（RECORD_AUDIO, POST_NOTIFICATIONS, BLUETOOTH 等）
 * 2. 绑定 MicService 前台服务
 * 3. 传输模式单选切换 (WiFi, USB AOA, USB ADB, 蓝牙)
 * 4. 实时 RMS 音量动效柱 (Volume Meter)
 * 5. 一键开关控制
 */
class MainActivity : ComponentActivity() {

    private var micService: MicService? = null
    private var isBound = false

    private val serviceBoundFlow = MutableStateFlow(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MicService.LocalBinder
            micService = binder.getService()
            isBound = true
            serviceBoundFlow.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            micService = null
            isBound = false
            serviceBoundFlow.value = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordGranted) {
            Toast.makeText(this, "需要麦克风录音权限才能采集音频！", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        // 启动并绑定前台服务
        val serviceIntent = Intent(this, MicService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121418)
                ) {
                    MainScreen(
                        micService = micService,
                        onToggleStream = { type, start ->
                            if (start) {
                                if (hasRecordPermission()) {
                                    micService?.startStreaming(type)
                                } else {
                                    checkAndRequestPermissions()
                                }
                            } else {
                                micService?.stopStreaming()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        super.onDestroy()
    }
}

@Composable
fun MainScreen(
    micService: MicService?,
    onToggleStream: (ITransportSender.Type, Boolean) -> Unit
) {
    val isStreaming by (micService?.isStreaming ?: MutableStateFlow(false)).collectAsState()
    val volumeLevel by (micService?.currentVolume ?: MutableStateFlow(0f)).collectAsState()
    val statusText by (micService?.statusText ?: MutableStateFlow("服务未连接")).collectAsState()
    val activeTransport by (micService?.transportType ?: MutableStateFlow(ITransportSender.Type.WIFI_UDP)).collectAsState()

    var selectedTransport by remember { mutableStateOf(ITransportSender.Type.WIFI_UDP) }

    val animatedVolume by animateFloatAsState(
        targetValue = volumeLevel,
        animationSpec = tween(durationMillis = 60, easing = FastOutSlowInEasing),
        label = "VolumeAnimation"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // 顶部标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = Color(0xFF4E95FF),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "MicBridge 手机麦克风",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Text(
            text = "48kHz 16-bit 10ms 极低延迟音频桥接引擎",
            fontSize = 13.sp,
            color = Color(0xFF9E9E9E)
        )

        // 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E222B))
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val statusDotColor by animateColorAsState(
                    targetValue = if (isStreaming) Color(0xFF00E676) else Color(0xFFFF5252),
                    label = "StatusDot"
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isStreaming) "正在实时传输" else "已就绪",
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = Color(0xFFB0BEC5)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 实时 RMS 音量条
                Text(
                    text = "实时音频输入强度",
                    fontSize = 12.sp,
                    color = Color(0xFF78909C),
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { animatedVolume },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (animatedVolume > 0.85f) Color(0xFFFF5252) else Color(0xFF00E676),
                    trackColor = Color(0xFF2C3240)
                )
            }
        }

        // 传输通道选择器
        Text(
            text = "选择传输协议通道",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier = Modifier.align(Alignment.Start)
        )

        val transportList = listOf(
            Triple(ITransportSender.Type.WIFI_UDP, "WiFi UDP", Icons.Default.Wifi),
            Triple(ITransportSender.Type.USB_AOA, "USB AOA 2.0 (免调试)", Icons.Default.Usb),
            Triple(ITransportSender.Type.USB_ADB, "USB ADB 端口转发", Icons.Default.SettingsInputAntenna),
            Triple(ITransportSender.Type.BLUETOOTH_SPP, "经典蓝牙 SPP", Icons.Default.Bluetooth)
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            transportList.forEach { (type, title, icon) ->
                val isSelected = (if (isStreaming) activeTransport else selectedTransport) == type
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isStreaming) {
                            selectedTransport = type
                        },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) Color(0xFF25334D) else Color(0xFF1A1D24)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = if (isSelected) Color(0xFF4E95FF) else Color(0xFF78909C),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = title,
                            color = Color.White,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        RadioButton(
                            selected = isSelected,
                            onClick = { if (!isStreaming) selectedTransport = type },
                            enabled = !isStreaming
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 一键开始 / 停止大按钮
        Button(
            onClick = {
                val targetType = if (isStreaming) activeTransport else selectedTransport
                onToggleStream(targetType, !isStreaming)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isStreaming) Color(0xFFE53935) else Color(0xFF2979FF)
            )
        ) {
            Icon(
                imageVector = if (isStreaming) Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null,
                tint = Color.White
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (isStreaming) "停止传输并静音" else "启动麦克风串流",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}
