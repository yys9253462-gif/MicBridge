package com.micbridge.android.transport

/**
 * 传输通道通用抽象接口
 */
interface ITransportSender {
    /**
     * 传输通道类型定义
     */
    enum class Type(val displayName: String) {
        WIFI_UDP("WiFi UDP (局域网直连)"),
        USB_AOA("USB AOA 2.0 (免调试配件模式)"),
        USB_ADB("USB ADB (本地端口转发 127.0.0.1:18888)"),
        BLUETOOTH_SPP("蓝牙 (经典蓝牙 SPP 串口)")
    }

    /**
     * 连接监听状态回调
     */
    interface StatusListener {
        fun onStatusChanged(connected: Boolean, message: String)
        fun onError(error: String)
    }

    val type: Type
    val isConnected: Boolean

    /**
     * 启动通道并建立连接/监听
     */
    fun start(listener: StatusListener)

    /**
     * 发送音频数据帧
     * @param packetData 完整字节数组 (包含 12 字节头部 + 960 字节音频)
     * @param offset 偏移
     * @param length 数据包长度 (972 字节)
     */
    fun sendAudioPacket(packetData: ByteArray, offset: Int, length: Int)

    /**
     * 关闭释放通道资源
     */
    fun stop()
}
