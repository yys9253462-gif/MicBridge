using System;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using MicBridge.PC.Audio;

namespace MicBridge.PC.Transports
{
    /// <summary>
    /// Bluetooth RFCOMM Serial Port Profile (SPP) Receiver based on 32feet.NET WinSock.
    /// UUID matches the Android MicBridge RFCOMM service.
    /// </summary>
    public class BluetoothReceiver : IAudioReceiver
    {
        // Standard custom Serial Port UUID for MicBridge
        public static readonly Guid MicBridgeServiceUuid = new("a0e44b4e-48a6-4be4-a745-7ec532f814b7");

        private BluetoothListener? _btListener;
        private CancellationTokenSource? _cts;

        private long _packetCounter = 0;
        private long _byteCounter = 0;
        private long _lastPps = 0;
        private long _lastBps = 0;
        private System.Timers.Timer? _statsTimer;

        public string TransportName => "Bluetooth (RFCOMM SPP)";
        public bool IsRunning => _cts != null && !_cts.IsCancellationRequested;
        public long PacketsReceivedPerSec => _lastPps;
        public long BytesReceivedPerSec => _lastBps;

        public event Action<AudioPacket>? OnAudioPacketReceived;
        public event Action<string>? OnLog;

        public void Start()
        {
            if (IsRunning) return;

            try
            {
                _cts = new CancellationTokenSource();
                _btListener = new BluetoothListener(MicBridgeServiceUuid)
                {
                    ServiceName = "MicBridge Audio Stream"
                };
                _btListener.Start();

                Task.Run(() => AcceptBluetoothClientsLoop(_cts.Token));

                _statsTimer = new System.Timers.Timer(1000);
                _statsTimer.Elapsed += (_, _) =>
                {
                    _lastPps = Interlocked.Exchange(ref _packetCounter, 0);
                    _lastBps = Interlocked.Exchange(ref _byteCounter, 0);
                };
                _statsTimer.Start();

                OnLog?.Invoke("[Bluetooth] RFCOMM SPP service listening. Ready for phone Bluetooth pairing.");
            }
            catch (Exception ex)
            {
                OnLog?.Invoke($"[Bluetooth] Bluetooth initialization warning (adapter missing or disabled?): {ex.Message}");
            }
        }

        private async Task AcceptBluetoothClientsLoop(CancellationToken ct)
        {
            while (!ct.IsCancellationRequested && _btListener != null)
            {
                try
                {
                    var client = _btListener.AcceptBluetoothClient();
                    OnLog?.Invoke($"[Bluetooth] Phone connected via Bluetooth: {client.RemoteMachineName}");
                    _ = Task.Run(() => HandleBluetoothStream(client, ct));
                }
                catch (Exception ex)
                {
                    if (!ct.IsCancellationRequested)
                    {
                        OnLog?.Invoke($"[Bluetooth] Accept error: {ex.Message}");
                        await Task.Delay(1000, ct);
                    }
                }
            }
        }

        private async Task HandleBluetoothStream(BluetoothClient client, CancellationToken ct)
        {
            using (client)
            using (var stream = client.GetStream())
            {
                byte[] lengthBuffer = new byte[4];

                while (!ct.IsCancellationRequested && client.Connected)
                {
                    try
                    {
                        if (!await ReadExactAsync(stream, lengthBuffer, 0, 4, ct)) break;
                        int packetLength = BitConverter.ToInt32(lengthBuffer, 0);

                        if (packetLength <= 0 || packetLength > 65536) break;

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
                            OnLog?.Invoke($"[Bluetooth] Connection stream terminated: {ex.Message}");
                        }
                        break;
                    }
                }
            }
            OnLog?.Invoke("[Bluetooth] Phone disconnected from Bluetooth.");
        }

        private static async Task<bool> ReadExactAsync(Stream stream, byte[] buffer, int offset, int count, CancellationToken ct)
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

            try { _btListener?.Stop(); } catch { }
            _btListener = null;
            OnLog?.Invoke("[Bluetooth] Stopped.");
        }

        public void Dispose()
        {
            Stop();
            _cts?.Dispose();
        }
    }
}
