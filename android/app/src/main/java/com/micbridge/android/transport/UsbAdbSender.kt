package com.micbridge.android.transport

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB ADB 端口转发模式传输通道
 * 
 * 工作原理：
 * 1. 手机端启动本地 TCP Server 监听 127.0.0.1:18888
 * 2. PC 端执行 `adb forward tcp:18888 tcp:18888`
 * 3. PC 端客户端直接连接本地 127.0.0.1:18888 即可建立极速稳定的 USB 硬件流管道
 */
class UsbAdbSender(
    private val port: Int = 18888
) : ITransportSender {

    companion object {
        private const val TAG = "UsbAdbSender"
    }

    override val type: ITransportSender.Type = ITransportSender.Type.USB_ADB

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var outputStream: FileOutputStream? = null

    private val sendQueue = LinkedBlockingQueue<ByteArray>(50)
    private var acceptThread: Thread? = null
    private var sendThread: Thread? = null
    private var statusListener: ITransportSender.StatusListener? = null

    @Synchronized
    override fun start(listener: ITransportSender.StatusListener) {
        if (isRunning.get()) return
        this.statusListener = listener
        isRunning.set(true)

        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", port))
            }

            statusListener?.onStatusChanged(false, "等待 ADB 连接 (端口 $port)...")
            Log.i(TAG, "UsbAdbSender listening on 127.0.0.1:$port")

            acceptThread = Thread({ acceptLoop() }, "UsbAdb-AcceptThread").apply {
                start()
            }

            sendThread = Thread({ sendLoop() }, "UsbAdb-SendThread").apply {
                priority = Thread.NORM_PRIORITY + 2
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start UsbAdbServer", e)
            statusListener?.onError("ADB 本地服务启动失败: ${e.message}")
            stop()
        }
    }

    private fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val server = serverSocket ?: break
                val socket = server.accept().apply {
                    tcpNoDelay = true
                    sendBufferSize = 64 * 1024
                }

                synchronized(this) {
                    clientSocket?.close()
                    clientSocket = socket
                    outputStream = socket.getOutputStream() as? FileOutputStream ?: FileOutputStream(socket.getOutputStream().toString())
                    _isConnected = true
                }

                statusListener?.onStatusChanged(true, "PC ADB 已连接成功")
                Log.i(TAG, "ADB Client connected from ${socket.remoteSocketAddress}")

                // 阻塞保持，监听对方断开
                val inputStream = socket.getInputStream()
                val testBuf = ByteArray(16)
                while (isRunning.get() && clientSocket != null) {
                    val read = inputStream.read(testBuf)
                    if (read == -1) break
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "ADB client disconnected or error: ${e.message}")
                }
            } finally {
                synchronized(this) {
                    clientSocket?.close()
                    clientSocket = null
                    outputStream = null
                    _isConnected = false
                }
                if (isRunning.get()) {
                    statusListener?.onStatusChanged(false, "PC 客户端已断开，等待重连...")
                }
            }
        }
    }

    override fun sendAudioPacket(packetData: ByteArray, offset: Int, length: Int) {
        if (!isRunning.get() || !_isConnected) return
        val copy = ByteArray(length)
        System.arraycopy(packetData, offset, copy, 0, length)
        if (!sendQueue.offer(copy)) {
            sendQueue.poll()
            sendQueue.offer(copy)
        }
    }

    private fun sendLoop() {
        while (isRunning.get()) {
            try {
                val packet = sendQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val out = synchronized(this) { outputStream ?: clientSocket?.getOutputStream() }
                if (out != null && _isConnected) {
                    out.write(packet)
                    out.flush()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "Write error in UsbAdbSender: ${e.message}")
                _isConnected = false
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected send error: ${e.message}")
            }
        }
    }

    @Synchronized
    override fun stop() {
        isRunning.set(false)
        _isConnected = false
        acceptThread?.interrupt()
        sendThread?.interrupt()
        acceptThread = null
        sendThread = null

        try {
            clientSocket?.close()
        } catch (_: Exception) {}
        clientSocket = null
        outputStream = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        sendQueue.clear()
        statusListener?.onStatusChanged(false, "ADB 传输服务已停止")
        Log.i(TAG, "UsbAdbSender stopped")
    }
}
