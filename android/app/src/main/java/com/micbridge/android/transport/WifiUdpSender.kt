package com.micbridge.android.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WiFi UDP 高速极低延迟传输通道
 * 
 * 核心特性：
 * 1. 发送队列解耦采集线程与网络发送线程，保证 10ms 帧准时消费不阻塞 AudioRecord
 * 2. 支持 UDP 广播/组播自动发现 PC 端
 * 3. 监听 PC 端心跳包，自动锁定目标 PC 的 IP 与数据端口
 */
class WifiUdpSender(
    private val context: Context,
    private var targetHost: String = "255.255.255.255",
    private var targetPort: Int = 18889,
    private val listenPort: Int = 18888
) : ITransportSender {

    companion object {
        private const val TAG = "WifiUdpSender"
        private const val DISCOVERY_MAGIC = "MICBRIDGE_DISCOVER"
        private const val ACK_MAGIC = "MICBRIDGE_ACK"
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
                if (message.startsWith(ACK_MAGIC) || message.startsWith(DISCOVERY_MAGIC)) {
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
