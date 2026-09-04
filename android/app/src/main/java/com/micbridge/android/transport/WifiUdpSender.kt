package com.micbridge.android.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFi UDP 高速极低延迟传输通道与局域网发现
 */
class WifiUdpSender(
    private val context: Context,
    var targetHost: String = "255.255.255.255",
    var targetPort: Int = 18889,
    private val listenPort: Int = 18888
) : ITransportSender {

    data class DiscoveredPc(
        val name: String,
        val ip: String,
        val port: Int
    )

    companion object {
        private const val TAG = "WifiUdpSender"
        const val DISCOVERY_MAGIC = "MICBRIDGE_DISCOVER"
        const val PC_BEACON_PREFIX = "MICBRIDGE_PC|"
        const val PING_MAGIC = "MICBRIDGE_PING"
        const val PONG_MAGIC = "MICBRIDGE_PONG"
        const val DISCOVERY_PORT = 18889

        /**
         * 静态辅助方法：向局域网广播发现 PC
         */
        suspend fun scanDevices(context: Context, timeoutMs: Int = 2000): List<DiscoveredPc> =
            withContext(Dispatchers.IO) {
                val results = mutableListOf<DiscoveredPc>()
                val seen = mutableSetOf<String>()
                var lock: WifiManager.MulticastLock? = null
                var scanSocket: DatagramSocket? = null

                try {
                    val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    lock = wm?.createMulticastLock("MicBridgeScanLock")?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }

                    scanSocket = DatagramSocket().apply {
                        broadcast = true
                        soTimeout = 400
                    }

                    val discoverBytes = DISCOVERY_MAGIC.toByteArray(Charsets.UTF_8)
                    val broadcastAddr = InetAddress.getByName("255.255.255.255")
                    val discoverPacket = DatagramPacket(discoverBytes, discoverBytes.size, broadcastAddr, DISCOVERY_PORT)

                    // 发送两次广播探测包
                    scanSocket.send(discoverPacket)
                    Thread.sleep(80)
                    scanSocket.send(discoverPacket)

                    val buffer = ByteArray(1024)
                    val recvPacket = DatagramPacket(buffer, buffer.size)
                    val startTime = System.currentTimeMillis()

                    while (System.currentTimeMillis() - startTime < timeoutMs) {
                        try {
                            scanSocket.receive(recvPacket)
                            val text = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8).trim()
                            val fromIp = recvPacket.address.hostAddress ?: continue

                            if (text.startsWith(PC_BEACON_PREFIX)) {
                                // 格式: MICBRIDGE_PC|电脑名|端口
                                val parts = text.split("|")
                                val pcName = if (parts.size >= 2 && parts[1].isNotEmpty()) parts[1] else "PC-$fromIp"
                                val pcPort = if (parts.size >= 3) parts[2].toIntOrNull() ?: DISCOVERY_PORT else DISCOVERY_PORT
                                val key = "$fromIp:$pcPort"
                                if (!seen.contains(key)) {
                                    seen.add(key)
                                    results.add(DiscoveredPc(name = pcName, ip = fromIp, port = pcPort))
                                }
                            }
                        } catch (e: SocketTimeoutException) {
                            // 单次接收超时继续循环，直到总时长到达
                        } catch (e: Exception) {
                            Log.w(TAG, "Error in scan loop: ${e.message}")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Scan failed: ${e.message}", e)
                } finally {
                    try {
                        scanSocket?.close()
                    } catch (_: Exception) {}
                    try {
                        if (lock?.isHeld == true) lock?.release()
                    } catch (_: Exception) {}
                }
                results
            }

        /**
         * 静态辅助方法：测试向指定 host:port 发送 PING 并等待 PONG
         */
        suspend fun pingTest(host: String, port: Int, timeoutMs: Int = 1500): Pair<Boolean, Long> =
            withContext(Dispatchers.IO) {
                var testSocket: DatagramSocket? = null
                try {
                    testSocket = DatagramSocket().apply {
                        soTimeout = timeoutMs
                    }
                    val pingBytes = PING_MAGIC.toByteArray(Charsets.UTF_8)
                    val targetAddr = InetAddress.getByName(host)
                    val pingPacket = DatagramPacket(pingBytes, pingBytes.size, targetAddr, port)

                    val startNs = System.nanoTime()
                    testSocket.send(pingPacket)

                    val buf = ByteArray(256)
                    val recvPacket = DatagramPacket(buf, buf.size)
                    testSocket.receive(recvPacket)
                    val rttMs = (System.nanoTime() - startNs) / 1_000_000
                    val reply = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8).trim()

                    if (reply.contains(PONG_MAGIC)) {
                        Pair(true, rttMs)
                    } else {
                        Pair(false, -1L)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Ping test failed to $host:$port : ${e.message}")
                    Pair(false, -1L)
                } finally {
                    try {
                        testSocket?.close()
                    } catch (_: Exception) {}
                }
            }
    }

    override val type: ITransportSender.Type = ITransportSender.Type.WIFI_UDP

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    private val isRunning = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // 单包 972 字节，队列长度 50 对应 500ms 缓冲，防止瞬时网络拥塞
    private val sendQueue = LinkedBlockingQueue<ByteArray>(50)

    private var sendThread: Thread? = null
    private var receiveThread: Thread? = null
    private var statusListener: ITransportSender.StatusListener? = null

    @Synchronized
    override fun start(listener: ITransportSender.StatusListener) {
        if (isRunning.get()) return
        this.statusListener = listener
        isRunning.set(true)

        try {
            // 获取 MulticastLock 允许接收局域网广播包
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifiManager?.createMulticastLock("MicBridgeMulticastLock")?.apply {
                setReferenceCounted(true)
                acquire()
            }

            socket = DatagramSocket(listenPort).apply {
                broadcast = true
                receiveBufferSize = 64 * 1024
                sendBufferSize = 64 * 1024
            }

            // 启动独立发送线程
            sendThread = Thread({ sendLoop() }, "WifiUdpSender-SendThread").apply {
                priority = Thread.NORM_PRIORITY + 2
                start()
            }

            // 启动独立接收线程 (监听 PC 端心跳回复与配对发现)
            receiveThread = Thread({ receiveLoop() }, "WifiUdpSender-RecvThread").apply {
                priority = Thread.NORM_PRIORITY
                start()
            }

            _isConnected = true
            statusListener?.onStatusChanged(true, "UDP 传输已就绪，正在向 $targetHost:$targetPort 发送...")
            Log.i(TAG, "WifiUdpSender started on port $listenPort, target $targetHost:$targetPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start WifiUdpSender", e)
            _isConnected = false
            statusListener?.onError("无法启动 UDP 套接字: ${e.message}")
            stop()
        }
    }

    fun setTarget(host: String, port: Int) {
        this.targetHost = host
        this.targetPort = port
        Log.i(TAG, "Updated UDP target to $targetHost:$targetPort")
    }

    override fun sendAudioPacket(packetData: ByteArray, offset: Int, length: Int) {
        if (!isRunning.get()) return
        val copy = ByteArray(length)
        System.arraycopy(packetData, offset, copy, 0, length)
        // 非阻塞入队，队列满时舍弃最旧数据包，优先保证超低实时延迟
        if (!sendQueue.offer(copy)) {
            sendQueue.poll()
            sendQueue.offer(copy)
        }
    }

    private fun sendLoop() {
        while (isRunning.get()) {
            try {
                val packet = sendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val curSocket = socket ?: break
                val targetAddr = InetAddress.getByName(targetHost)
                val datagram = DatagramPacket(packet, packet.size, targetAddr, targetPort)
                curSocket.send(datagram)
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "Error sending UDP packet: ${e.message}")
                }
            }
        }
    }

    private fun receiveLoop() {
        val recvBuf = ByteArray(1024)
        while (isRunning.get()) {
            try {
                val curSocket = socket ?: break
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                curSocket.receive(recvPacket)

                val message = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8).trim()
                if (message.startsWith(PONG_MAGIC) || message.startsWith(PC_BEACON_PREFIX) || message.startsWith(DISCOVERY_MAGIC)) {
                    // 动态自适应 PC 端 IP 与端口
                    val senderIp = recvPacket.address.hostAddress ?: continue
                    val senderPort = recvPacket.port
                    this.targetHost = senderIp
                    this.targetPort = senderPort
                    _isConnected = true
                    statusListener?.onStatusChanged(true, "已连接 PC: $senderIp:$senderPort")
                    Log.i(TAG, "Discovered PC host from packet: $senderIp:$senderPort")
                }
            } catch (e: Exception) {
                if (!isRunning.get()) break
            }
        }
    }

    @Synchronized
    override fun stop() {
        isRunning.set(false)
        _isConnected = false
        sendThread?.interrupt()
        receiveThread?.interrupt()
        sendThread = null
        receiveThread = null

        try {
            socket?.close()
        } catch (_: Exception) {}
        socket = null

        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (_: Exception) {}
        multicastLock = null

        sendQueue.clear()
        statusListener?.onStatusChanged(false, "UDP 传输已停止")
        Log.i(TAG, "WifiUdpSender stopped")
    }
}
