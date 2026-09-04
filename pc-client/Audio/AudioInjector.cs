using System;
using System.IO;
using System.Threading;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace MicBridge.PC.Audio
{
    /// <summary>
    /// WASAPI Audio Injector with low latency event-driven rendering into virtual microphone endpoints.
    /// Includes real-time RMS/Peak volume calculation.
    /// </summary>
    public class AudioInjector : IDisposable
    {
        private readonly WasapiOut _wasapiOut;
        private readonly BufferedWaveProvider _bufferedWaveProvider;
        private readonly WaveFormat _waveFormat;
        private readonly JitterBuffer _jitterBuffer;
        private readonly Thread _feederThread;
        private volatile bool _isRunning = false;

        // Peak / RMS metrics
        private float _currentPeak = 0.0f;
        private float _currentRms = 0.0f;
        private readonly object _metricsLock = new();

        public float PeakVolume => _currentPeak;
        public float RmsVolume => _currentRms;
        public JitterBuffer JitterBuffer => _jitterBuffer;
        public string TargetDeviceName { get; }

        public AudioInjector(MMDevice targetDevice, int sampleRate = 48000, int channels = 1)
        {
            TargetDeviceName = targetDevice.FriendlyName;
            _waveFormat = new WaveFormat(sampleRate, 16, channels);

            // Buffer sized to 2 seconds max
            _bufferedWaveProvider = new BufferedWaveProvider(_waveFormat)
            {
                BufferLength = sampleRate * channels * 2 * 2,
                DiscardOnBufferOverflow = true
            };

            _jitterBuffer = new JitterBuffer(targetBufferingPackets: 3, maxQueueSize: 20);

            // Configure WasapiOut with EventSync & 10ms target latency
            _wasapiOut = new WasapiOut(targetDevice, AudioClientShareMode.Shared, useEventSync: true, latency: 10);
            _wasapiOut.Init(_bufferedWaveProvider);

            _feederThread = new Thread(FeederLoop)
            {
                IsBackground = true,
                Priority = ThreadPriority.Highest,
                Name = "AudioInjectorFeeder"
            };
        }

        public void Start()
        {
            if (_isRunning) return;
            _isRunning = true;
            _wasapiOut.Play();
            _feederThread.Start();
        }

        public void PushPacket(AudioPacket packet)
        {
            _jitterBuffer.Push(packet);
        }

        private void FeederLoop()
        {
            // At 48000Hz 16-bit mono: 10ms = 480 samples = 960 bytes
            int bytesPer10Ms = (_waveFormat.SampleRate * _waveFormat.Channels * (_waveFormat.BitsPerSample / 8)) / 100;
            byte[] silenceBuffer = new byte[bytesPer10Ms];

            while (_isRunning)
            {
                try
                {
                    // If buffer is running low (< 20ms buffered), pull from JitterBuffer
                    if (_bufferedWaveProvider.BufferedBytes < bytesPer10Ms * 2)
                    {
                        byte[]? chunk = _jitterBuffer.Pull(bytesPer10Ms);
                        if (chunk != null && chunk.Length > 0)
                        {
                            ComputeVolume(chunk, chunk.Length);
                            _bufferedWaveProvider.AddSamples(chunk, 0, chunk.Length);
                        }
                        else
                        {
                            // Keep buffer fed with silence to avoid WASAPI underflow glitches
                            _bufferedWaveProvider.AddSamples(silenceBuffer, 0, silenceBuffer.Length);
                        }
                    }

                    Thread.Sleep(5);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[AudioInjector] Feeder error: {ex.Message}");
                }
            }
        }

        private void ComputeVolume(byte[] buffer, int length)
        {
            int sampleCount = length / 2;
            if (sampleCount == 0) return;

            float maxAbs = 0f;
            double sumSquares = 0.0;

            for (int i = 0; i < length; i += 2)
            {
                short sample = (short)(buffer[i] | (buffer[i + 1] << 8));
                float norm = Math.Abs(sample / 32768.0f);
                if (norm > maxAbs) maxAbs = norm;
                sumSquares += norm * norm;
            }

            float rms = (float)Math.Sqrt(sumSquares / sampleCount);

            lock (_metricsLock)
            {
                // Smooth decay
                _currentPeak = Math.Max(maxAbs, _currentPeak * 0.85f);
                _currentRms = (rms * 0.3f) + (_currentRms * 0.7f);
            }
        }

        public void Stop()
        {
            _isRunning = false;
            try
            {
                _wasapiOut.Stop();
            }
            catch { }
        }

        public void Dispose()
        {
            Stop();
            _wasapiOut.Dispose();
        }
    }
}
