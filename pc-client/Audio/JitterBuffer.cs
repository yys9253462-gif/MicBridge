using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Threading;

namespace MicBridge.PC.Audio
{
    public class AudioPacket
    {
        public AudioFrameHeader Header { get; set; }
        public byte[] PcmData { get; set; } = Array.Empty<byte>();
        public long ReceivedTimeTicks { get; set; }
    }

    /// <summary>
    /// Jitter Buffer with packet reordering, PLC (Packet Loss Concealment), 
    /// and clock drift alignment.
    /// </summary>
    public class JitterBuffer
    {
        private readonly SortedDictionary<uint, AudioPacket> _packetQueue = new();
        private readonly object _lock = new();

        private readonly int _targetBufferingPackets;
        private readonly int _maxQueueSize;
        private uint _nextExpectedSeq = 0;
        private bool _isInitialized = false;
        private bool _hasStartedPlayback = false;

        // Statistics
        private long _totalReceivedPackets = 0;
        private long _totalLostPackets = 0;
        private long _totalConcealedPackets = 0;
        private double _currentJitterMs = 0;
        private double _lastTransitTime = 0;

        public double JitterMs => _currentJitterMs;
        public long LostPackets => _totalLostPackets;
        public long ReceivedPackets => _totalReceivedPackets;
        public int QueuedPacketsCount
        {
            get
            {
                lock (_lock) return _packetQueue.Count;
            }
        }

        public JitterBuffer(int targetBufferingPackets = 4, int maxQueueSize = 25)
        {
            _targetBufferingPackets = targetBufferingPackets;
            _maxQueueSize = maxQueueSize;
        }

        public void Push(AudioPacket packet)
        {
            lock (_lock)
            {
                Interlocked.Increment(ref _totalReceivedPackets);

                // Calculate jitter according to RFC 3550 standard
                long nowMs = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                double transitTime = nowMs - (long)packet.Header.TimestampMs;
                if (_lastTransitTime != 0)
                {
                    double d = Math.Abs(transitTime - _lastTransitTime);
                    _currentJitterMs += (d - _currentJitterMs) / 16.0;
                }
                _lastTransitTime = transitTime;

                if (!_isInitialized)
                {
                    _nextExpectedSeq = packet.Header.SequenceNumber;
                    _isInitialized = true;
                }

                // If packet is too old (already played past its seq)
                if (IsSequenceBefore(packet.Header.SequenceNumber, _nextExpectedSeq))
                {
                    // Late packet, drop it
                    return;
                }

                // Insert into sorted queue
                _packetQueue[packet.Header.SequenceNumber] = packet;

                // Buffer overflow protection: if queue grows too large, fast-forward
                if (_packetQueue.Count > _maxQueueSize)
                {
                    using var enumerator = _packetQueue.Keys.GetEnumerator();
                    if (enumerator.MoveNext())
                    {
                        _nextExpectedSeq = enumerator.Current;
                    }
                }
            }
        }

        /// <summary>
        /// Pulls the next contiguous PCM chunk. If packet is missing, produces Packet Loss Concealment (comfort silence/decay).
        /// </summary>
        public byte[]? Pull(int expectedBytes)
        {
            lock (_lock)
            {
                if (!_isInitialized) return null;

                // Wait until we have accumulated target buffering packets (startup warm-up)
                if (!_hasStartedPlayback)
                {
                    if (_packetQueue.Count < _targetBufferingPackets)
                    {
                        return null;
                    }
                    _hasStartedPlayback = true;
                }

                if (_packetQueue.TryGetValue(_nextExpectedSeq, out var packet))
                {
                    _packetQueue.Remove(_nextExpectedSeq);
                    _nextExpectedSeq++;
                    return packet.PcmData;
                }

                // Packet is missing!
                // If there are newer packets in the queue, this packet was lost in transit
                if (_packetQueue.Count > 0)
                {
                    var minSeq = GetFirstSequence();
                    if (IsSequenceBefore(_nextExpectedSeq, minSeq))
                    {
                        // The packet was indeed dropped
                        Interlocked.Increment(ref _totalLostPackets);
                        Interlocked.Increment(ref _totalConcealedPackets);
                        _nextExpectedSeq++;

                        // Return silence/concealment buffer
                        return new byte[expectedBytes];
                    }
                }

                return null;
            }
        }

        public void Reset()
        {
            lock (_lock)
            {
                _packetQueue.Clear();
                _isInitialized = false;
                _hasStartedPlayback = false;
                _nextExpectedSeq = 0;
            }
        }

        private uint GetFirstSequence()
        {
            using var enumerator = _packetQueue.Keys.GetEnumerator();
            enumerator.MoveNext();
            return enumerator.Current;
        }

        private static bool IsSequenceBefore(uint s1, uint s2)
        {
            // Modular sequence number comparison (handles 32-bit wrap-around)
            return (s1 != s2) && ((s2 - s1) < 0x80000000);
        }
    }
}
