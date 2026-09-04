package com.micbridge.android.transport

import android.content.Context
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB AOA 2.0 (Android Open Accessory) 免调试极低延迟物理传输通道
 * 
 * 特性：
 * - 手机作为 USB Device，PC 作为 USB Host 握手激活 AOA 模式
 * - 手机完全无需开启 ADB 开发者选项与 USB 调试
 * - 基于底层文件描述符 FileOutputStream，极低传输延迟（< 1ms）
 */
class UsbAoaSender(
    private val context: Context
) : ITransportSender {

    companion object {
        private const val TAG = "UsbAoaSender"
    }

    override val type: ITransportSender.Type = ITransportSender.Type.USB_AOA

    @Volatile
    private var _isConnected = false
    override val isConnected: Boolean get() = _isConnected

    private val isRunning = AtomicBoolean(false)
    private var usbManager: UsbManager? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var outputStream: FileOutputStream? = null
    private var inputStream: FileInputStream? = null

    private val sendQueue = LinkedBlockingQueue<ByteArray>(50)
    private var sendThread: Thread? = null
    private var readThread: Thread? = null
    private var statusListener: ITransportSender.StatusListener? = null

    @Synchronized
    override fun start(listener: ITransportSender.StatusListener) {
        if (isRunning.get()) return
        this.statusListener = listener
        isRunning.set(true)

        usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val accessoryList = usbManager?.accessoryList

        if (accessoryList.isNullOrEmpty()) {
            _isConnected = false
            statusListener?.onStatusChanged(false, "未检测到 USB AOA 主机配件，请插入电脑并启动 MicBridge PC 端")
            Log.w(TAG, "No USB accessories found")
            return
        }

        val accessory = accessoryList[0]
        openAccessory(accessory)
    }

    /**
     * 当收到系统 USB_ACCESSORY_ATTACHED 广播或手动发现时调用
     */
    @Synchronized
    fun onAccessoryAttached(accessory: UsbAccessory) {
        if (isRunning.get() && !_isConnected) {
            openAccessory(accessory)
        }
    }

    private fun openAccessory(accessory: UsbAccessory) {
        try {
            fileDescriptor = usbManager?.openAccessory(accessory)
            if (fileDescriptor != null) {
                val fd: FileDescriptor = fileDescriptor!!.fileDescriptor
                outputStream = FileOutputStream(fd)
                inputStream = FileInputStream(fd)
                _isConnected = true

                statusListener?.onStatusChanged(true, "USB AOA 握手成功: ${accessory.description}")
                Log.i(TAG, "USB Accessory opened: ${accessory.manufacturer} - ${accessory.model}")

                sendThread = Thread({ sendLoop() }, "UsbAoa-SendThread").apply {
                    priority = Thread.NORM_PRIORITY + 2
                    start()
                }

                readThread = Thread({ readLoop() }, "UsbAoa-ReadThread").apply {
                    priority = Thread.NORM_PRIORITY
                    start()
                }
            } else {
                _isConnected = false
                statusListener?.onError("无法打开 USB AOA 文件描述符（可能缺少权限）")
                Log.e(TAG, "openAccessory returned null")
            }
        } catch (e: Exception) {
            _isConnected = false
            statusListener?.onError("打开 USB 配件失败: ${e.message}")
            Log.e(TAG, "Error opening accessory", e)
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
                val out = outputStream ?: break
                out.write(packet)
                out.flush()
            } catch (e: InterruptedException) {
                break
            } catch (e: IOException) {
                Log.w(TAG, "USB AOA write failed: ${e.message}")
                _isConnected = false
                statusListener?.onStatusChanged(false, "USB AOA 连接中断")
                break
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error in USB AOA sendLoop: ${e.message}")
            }
        }
    }

    private fun readLoop() {
        val buffer = ByteArray(256)
        while (isRunning.get()) {
            try {
                val ins = inputStream ?: break
                val read = ins.read(buffer)
                if (read < 0) {
                    _isConnected = false
                    statusListener?.onStatusChanged(false, "USB AOA 主机断开")
                    break
                }
            } catch (e: IOException) {
                if (isRunning.get()) {
                    Log.w(TAG, "USB AOA read exception: ${e.message}")
                    _isConnected = false
                }
                break
            }
        }
    }

    @Synchronized
    override fun stop() {
        isRunning.set(false)
        _isConnected = false
        sendThread?.interrupt()
        readThread?.interrupt()
        sendThread = null
        readThread = null

        try {
            outputStream?.close()
        } catch (_: Exception) {}
        outputStream = null

        try {
            inputStream?.close()
        } catch (_: Exception) {}
        inputStream = null

        try {
            fileDescriptor?.close()
        } catch (_: Exception) {}
        fileDescriptor = null

        sendQueue.clear()
        statusListener?.onStatusChanged(false, "USB AOA 通道已关闭")
        Log.i(TAG, "UsbAoaSender stopped")
    }
}
