using System;
using System.IO;

class CheckIconDetail
{
    static void Main()
    {
        string icoPath = "SnakeNeon.ico";
        try
        {
            using (FileStream fs = new FileStream(icoPath, FileMode.Open, FileAccess.Read))
            {
                BinaryReader br = new BinaryReader(fs);
                ushort reserved = br.ReadUInt16();
                ushort type = br.ReadUInt16();
                ushort count = br.ReadUInt16();
                Console.WriteLine("ICO Header: reserved=" + reserved + ", type=" + type + ", count=" + count);
                for (int i = 0; i < count; i++)
                {
                    byte w = br.ReadByte();
                    byte h = br.ReadByte();
                    byte colors = br.ReadByte();
                    byte reserved2 = br.ReadByte();
                    ushort planes = br.ReadUInt16();
                    ushort bitCount = br.ReadUInt16();
                    uint size = br.ReadUInt32();
                    uint offset = br.ReadUInt32();
                    Console.WriteLine("  Entry " + i + ": " + w + "x" + h + " colors=" + colors + " planes=" + planes + " bpp=" + bitCount + " size=" + size + " offset=" + offset);
                }
            }
        }
        catch (Exception e)
        {
            Console.WriteLine("Error: " + e.Message);
        }
    }
}