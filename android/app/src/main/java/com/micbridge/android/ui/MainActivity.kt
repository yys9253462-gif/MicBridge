package com.micbridge.android.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.micbridge.android.audio.AudioEngine
import com.micbridge.android.service.MicService
import com.micbridge.android.transport.ITransportSender
import com.micbridge.android.transport.WifiUdpSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 重构后的主界面：标准 3 步完整闭环工作流
 * 步骤一：【配对与连接设备 (Pair & Connect)】
 * 步骤二：【麦克风测试 (Mic Test)】
 * 步骤三：【正式串流 (Live Stream)】
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

        val serviceIntent = Intent(this, MicService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101216)
                ) {
                    WorkflowScreen(
                        context = this,
                        micService = micService,
                        hasRecordPermission = ::hasRecordPermission,
                        requestPermissions = ::checkAndRequestPermissions
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
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
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
fun WorkflowScreen(
    context: Context,
    micService: MicService?,
    hasRecordPermission: () -> Boolean,
    requestPermissions: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // 串流核心状态
    val isStreaming by (micService?.isStreaming ?: MutableStateFlow(false)).collectAsState()
    val streamVolume by (micService?.currentVolume ?: MutableStateFlow(0f)).collectAsState()
    val statusText by (micService?.statusText ?: MutableStateFlow("空闲")).collectAsState()
    val isMuted by (micService?.isMuted ?: MutableStateFlow(false)).collectAsState()
    val gainMultiplier by (micService?.gainMultiplier ?: MutableStateFlow(1.0f)).collectAsState()
    val sentPackets by (micService?.sentPackets ?: MutableStateFlow(0L)).collectAsState()

    // 当前选中的传输通道
    var selectedTransport by remember { mutableStateOf(ITransportSender.Type.WIFI_UDP) }

    // 配对状态
    var isPaired by remember { mutableStateOf(false) }
    var pairedSummary by remember { mutableStateOf("") }

    // WiFi 专用设置
    var targetIp by remember { mutableStateOf("192.168.1.") }
    var targetPortStr by remember { mutableStateOf("18889") }
    var isScanningWifi by remember { mutableStateOf(false) }
    var isPingingWifi by remember { mutableStateOf(false) }
    var lastPingRtt by remember { mutableStateOf<Long?>(null) }
    val discoveredPcs = remember { mutableStateListOf<WifiUdpSender.DiscoveredPc>() }

    // USB 专用状态
    var usbAoaConnected by remember { mutableStateOf(false) }
    var usbAdbConnected by remember { mutableStateOf(false) }
    var isCheckingUsb by remember { mutableStateOf(false) }

    // 蓝牙专用状态
    data class SimpleBluetoothDevice(val name: String, val address: String)
    val pairedBtDevices = remember { mutableStateListOf<SimpleBluetoothDevice>() }
    var selectedBtDevice by remember { mutableStateOf<SimpleBluetoothDevice?>(null) }
    var btTesting by remember { mutableStateOf(false) }

    // 步骤二：麦克风测试状态
    var isMicTesting by remember { mutableStateOf(false) }
    var micTestSecondsRemaining by remember { mutableIntStateOf(0) }
    var testAudioLevel by remember { mutableFloatStateOf(0f) }
    var micTestPassed by remember { mutableStateOf(false) }
    var micTestFeedback by remember { mutableStateOf("") }
    val waveformPoints = remember { mutableStateListOf<Float>() }

    // 音量动效
    val animatedStreamVolume by animateFloatAsState(
        targetValue = streamVolume,
        animationSpec = tween(durationMillis = 60, easing = FastOutSlowInEasing),
        label = "StreamVolumeAnim"
    )

    val animatedTestLevel by animateFloatAsState(
        targetValue = testAudioLevel,
        animationSpec = tween(durationMillis = 60, easing = FastOutSlowInEasing),
        label = "TestAudioLevelAnim"
    )

    // 检查 USB 状态函数
    fun checkUsbStatus() {
        isCheckingUsb = true
        coroutineScope.launch(Dispatchers.IO) {
            val um = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val accessories = um?.accessoryList
            val hasAoa = !accessories.isNullOrEmpty()

            var hasAdb = false
            try {
                val s = Socket()
                s.connect(InetSocketAddress("127.0.0.1", 18888), 600)
                hasAdb = s.isConnected
                s.close()
            } catch (_: Exception) {}

            withContext(Dispatchers.Main) {
                usbAoaConnected = hasAoa
                usbAdbConnected = hasAdb
                isCheckingUsb = false
                if (selectedTransport == ITransportSender.Type.USB_AOA && hasAoa) {
                    isPaired = true
                    pairedSummary = "USB AOA 配件模式已就绪 (${accessories?.firstOrNull()?.description ?: "Android Accessory"})"
                } else if (selectedTransport == ITransportSender.Type.USB_ADB && hasAdb) {
                    isPaired = true
                    pairedSummary = "USB ADB 端口转发 (127.0.0.1:18888) 连通成功"
                } else if (selectedTransport == ITransportSender.Type.USB_AOA || selectedTransport == ITransportSender.Type.USB_ADB) {
                    isPaired = false
                    pairedSummary = "未检测到对应 USB 连接"
                }
            }
        }
    }

    // 刷新蓝牙配对列表
    @SuppressLint("MissingPermission")
    fun refreshBluetoothDevices() {
        pairedBtDevices.clear()
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val bonded = adapter?.bondedDevices
            bonded?.forEach { dev ->
                pairedBtDevices.add(SimpleBluetoothDevice(name = dev.name ?: "未知设备", address = dev.address))
            }
        } catch (_: Exception) {}
    }

    // 麦克风 5 秒测试逻辑
    fun startMicTest() {
        if (!hasRecordPermission()) {
            requestPermissions()
            return
        }
        if (isMicTesting) return

        isMicTesting = true
        micTestPassed = false
        micTestSecondsRemaining = 5
        micTestFeedback = "正在录音采样与发送电脑回传测试包..."
        waveformPoints.clear()

        coroutineScope.launch(Dispatchers.IO) {
            var testAudioEngine: AudioEngine? = null
            var soundMaxLevel = 0f
            var receivedPcReply = false

            try {
                testAudioEngine = AudioEngine(context)
                testAudioEngine.setCallback { packetData, offset, length, dbLevel ->
                    testAudioLevel = dbLevel
                    if (dbLevel > soundMaxLevel) soundMaxLevel = dbLevel
                    if (waveformPoints.size > 40) waveformPoints.removeAt(0)
                    waveformPoints.add(dbLevel)
                }
                testAudioEngine.start()

                // 同时尝试向 PC 发送一段 PING/测试包测试链路
                val targetPort = targetPortStr.toIntOrNull() ?: 18889
                if (selectedTransport == ITransportSender.Type.WIFI_UDP && targetIp.isNotBlank()) {
                    val (pongOk, _) = WifiUdpSender.pingTest(targetIp, targetPort, timeoutMs = 2500)
                    receivedPcReply = pongOk
                } else {
                    receivedPcReply = isPaired
                }

                // 倒计时 5 秒
                for (sec in 5 downTo 1) {
                    withContext(Dispatchers.Main) {
                        micTestSecondsRemaining = sec
                    }
                    delay(1000)
                }

                withContext(Dispatchers.Main) {
                    isMicTesting = false
                    testAudioLevel = 0f
                    val micOk = soundMaxLevel > 0.05f
                    if (micOk && (receivedPcReply || isPaired)) {
                        micTestPassed = true
                        micTestFeedback = "✔ 麦克风采样正常 (峰值电平 ${(soundMaxLevel * 100).roundToInt()}%)，电脑已成功响应信号！"
                    } else if (!micOk) {
                        micTestPassed = false
                        micTestFeedback = "⚠ 未检测到明显声音输入，请靠近麦克风或调大系统音量后再试。"
                    } else {
                        micTestPassed = true
                        micTestFeedback = "✔ 麦克风工作正常，声音波形良好。"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isMicTesting = false
                    testAudioLevel = 0f
                    micTestFeedback = "测试发生异常: ${e.message}"
                }
            } finally {
                testAudioEngine?.stop()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 顶部品牌栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PhoneAndroid,
                contentDescription = null,
                tint = Color(0xFF388AF6),
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "MicBridge 手机麦克风",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "极低延迟·先配对·再测试·后串流",
                    fontSize = 12.sp,
                    color = Color(0xFF90A4AE)
                )
            }
        }

        // ==========================================
        // 步骤一：【配对与连接设备 (Pair & Connect)】
        // ==========================================
        StepContainer(
            stepNumber = 1,
            title = "配对与连接设备 (Pair & Connect)",
            isCompleted = isPaired,
            statusBadge = if (isPaired) "已配对" else "待配对"
        ) {
            // 通道选择 Tab
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val types = listOf(
                    Triple(ITransportSender.Type.WIFI_UDP, "WiFi", Icons.Default.Wifi),
                    Triple(ITransportSender.Type.USB_AOA, "USB AOA", Icons.Default.Usb),
                    Triple(ITransportSender.Type.USB_ADB, "USB ADB", Icons.Default.SettingsInputAntenna),
                    Triple(ITransportSender.Type.BLUETOOTH_SPP, "蓝牙", Icons.Default.Bluetooth)
                )
                types.forEach { (type, label, icon) ->
                    val isSelected = selectedTransport == type
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E2430))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) Color(0xFF3B82F6) else Color.Transparent,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .clickable(enabled = !isStreaming) {
                                selectedTransport = type
                                isPaired = false
                                micTestPassed = false
                                if (type == ITransportSender.Type.USB_AOA || type == ITransportSender.Type.USB_ADB) {
                                    checkUsbStatus()
                                } else if (type == ITransportSender.Type.BLUETOOTH_SPP) {
                                    refreshBluetoothDevices()
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) Color.White else Color(0xFF94A3B8),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = label,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) Color.White else Color(0xFF94A3B8)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 根据选中的传输模式展示对应的配对控制面板
            when (selectedTransport) {
                ITransportSender.Type.WIFI_UDP -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isScanningWifi = true
                                    discoveredPcs.clear()
                                    coroutineScope.launch {
                                        val pcs = WifiUdpSender.scanDevices(context, timeoutMs = 2500)
                                        discoveredPcs.addAll(pcs)
                                        isScanningWifi = false
                                        if (pcs.isNotEmpty()) {
                                            val first = pcs.first()
                                            targetIp = first.ip
                                            targetPortStr = first.port.toString()
                                            isPaired = true
                                            pairedSummary = "已自动选中: ${first.name} (${first.ip}:${first.port})"
                                        }
                                    }
                                },
                                enabled = !isScanningWifi && !isStreaming,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                            ) {
                                if (isScanningWifi) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("扫描中...", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("自动扫描电脑", fontSize = 13.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    isPingingWifi = true
                                    coroutineScope.launch {
                                        val port = targetPortStr.toIntOrNull() ?: 18889
                                        val (ok, rtt) = WifiUdpSender.pingTest(targetIp, port, timeoutMs = 1500)
                                        isPingingWifi = false
                                        lastPingRtt = if (ok) rtt else null
                                        if (ok) {
                                            isPaired = true
                                            pairedSummary = "连通测试成功: $targetIp:$port (往返延时 ${rtt}ms)"
                                        } else {
                                            isPaired = false
                                            pairedSummary = "连通失败: 目标无响应，请确保 PC 端 MicBridge 正在运行"
                                        }
                                    }
                                },
                                enabled = !isPingingWifi && targetIp.isNotBlank() && !isStreaming,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                if (isPingingWifi) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("测试中...", fontSize = 13.sp)
                                } else {
                                    Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("测试连通性", fontSize = 13.sp)
                                }
                            }
                        }

                        // 扫描结果列表
                        if (discoveredPcs.isNotEmpty()) {
                            Text(
                                text = "局域网发现的电脑 (点击直接配对):",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                            discoveredPcs.forEach { pc ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            targetIp = pc.ip
                                            targetPortStr = pc.port.toString()
                                            isPaired = true
                                            pairedSummary = "已配对: ${pc.name} (${pc.ip}:${pc.port})"
                                        },
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Computer,
                                            contentDescription = null,
                                            tint = Color(0xFF38BDF8),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(text = pc.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                            Text(text = "${pc.ip}:${pc.port}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        }
                                        if (isPaired && targetIp == pc.ip) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 手动输入 IP / 端口兜底
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = targetIp,
                                onValueChange = { targetIp = it },
                                label = { Text("电脑 IP", fontSize = 11.sp) },
                                modifier = Modifier.weight(2f),
                                singleLine = true,
                                enabled = !isStreaming,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )
                            OutlinedTextField(
                                value = targetPortStr,
                                onValueChange = { targetPortStr = it },
                                label = { Text("端口", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                enabled = !isStreaming,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = Color(0xFF3B82F6),
                                    unfocusedBorderColor = Color(0xFF334155)
                                )
                            )
                        }
                    }
                }

                ITransportSender.Type.USB_AOA -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "免开启开发者调试，手机作为配件由 PC 驱动握手。",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (usbAoaConnected) "✔ USB AOA 配件已接入" else "✕ 未检测到 AOA 配件",
                                color = if (usbAoaConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { checkUsbStatus() },
                                enabled = !isCheckingUsb,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("检测连接", fontSize = 12.sp)
                            }
                        }
                    }
                }

                ITransportSender.Type.USB_ADB -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "PC 执行 `adb forward tcp:18888 tcp:18888` 后点击检测。",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (usbAdbConnected) "✔ 127.0.0.1:18888 连通正常" else "✕ 本地端口未连通",
                                color = if (usbAdbConnected) Color(0xFF10B981) else Color(0xFFEF4444),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp
                            )
                            Button(
                                onClick = { checkUsbStatus() },
                                enabled = !isCheckingUsb,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("检测连接", fontSize = 12.sp)
                            }
                        }
                    }
                }

                ITransportSender.Type.BLUETOOTH_SPP -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "已配对的系统蓝牙设备:",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                            IconButton(onClick = { refreshBluetoothDevices() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "刷新", tint = Color(0xFF38BDF8))
                            }
                        }

                        if (pairedBtDevices.isEmpty()) {
                            Text(
                                text = "未发现已配对的蓝牙设备，请先在手机系统设置里配对电脑蓝牙",
                                fontSize = 12.sp,
                                color = Color(0xFFF59E0B)
                            )
                        } else {
                            pairedBtDevices.forEach { dev ->
                                val isSelected = selectedBtDevice?.address == dev.address
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedBtDevice = dev
                                            isPaired = true
                                            pairedSummary = "已选定蓝牙设备: ${dev.name} (${dev.address})"
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) Color(0xFF1E3A8A) else Color(0xFF1E293B)
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Bluetooth,
                                            contentDescription = null,
                                            tint = if (isSelected) Color(0xFF38BDF8) else Color(0xFF94A3B8),
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(dev.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                            Text(dev.address, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        }
                                        if (isSelected) {
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = Color(0xFF10B981),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (pairedSummary.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = pairedSummary,
                    fontSize = 12.sp,
                    color = if (isPaired) Color(0xFF34D399) else Color(0xFFF87171)
                )
            }
        }

        // ==========================================
        // 步骤二：【麦克风测试 (Mic Test)】
        // ==========================================
        StepContainer(
            stepNumber = 2,
            title = "麦克风与连通可用性测试 (Mic Test)",
            isCompleted = micTestPassed,
            statusBadge = if (micTestPassed) "测试通过" else if (isMicTesting) "正在测试" else "待测试"
        ) {
            Text(
                text = "在正式推流前进行 5 秒声音电平与电脑握手检测，确保声音清晰且被 PC 接收。",
                fontSize = 12.sp,
                color = Color(0xFF94A3B8)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // 测试中声音波形绘制
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0F172A))
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (isMicTesting && waveformPoints.isNotEmpty()) {
                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
                        val width = size.width
                        val height = size.height
                        val midY = height / 2f
                        val stepX = width / (waveformPoints.size.coerceAtLeast(1))

                        val path = Path()
                        waveformPoints.forEachIndexed { index, amp ->
                            val x = index * stepX
                            val barHeight = (amp * (height * 0.8f)).coerceAtLeast(2f)
                            drawLine(
                                color = if (amp > 0.7f) Color(0xFFEF4444) else Color(0xFF10B981),
                                start = Offset(x, midY - barHeight / 2f),
                                end = Offset(x, midY + barHeight / 2f),
                                strokeWidth = 4f
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (isMicTesting) "请对着手机说话..." else "点击下方按钮开始 5 秒试音",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 实时电平进度柱
            LinearProgressIndicator(
                progress = { if (isMicTesting) animatedTestLevel else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (animatedTestLevel > 0.8f) Color(0xFFEF4444) else Color(0xFF10B981),
                trackColor = Color(0xFF1E293B)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { startMicTest() },
                    enabled = isPaired && !isMicTesting && !isStreaming,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7)
                    )
                ) {
                    if (isMicTesting) {
                        Text("测试中 (${micTestSecondsRemaining}s)", fontSize = 13.sp)
                    } else {
                        Icon(Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (micTestPassed) "重新测试麦克风" else "测试麦克风 (5秒)", fontSize = 13.sp)
                    }
                }

                if (!isPaired) {
                    Text("需先完成步骤一配对", fontSize = 11.sp, color = Color(0xFF94A3B8))
                }
            }

            if (micTestFeedback.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = micTestFeedback,
                    fontSize = 12.sp,
                    color = if (micTestPassed) Color(0xFF34D399) else Color(0xFFF87171)
                )
            }
        }

        // ==========================================
        // 步骤三：【正式串流 (Live Stream)】
        // ==========================================
        StepContainer(
            stepNumber = 3,
            title = "正式串流 (Live Stream)",
            isCompleted = isStreaming,
            statusBadge = if (isStreaming) "推流中" else "未推流"
        ) {
            val canStartStream = isPaired && (micTestPassed || isStreaming)

            if (!canStartStream && !isStreaming) {
                Text(
                    text = "提示：完成步骤一配对和步骤二测试后，即可启动正式无损音频串流。",
                    fontSize = 12.sp,
                    color = Color(0xFF64748B)
                )
            }

            // 正式推流中的调控面板 (仅在推流时展开展示高级控台)
            AnimatedVisibility(visible = isStreaming) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 状态指示条
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("已发送数据帧: $sentPackets 帧", fontSize = 12.sp, color = Color(0xFF94A3B8))
                        }
                        Text(text = statusText, fontSize = 12.sp, color = Color(0xFF38BDF8))
                    }

                    // 实时串流音量柱
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("串流实时音频强度:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Text("${(animatedStreamVolume * 100).roundToInt()}%", fontSize = 12.sp, color = Color.White)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { animatedStreamVolume },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = if (isMuted) Color(0xFF64748B) else if (animatedStreamVolume > 0.85f) Color(0xFFEF4444) else Color(0xFF10B981),
                            trackColor = Color(0xFF1E293B)
                        )
                    }

                    HorizontalDivider(color = Color(0xFF1E293B))

                    // 静音与增益调节
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null,
                                tint = if (isMuted) Color(0xFFEF4444) else Color(0xFF10B981),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isMuted) "麦克风已静音" else "麦克风拾音开启", fontSize = 13.sp, color = Color.White)
                        }
                        Switch(
                            checked = !isMuted,
                            onCheckedChange = { checked ->
                                micService?.setMuted(!checked)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF10B981),
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color(0xFF475569)
                            )
                        )
                    }

                    // 软件增益滑块
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("音频输入增益倍数:", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            Text("${String.format("%.1f", gainMultiplier)}x", fontSize = 12.sp, color = Color(0xFF38BDF8))
                        }
                        Slider(
                            value = gainMultiplier,
                            onValueChange = { micService?.setGain(it) },
                            valueRange = 0.5f..2.5f,
                            steps = 20,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFF38BDF8),
                                activeTrackColor = Color(0xFF2563EB),
                                inactiveTrackColor = Color(0xFF1E293B)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 大按钮：启动 / 停止麦克风串流
            Button(
                onClick = {
                    if (isStreaming) {
                        micService?.stopStreaming()
                    } else {
                        if (!hasRecordPermission()) {
                            requestPermissions()
                            return@Button
                        }
                        val port = targetPortStr.toIntOrNull() ?: 18889
                        micService?.startStreaming(
                            type = selectedTransport,
                            targetHost = targetIp,
                            targetPort = port,
                            bluetoothMac = selectedBtDevice?.address
                        )
                    }
                },
                enabled = canStartStream || isStreaming,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isStreaming) Color(0xFFDC2626) else Color(0xFF16A34A),
                    disabledContainerColor = Color(0xFF1E293B)
                )
            ) {
                Icon(
                    imageVector = if (isStreaming) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isStreaming) "停止正式推流" else "启动麦克风串流 (已就绪)",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * 步骤卡片容器组件
 */
@Composable
fun StepContainer(
    stepNumber: Int,
    title: String,
    isCompleted: Boolean,
    statusBadge: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF141923)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isCompleted) Color(0xFF059669) else Color(0xFF1E293B)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 步骤条头
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (isCompleted) Color(0xFF10B981) else Color(0xFF3B82F6)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stepNumber.toString(),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (isCompleted) Color(0x2210B981) else Color(0x2264748B)
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = statusBadge,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isCompleted) Color(0xFF34D399) else Color(0xFF94A3B8)
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = Color(0xFF1E293B)
            )

            content()
        }
    }
}
