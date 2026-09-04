using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using MicBridge.PC.Audio;
using MicBridge.PC.Transports;

namespace MicBridge.PC
{
    internal class Program
    {
        private static AudioInjector? _injector;
        private static readonly List<IAudioReceiver> _receivers = new();
        private static readonly DriverManager _driverManager = new();
        private static readonly Queue<string> _recentLogs = new();
        private static readonly object _logLock = new();

        static async Task Main(string[] args)
        {
            Console.OutputEncoding = Encoding.UTF8;
            Console.Title = "MicBridge PC - Smartphone as High-Quality Windows Mic";

            Log("[Core] Starting MicBridge PC Audio Engine (.NET 8.0 WASAPI)...");

            // 1. Device check
            _driverManager.EnsureVirtualDriverReady();
            var targetDevice = _driverManager.FindVirtualCableInputDevice();

            if (targetDevice == null)
            {
                Log("[Core] ERROR: Could not find any audio render device!");
                return;
            }

            Log($"[Core] Binding WASAPI Audio Injector to: [{targetDevice.FriendlyName}]");
            _injector = new AudioInjector(targetDevice, sampleRate: 48000, channels: 1);
            _injector.Start();

            // 2. Transports setup
            var wifiReceiver = new WifiUdpReceiver(18888);
            var usbReceiver = new UsbReceiver(18888);
            var btReceiver = new BluetoothReceiver();

            RegisterReceiver(wifiReceiver);
            RegisterReceiver(usbReceiver);
            RegisterReceiver(btReceiver);

            foreach (var r in _receivers)
            {
                r.Start();
            }

            Log("[Core] All transports listening. Dashboard is live. Press Ctrl+C to exit.");

            // 3. Start Dashboard loop
            var cts = new CancellationTokenSource();
            Console.CancelKeyPress += (_, e) =>
            {
                e.Cancel = true;
                cts.Cancel();
            };

            await RunDashboardLoop(cts.Token);

            // Shutdown
            Log("[Core] Shutting down services...");
            foreach (var r in _receivers)
            {
                r.Stop();
                r.Dispose();
            }
            _injector.Stop();
            _injector.Dispose();
        }

        private static void RegisterReceiver(IAudioReceiver receiver)
        {
            _receivers.Add(receiver);
            receiver.OnAudioPacketReceived += packet =>
            {
                _injector?.PushPacket(packet);
            };
            receiver.OnLog += Log;
        }

        private static void Log(string msg)
        {
            lock (_logLock)
            {
                string timestamp = DateTime.Now.ToString("HH:mm:ss.fff");
                _recentLogs.Enqueue($"[{timestamp}] {msg}");
                while (_recentLogs.Count > 6)
                {
                    _recentLogs.Dequeue();
                }
            }
        }

        private static async Task RunDashboardLoop(CancellationToken ct)
        {
            try
            {
                Console.CursorVisible = false;
            }
            catch { }

            while (!ct.IsCancellationRequested)
            {
                RenderDashboard();
                try
                {
                    await Task.Delay(200, ct);
                }
                catch (OperationCanceledException) { break; }
            }

            try
            {
                Console.CursorVisible = true;
            }
            catch { }
        }

        private static void RenderDashboard()
        {
            if (_injector == null) return;

            var sb = new StringBuilder();
            sb.AppendLine("================================================================================");
            sb.AppendLine("                 MICBRIDGE PC ENGINE (.NET 8 WASAPI LOW-LATENCY)                 ");
            sb.AppendLine("================================================================================");
            sb.AppendLine($" Target Device  : {_injector.TargetDeviceName}");
            sb.AppendLine($" Sample Format  : 48000 Hz, 16-bit Mono, Low-latency 10ms Event-driven");
            sb.AppendLine("--------------------------------------------------------------------------------");

            // Volume Meter
            float peak = _injector.PeakVolume;
            float rms = _injector.RmsVolume;
            int peakBars = Math.Clamp((int)(peak * 35), 0, 35);
            string peakMeter = new string('#', peakBars).PadRight(35, '-');
            sb.AppendLine($" Input Level    : [{peakMeter}] Peak: {peak * 100,5:F1}% | RMS: {rms * 100,5:F1}%");

            // Jitter Buffer Stats
            var jb = _injector.JitterBuffer;
            sb.AppendLine($" Jitter Buffer  : Queue: {jb.QueuedPacketsCount,2} pkts | Jitter: {jb.JitterMs,4:F1}ms | Recv: {jb.ReceivedPackets,6} | Lost: {jb.LostPackets,4}");
            sb.AppendLine("--------------------------------------------------------------------------------");

            // Transport Stats
            sb.AppendLine(" ACTIVE TRANSPORTS:");
            foreach (var r in _receivers)
            {
                string status = r.IsRunning ? "[ONLINE]" : "[OFFLINE]";
                double kbps = (r.BytesReceivedPerSec * 8.0) / 1024.0;
                sb.AppendLine($"  - {r.TransportName,-28} : {status} {r.PacketsReceivedPerSec,4} pps | {kbps,6:F1} kbps");
            }

            sb.AppendLine("--------------------------------------------------------------------------------");
            sb.AppendLine(" RECENT LOGS:");
            lock (_logLock)
            {
                foreach (var log in _recentLogs)
                {
                    sb.AppendLine("  " + log);
                }
            }
            sb.AppendLine("================================================================================");
            sb.AppendLine(" Instructions: Set Windows App Mic to 'CABLE Output' | Press Ctrl+C to Quit");

            try
            {
                Console.SetCursorPosition(0, 0);
                Console.Write(sb.ToString());
            }
            catch
            {
                // Fallback if terminal doesn't support cursor positioning
                Console.WriteLine(sb.ToString());
            }
        }
    }
}
