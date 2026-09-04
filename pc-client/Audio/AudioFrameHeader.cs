using System;
using System.IO;

namespace MicBridge.PC.Audio
{
    public struct AudioFrameHeader
    {
        public const uint MagicValue = 0x4D494342; // "MICB"
        public const int HeaderSize = 24;

        public uint Magic;            // 4 bytes: 'M','I','C','B'
        public ushort Version;        // 2 bytes: Protocol version (e.g., 1)
        public ushort ChannelCount;   // 2 bytes: e.g. 1 (Mono) or 2 (Stereo)
        public uint SampleRate;       // 4 bytes: e.g. 48000
        public uint SequenceNumber;   // 4 bytes: Sequential packet counter
        public ulong TimestampMs;     // 8 bytes: Sender timestamp (UTC ms)

        public static bool TryParse(byte[] buffer, int offset, int length, out AudioFrameHeader header)
        {
            header = default;
            if (length < HeaderSize) return false;

            using var ms = new MemoryStream(buffer, offset, length, false);
            using var reader = new BinaryReader(ms);

            header.Magic = reader.ReadUInt32();
            if (header.Magic != MagicValue)
            {
                return false;
            }

            header.Version = reader.ReadUInt16();
            header.ChannelCount = reader.ReadUInt16();
            header.SampleRate = reader.ReadUInt32();
            header.SequenceNumber = reader.ReadUInt32();
            header.TimestampMs = reader.ReadUInt64();

            return true;
        }

        public byte[] ToBytes()
        {
            var bytes = new byte[HeaderSize];
            using var ms = new MemoryStream(bytes);
            using var writer = new BinaryWriter(ms);

            writer.Write(Magic);
            writer.Write(Version);
            writer.Write(ChannelCount);
            writer.Write(SampleRate);
            writer.Write(SequenceNumber);
            writer.Write(TimestampMs);

            return bytes;
        }
    }
}
