<# Windows CPU Set topology; no affinity or system settings are changed. #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'CPU Set discovery requires Windows.' }
if (-not ('FletchCpuSets' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Runtime.InteropServices;
public static class FletchCpuSets {
    [DllImport("kernel32.dll", SetLastError=true)]
    static extern bool GetSystemCpuSetInformation(IntPtr data, uint size,
        out uint returned, IntPtr process, uint flags);
    public sealed class Cpu {
        public uint Id { get; set; }
        public ushort Group { get; set; }
        public byte LogicalProcessor { get; set; }
        public byte Core { get; set; }
        public byte EfficiencyClass { get; set; }
        public byte Flags { get; set; }
    }
    public static Cpu[] Read() {
        uint size;
        GetSystemCpuSetInformation(IntPtr.Zero, 0, out size, IntPtr.Zero, 0);
        if (size == 0) throw new Win32Exception(Marshal.GetLastWin32Error());
        IntPtr data = Marshal.AllocHGlobal(checked((int)size));
        try {
            uint returned;
            if (!GetSystemCpuSetInformation(data, size, out returned, IntPtr.Zero, 0))
                throw new Win32Exception(Marshal.GetLastWin32Error());
            var result = new List<Cpu>();
            for (int offset = 0; offset < returned;) {
                int recordSize = Marshal.ReadInt32(data, offset);
                if (recordSize < 8 || offset + recordSize > returned)
                    throw new InvalidOperationException("Invalid CPU Set record size.");
                if (Marshal.ReadInt32(data, offset + 4) == 0) {
                    if (recordSize < 32) throw new InvalidOperationException("Truncated CPU Set record.");
                    result.Add(new Cpu {
                        Id = unchecked((uint)Marshal.ReadInt32(data, offset + 8)),
                        Group = unchecked((ushort)Marshal.ReadInt16(data, offset + 12)),
                        LogicalProcessor = Marshal.ReadByte(data, offset + 14),
                        Core = Marshal.ReadByte(data, offset + 15),
                        EfficiencyClass = Marshal.ReadByte(data, offset + 18),
                        Flags = Marshal.ReadByte(data, offset + 19)
                    });
                }
                offset += recordSize;
            }
            return result.ToArray();
        } finally { Marshal.FreeHGlobal(data); }
    }
}
'@
}
[FletchCpuSets]::Read()
