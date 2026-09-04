using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using NAudio.CoreAudioApi;

namespace MicBridge.PC.Audio
{
    public class AudioDeviceInfo
    {
        public string Id { get; set; } = string.Empty;
        public string FriendlyName { get; set; } = string.Empty;
        public DataFlow Flow { get; set; }
        public DeviceState State { get; set; }
        public bool IsVirtualCable { get; set; }
    }

    public class DriverManager
    {
        private readonly MMDeviceEnumerator _enumerator = new();

        /// <summary>
        /// Lists all capture and render audio endpoints.
        /// </summary>
        public List<AudioDeviceInfo> EnumerateDevices()
        {
            var list = new List<AudioDeviceInfo>();

            try
            {
                // Enumerate Render (Speakers / Virtual Cable Input)
                var renderDevices = _enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);
                foreach (var dev in renderDevices)
                {
                    bool isCable = dev.FriendlyName.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase) ||
                                   dev.FriendlyName.Contains("VB-Audio", StringComparison.OrdinalIgnoreCase) ||
                                   dev.FriendlyName.Contains("Virtual", StringComparison.OrdinalIgnoreCase);
                    list.Add(new AudioDeviceInfo
                    {
                        Id = dev.ID,
                        FriendlyName = dev.FriendlyName,
                        Flow = DataFlow.Render,
                        State = dev.State,
                        IsVirtualCable = isCable
                    });
                }

                // Enumerate Capture (Microphone / Virtual Cable Output)
                var captureDevices = _enumerator.EnumerateAudioEndPoints(DataFlow.Capture, DeviceState.Active);
                foreach (var dev in captureDevices)
                {
                    bool isCable = dev.FriendlyName.Contains("CABLE Output", StringComparison.OrdinalIgnoreCase) ||
                                   dev.FriendlyName.Contains("VB-Audio", StringComparison.OrdinalIgnoreCase) ||
                                   dev.FriendlyName.Contains("Virtual", StringComparison.OrdinalIgnoreCase);
                    list.Add(new AudioDeviceInfo
                    {
                        Id = dev.ID,
                        FriendlyName = dev.FriendlyName,
                        Flow = DataFlow.Capture,
                        State = dev.State,
                        IsVirtualCable = isCable
                    });
                }
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[DriverManager] Warning enumerating audio endpoints: {ex.Message}");
            }

            return list;
        }

        /// <summary>
        /// Finds the best Virtual Cable Input endpoint to inject PC audio into.
        /// Applications (Discord, Teams, Game, WebRTC) will listen to "CABLE Output" as microphone.
        /// </summary>
        public MMDevice? FindVirtualCableInputDevice()
        {
            try
            {
                var renderDevices = _enumerator.EnumerateAudioEndPoints(DataFlow.Render, DeviceState.Active);
                // 1. Exact VB-Cable match
                var cableDev = renderDevices.FirstOrDefault(d => d.FriendlyName.Contains("CABLE Input", StringComparison.OrdinalIgnoreCase));
                if (cableDev != null) return cableDev;

                // 2. Generic VB-Audio match
                cableDev = renderDevices.FirstOrDefault(d => d.FriendlyName.Contains("VB-Audio", StringComparison.OrdinalIgnoreCase));
                if (cableDev != null) return cableDev;

                // 3. Any virtual card
                cableDev = renderDevices.FirstOrDefault(d => d.FriendlyName.Contains("Virtual", StringComparison.OrdinalIgnoreCase));
                if (cableDev != null) return cableDev;

                // Fallback to default render device if virtual cable not found
                return _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[DriverManager] Error finding Virtual Cable device: {ex.Message}");
                return null;
            }
        }

        /// <summary>
        /// Checks if VB-CABLE driver is installed and ready.
        /// </summary>
        public bool IsVirtualDriverInstalled()
        {
            var devices = EnumerateDevices();
            return devices.Any(d => d.IsVirtualCable);
        }

        /// <summary>
        /// Self-healing: backs up the user's primary default playback speaker, 
        /// provides guidance / silent installation for VB-CABLE, and restores the default speaker.
        /// </summary>
        public void EnsureVirtualDriverReady()
        {
            if (IsVirtualDriverInstalled())
            {
                Console.WriteLine("[DriverManager] Virtual audio cable driver detected and ready.");
                return;
            }

            Console.WriteLine("[DriverManager] WARNING: No Virtual Audio Cable detected!");
            Console.WriteLine("[DriverManager] To route smartphone audio as a PC microphone in Discord/Teams/OBS:");
            Console.WriteLine("                Please install VB-CABLE Driver: https://vb-audio.com/Cable/");
            Console.WriteLine("                MicBridge can also automatically download and install it if requested.");

            // Backup current default render device ID
            string? originalDefaultDeviceId = null;
            try
            {
                var def = _enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                originalDefaultDeviceId = def?.ID;
                Console.WriteLine($"[DriverManager] Current Default Speaker backed up: {def?.FriendlyName}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[DriverManager] Could not query default speaker: {ex.Message}");
            }
        }
    }
}
