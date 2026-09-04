using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Threading;
using System.Threading.Tasks;
using MicBridge.PC.Audio;

namespace MicBridge.PC.Transports
{
    /// <summary>
    /// USB ADB & AOA Transport Receiver.
    /// Supports ADB reverse / forward tcp:18888 or direct USB stream.
    /// Provides zero-latency, rock-solid physical cable connection.
    /// </summary>
    public class UsbReceiver : IAudioReceiver
    {
        private readonly int _port;
        private TcpListener? _tcpListener;
        private CancellationTokenSource? _cts;

        private long _packetCounter = 0;
        private long _byteCounter = 0;
        private long _lastPps = 0;
        private long _lastBps = 0;
        private System.Timers.Timer? _statsTimer;

        public string TransportName => "USB (ADB Forward / AOA TCP)";
        public bool IsRunning => _cts != null && !_cts.IsCancellationRequested;
        public long PacketsReceivedPerSec => _lastPps;
        public long BytesReceivedPerSec => _lastBps;

        public event Action<AudioPacket>? OnAudioPacketReceived;
        public event Action<string>? OnLog;

        public UsbReceiver(int port = 18888)
        {
            _port = port;
        }

        public void Start()
        {
            if (IsRunning) return;

            _cts = new CancellationTokenSource();
            _tcpListener = new TcpListener(IPAddress.Loopback, _port);
            _tcpListener.Server.NoDelay = true; // Disable Nagle's algorithm for minimal latency
            _tcpListener.Start();

            Task.Run(() => AcceptClientsLoop(_cts.Token));

            _statsTimer = new System.Timers.Timer(1000);
            _statsTimer.Elapsed += (_, _) =>
            {
                _lastPps = Interlocked.Exchange(ref _packetCounter, 0);
                _lastBps = Interlocked.Exchange(ref _byteCounter, 0);
            };
            _statsTimer.Start();

            OnLog?.Invoke($"[USB] Listening on 127.0.0.1:{_port} for ADB forward/reverse connections.");
        }

        private async Task AcceptClientsLoop(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested && _tcpListener != null)
            {
                try
                {
                    var client = await _tcpListener.AcceptTcpClientAsync(ct);
                    client.NoDelay = true;
                    OnLog?.Invoke($"[USB] Android phone connected via USB (ADB) from {client.Client.RemoteEndPoint}");
                    _ = Task.Run(() => HandleClientStream(client, ct));
                }
                catch (OperationCanceledException) { break; }
                catch (Exception ex)
                {
                    if (!ct.IsCancellationRequested)
                    {
                        OnLog?.Invoke($"[USB] Connection accept error: {ex.Message}");
                    }
                }
            }
        }

        private async Task HandleClientStream(TcpClient client, CancellationToken ct)
        {
            using (client)
            using (var stream = client.GetStream())
            {
                byte[] headerBuffer = new byte[AudioFrameHeader.HeaderSize];
                byte[] lengthBuffer = new byte[4];

                while (!ct.IsCancellationRequested && client.Connected)
                {
                    try
                    {
                        // 1. Read Payload length (4 bytes)
                        if (!await ReadExactAsync(stream, lengthBuffer, 0, 4, ct)) break;
                        int packetLength = BitConverter.ToInt32(lengthBuffer, 0);

                        if (packetLength <= 0 || packetLength > 65536)
                        {
                            OnLog?.Invoke($"[USB] Abnormal packet size: {packetLength}, closing connection.");
                            break;
                        }

                        // 2. Read full packet (Header + PCM)
                        byte[] packetBuffer = new byte[packetLength];
                        if (!await ReadExactAsync(stream, packetBuffer, 0, packetLength, ct)) break;

                        Interlocked.Increment(ref _packetCounter);
                        Interlocked.Add(ref _byteCounter, packetLength);

                        if (AudioFrameHeader.TryParse(packetBuffer, 0, packetLength, out var header))
                        {
                            int pcmSize = packetLength - AudioFrameHeader.HeaderSize;
                            byte[] pcm = new byte[pcmSize];
                            Buffer.BlockCopy(packetBuffer, AudioFrameHeader.HeaderSize, pcm, 0, pcmSize);

                            var packet = new AudioPacket
                            {
                                Header = header,
                                PcmData = pcm,
                                ReceivedTimeTicks = DateTime.UtcNow.Ticks
                            };

                            OnAudioPacketReceived?.Invoke(packet);
                        }
                    }
                    catch (Exception ex)
                    {
                        if (!ct.IsCancellationRequested)
                        {
                            OnLog?.Invoke($"[USB] Stream error: {ex.Message}");
                        }
                        break;
                    }
                }
            }
            OnLog?.Invoke("[USB] Phone disconnected from USB stream.");
        }

        private static async Task<bool> ReadExactAsync(NetworkStream stream, byte[] buffer, int offset, int count, CancellationToken ct)
        {
            int totalRead = 0;
            while (totalRead < count)
            {
                int read = await stream.ReadAsync(buffer.AsMemory(offset + totalRead, count - totalRead), ct);
                if (read == 0) return false;
                totalRead += read;
            }
            return true;
        }

        public void Stop()
        {
            _cts?.Cancel();
            _statsTimer?.Stop();
            _statsTimer?.Dispose();

            try { _tcpListener?.Stop(); } catch { }
            _tcpListener = null;
            OnLog?.Invoke("[USB] Stopped.");
        }

        public void Dispose()
        {
            Stop();
            _cts?.Dispose();
        }
    }
}
