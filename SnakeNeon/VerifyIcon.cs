using System;
using System.Drawing;
using System.IO;

class VerifyIcon
{
    static void Main()
    {
        string icoPath = "SnakeNeon.ico";
        try
        {
            using (Icon ico = new Icon(icoPath))
            {
                Console.WriteLine("SnakeNeon.ico loaded successfully");
                Console.WriteLine("Size: " + ico.Size);
            }
        }
        catch (Exception e)
        {
            Console.WriteLine("Error loading .ico: " + e.Message);
        }

        try
        {
            using (Icon ico = Icon.ExtractAssociatedIcon("SnakeNeon.exe"))
            {
                Console.WriteLine("EXE associated icon: " + ico.Size);
            }
        }
        catch (Exception e)
        {
            Console.WriteLine("Error extracting from exe: " + e.Message);
        }

        // Check PE architecture
        byte[] pe = File.ReadAllBytes("SnakeNeon.exe");
        if (pe.Length > 0x3C)
        {
            int peOffset = BitConverter.ToInt32(pe, 0x3C);
            if (peOffset + 4 < pe.Length)
            {
                ushort machine = BitConverter.ToUInt16(pe, peOffset + 4);
                string arch = machine == 0x14c ? "x86 (32-bit)" : machine == 0x8664 ? "x64 (64-bit)" : machine == 0x1c0 ? "ARM" : "Unknown (0x" + machine.ToString("X") + ")";
                Console.WriteLine("EXE Architecture: " + arch);
            }
        }

        // Check resource
        try
        {
            System.Reflection.Assembly asm = System.Reflection.Assembly.LoadFile(Path.GetFullPath("SnakeNeon.exe"));
            string[] res = asm.GetManifestResourceNames();
            Console.WriteLine("Manifest resources:");
            foreach (string r in res) Console.WriteLine("  " + r);
        }
        catch (Exception e)
        {
            Console.WriteLine("Assembly load error: " + e.Message);
        }
    }
}