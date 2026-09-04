using System;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MicBridge.PC.Audio;

namespace MicBridge.PC.Transports
{
    /// <summary>
    /// High-throughput, low-latency UDP receiver for LAN Wi-Fi transmission.
    /// Also provides UDP Broadcast beacon / Discovery responder on port 18888 so Android devices can instantly auto-discover PC.
    /// </summary>
    public class WifiUdpReceiver : IAudioReceiver
    {
        private readonly int _port;
        private UdpClient? _udpClient;
        private UdpClient? _discoveryClient;
        private CancellationTokenSource? _cts;

        private long _packetCounter = 0;
        private long _byteCounter = 0;
        private long _lastPps = 0;
        private long _lastBps = 0;
        private System.Timers.Timer? _statsTimer;

        public string TransportName => "Wi-Fi (UDP / Discovery)";
        public bool IsRunning => _cts != null && !_cts.IsCancellationRequested;
        public long PacketsReceivedPerSec => _lastPps;
        public long BytesReceivedPerSec => _lastBps;

        public event Action<AudioPacket>? OnAudioPacketReceived;
        public event Action<string>? OnLog;

        public WifiUdpReceiver(int port = 18888)
        {
            _port = port;
        }

        public void Start()
        {
            if (IsRunning) return;

            _cts = new CancellationTokenSource();

            // 1. Audio UDP socket
            _udpClient = new UdpClient();
            _udpClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
            // Boost socket receive buffer to 2MB to prevent OS-level drops
            _udpClient.Client.ReceiveBufferSize = 2 * 1024 * 1024;
            _udpClient.Client.Bind(new IPEndPoint(IPAddress.Any, _port));

            // 2. Discovery broadcast listener on 18889
            try
            {
                _discoveryClient = new UdpClient();
                _discoveryClient.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
                _discoveryClient.Client.Bind(new IPEndPoint(IPAddress.Any, _port + 1));
                Task.Run(() => DiscoveryLoop(_cts.Token));
            }
            catch (Exception ex)
            {
                OnLog?.Invoke($"[Wi-Fi] Discovery socket bind warning: {ex.Message}");
            }

            // 3. Audio receive loop
            Task.Run(() => ReceiveLoop(_cts.Token));

            // Stats timer
            _statsTimer = new System.Timers.Timer(1000);
            _statsTimer.Elapsed += (_, _) =>
            {
                _lastPps = Interlocked.Exchange(ref _packetCounter, 0);
                _lastBps = Interlocked.Exchange(ref _byteCounter, 0);
            };
            _statsTimer.Start();

            OnLog?.Invoke($"[Wi-Fi] Listening for UDP audio packets on port {_port}, auto-discovery on {_port + 1}");
        }

        private async Task ReceiveLoop(CancellationToken ct)
        {
            var remoteEndpointsSeen = new HashSet<string>();

            while (!ct.IsCancellationRequested && _udpClient != null)
            {
                try
                {
                    var result = await _udpClient.ReceiveAsync(ct);
                    byte[] data = result.Buffer;
                    Interlocked.Increment(ref _packetCounter);
                    Interlocked.Add(ref _byteCounter, data.Length);

                    // Check if it's a PING test packet directly on audio port
                    if (data.Length >= 14)
                    {
                        string text = Encoding.UTF8.GetString(data);
                        if (text.StartsWith("MICBRIDGE_PING"))
                        {
                            byte[] pongResponse = Encoding.UTF8.GetBytes("MICBRIDGE_PONG");
                            await _udpClient.SendAsync(pongResponse, pongResponse.Length, result.RemoteEndPoint);
                            OnLog?.Invoke($"[配对成功] 收到来自手机 ({result.RemoteEndPoint.Address}) 的测试信号/音频信号，麦克风可用！");
                            continue;
                        }
                    }

                    if (AudioFrameHeader.TryParse(data, 0, data.Length, out var header))
                    {
                        string clientIp = result.RemoteEndPoint.Address.ToString();
                        if (remoteEndpointsSeen.Add(clientIp))
                        {
                            OnLog?.Invoke($"[配对成功] 收到来自手机 ({clientIp}) 的测试信号/音频信号，麦克风可用！");
                        }

                        int payloadSize = data.Length - AudioFrameHeader.HeaderSize;
                        byte[] pcm = new byte[payloadSize];
                        Buffer.BlockCopy(data, AudioFrameHeader.HeaderSize, pcm, 0, payloadSize);

                        var packet = new AudioPacket
                        {
                            Header = header,
                            PcmData = pcm,
                            ReceivedTimeTicks = DateTime.UtcNow.Ticks
                        };

                        OnAudioPacketReceived?.Invoke(packet);
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    if (!ct.IsCancellationRequested)
                    {
                        OnLog?.Invoke($"[Wi-Fi] Receive error: {ex.Message}");
                    }
                }
            }
        }

        private async Task DiscoveryLoop(CancellationToken ct)
        {
            byte[] beaconResponse = Encoding.UTF8.GetBytes($"MICBRIDGE_PC|{Environment.MachineName}|{_port}");
            byte[] pongResponse = Encoding.UTF8.GetBytes("MICBRIDGE_PONG");

            while (!ct.IsCancellationRequested && _discoveryClient != null)
            {
                try
                {
                    var result = await _discoveryClient.ReceiveAsync(ct);
                    string query = Encoding.UTF8.GetString(result.Buffer);
                    if (query.Contains("MICBRIDGE_DISCOVER"))
                    {
                        // Reply back to sender with our port & hostname
                        await _discoveryClient.SendAsync(beaconResponse, beaconResponse.Length, result.RemoteEndPoint);
                        OnLog?.Invoke($"[Wi-Fi] Responded to phone discovery request from {result.RemoteEndPoint}");
                    }
                    else if (query.Contains("MICBRIDGE_PING"))
                    {
                        // Respond to PING connectivity test
                        await _discoveryClient.SendAsync(pongResponse, pongResponse.Length, result.RemoteEndPoint);
                        OnLog?.Invoke($"[配对成功] 收到来自手机 ({result.RemoteEndPoint.Address}) 的测试信号/音频信号，麦克风可用！");
                    }
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    if (!ct.IsCancellationRequested)
                    {
                        OnLog?.Invoke($"[Wi-Fi] Discovery loop error: {ex.Message}");
                    }
                }
            }
        }

        public void Stop()
        {
            _cts?.Cancel();
            _statsTimer?.Stop();
            _statsTimer?.Dispose();

            try { _udpClient?.Close(); } catch { }
            try { _discoveryClient?.Close(); } catch { }

            _udpClient = null;
            _discoveryClient = null;
            OnLog?.Invoke("[Wi-Fi] Stopped.");
        }

        public void Dispose()
        {
            Stop();
            _cts?.Dispose();
        }
    }
}
