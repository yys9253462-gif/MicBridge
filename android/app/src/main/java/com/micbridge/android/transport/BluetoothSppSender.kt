package com.micbridge.android.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 经典蓝牙 RFCOMM (SPP Serial Port Profile) 串口流传输通道
 * 
 * 标准 UUID: 00001101-0000-1000-8000-00805F9B34FB
 */
class BluetoothSppSender(
    private val context: Context,
    private val targetDeviceAddress: String? = null
) : ITransportSender {

    companion object {
        private const val TAG = "BluetoothSppSender"
        // 经典蓝牙 SPP 标准 UUID
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SERVICE_NAME = "MicBridgeAudioSPP"
    }

    override val type: ITransportSender.Type = ITransportSender.Type.BLUETOOTH_SPP

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    private val isRunning = AtomicBoolean(false)
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null

    private val sendQueue = LinkedBlockingQueue<ByteArray>(50)
    private var connectThread: Thread? = null
    private var sendThread: Thread? = null
    private var statusListener: ITransportSender.StatusListener? = null

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun start(listener: ITransportSender.StatusListener) {
        if (isRunning.get()) return
        this.statusListener = listener
        isRunning.set(true)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            _isConnected = false
            statusListener?.onError("蓝牙未开启或设备不支持蓝牙")
            stop()
            return
        }

        connectThread = Thread({
            if (targetDeviceAddress.isNullOrEmpty()) {
                // 作为 SPP Server 监听来自电脑的蓝牙串口连接
                runSppServer()
            } else {
                // 作为 SPP Client 主动连接电脑蓝牙
                runSppClient(targetDeviceAddress)
            }
        }, "BluetoothSpp-ConnectThread").apply {
            start()
        }

        sendThread = Thread({ sendLoop() }, "BluetoothSpp-SendThread").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSppServer() {
        try {
            statusListener?.onStatusChanged(false, "蓝牙 SPP 服务启动，等待电脑配对连接...")
            serverSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
            val socket = serverSocket?.accept() ?: return

            synchronized(this) {
                clientSocket = socket
                outputStream = socket.outputStream
                _isConnected = true
            }

            statusListener?.onStatusChanged(true, "蓝牙已连接: ${socket.remoteDevice?.name ?: socket.remoteDevice?.address}")
            Log.i(TAG, "Bluetooth RFCOMM connected to ${socket.remoteDevice?.address}")

            // 监听断开
            val ins = socket.inputStream
            val buf = ByteArray(16)
            while (isRunning.get() && clientSocket != null) {
                if (ins.read(buf) == -1) break
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.w(TAG, "Bluetooth server connection error: ${e.message}")
            }
        } finally {
            disconnectClient()
            if (isRunning.get()) {
                statusListener?.onStatusChanged(false, "蓝牙连接已断开，重新监听中...")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun runSppClient(address: String) {
        try {
            statusListener?.onStatusChanged(false, "正在连接电脑蓝牙 ($address)...")
            val device: BluetoothDevice = bluetoothAdapter?.getRemoteDevice(address) ?: return
            val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothAdapter?.cancelDiscovery()
            socket.connect()

            synchronized(this) {
                clientSocket = socket
                outputStream = socket.outputStream
                _isConnected = true
            }

            statusListener?.onStatusChanged(true, "已连上电脑蓝牙: ${device.name ?: address}")
            Log.i(TAG, "Successfully connected to Bluetooth device $address")

            val ins = socket.inputStream
            val buf = ByteArray(16)
            while (isRunning.get() && clientSocket != null) {
                if (ins.read(buf) == -1) break
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "Bluetooth client connection failed: ${e.message}")
                statusListener?.onError("蓝牙连接失败: ${e.message}")
            }
        } finally {
            disconnectClient()
        }
    }

    private fun disconnectClient() {
        synchronized(this) {
            try {
                clientSocket?.close()
            } catch (_: Exception) {}
            clientSocket = null
            outputStream = null
            _isConnected = false
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
                val out = synchronized(this) { outputStream }
                if (out != null && _isConnected) {
                    out.write(packet)
                    out.flush()
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "Bluetooth send error: ${e.message}")
                _isConnected = false
                break
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error in Bluetooth sendLoop: ${e.message}")
            }
        }
    }

    @Synchronized
    override fun stop() {
        isRunning.set(false)
        _isConnected = false
        connectThread?.interrupt()
        sendThread?.interrupt()
        connectThread = null
        sendThread = null

        disconnectClient()

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        sendQueue.clear()
        statusListener?.onStatusChanged(false, "蓝牙传输已停止")
        Log.i(TAG, "BluetoothSppSender stopped")
    }
}
