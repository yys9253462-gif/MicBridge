using System;
using MicBridge.PC.Audio;

namespace MicBridge.PC.Transports
{
    public interface IAudioReceiver : IDisposable
    {
        string TransportName { get; }
        bool IsRunning { get; }
        long PacketsReceivedPerSec { get; }
        long BytesReceivedPerSec { get; }

        event Action<AudioPacket>? OnAudioPacketReceived;
        event Action<string>? OnLog;

        void Start();
        void Stop();
    }
}
