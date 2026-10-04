using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

class CreateIconFixed
{
    static void Main()
    {
        int[] sizes = { 16, 24, 32, 48, 64, 128, 256 };
        string outputPath = "SnakeNeon.ico";

        using (FileStream fs = new FileStream(outputPath, FileMode.Create))
        {
            BinaryWriter bw = new BinaryWriter(fs);
            bw.Write((short)0);           
            bw.Write((short)1);           
            bw.Write((short)sizes.Length); 

            long[] imageOffsets = new long[sizes.Length];
            byte[][] imageData = new byte[sizes.Length][];

            for (int i = 0; i < sizes.Length; i++)
            {
                int size = sizes[i];
                using (Bitmap bmp = CreateIconBitmap(size))
                {
                    using (MemoryStream ms = new MemoryStream())
                    {
                        // Always save as BMP for maximum Windows Explorer compatibility
                        SaveAsBmp32bpp(bmp, ms);
                        imageData[i] = ms.ToArray();
                    }
                }
            }

            long dataStart = 6 + sizes.Length * 16;
            long currentOffset = dataStart;

            for (int i = 0; i < sizes.Length; i++)
            {
                int size = sizes[i];
                bw.Write((byte)size);      
                bw.Write((byte)size);      
                bw.Write((byte)0);         
                bw.Write((byte)0);         
                bw.Write((short)1);        
                bw.Write((short)32);       
                bw.Write(imageData[i].Length); 
                bw.Write((int)currentOffset);  
                imageOffsets[i] = currentOffset;
                currentOffset += imageData[i].Length;
            }

            for (int i = 0; i < sizes.Length; i++)
            {
                fs.Position = imageOffsets[i];
                bw.Write(imageData[i]);
            }
        }

        Console.WriteLine("Created " + outputPath + " with " + sizes.Length + " sizes (all BMP 32bpp)");
        foreach (int s in sizes) Console.WriteLine("  " + s + "x" + s);
    }

    static Bitmap CreateIconBitmap(int size)
    {
        Bitmap bmp = new Bitmap(size, size, PixelFormat.Format32bppArgb);
        using (Graphics g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            g.InterpolationMode = InterpolationMode.HighQualityBicubic;
            g.PixelOffsetMode = PixelOffsetMode.HighQuality;
            g.CompositingQuality = CompositingQuality.HighQuality;

            g.Clear(Color.Transparent);

            float cx = size / 2f;
            float cy = size / 2f;

            // Dark background circle
            using (SolidBrush bgBrush = new SolidBrush(Color.FromArgb(40, 6, 9, 19)))
            {
                g.FillEllipse(bgBrush, size * 0.05f, size * 0.05f, size * 0.9f, size * 0.9f);
            }

            float segmentSize = size * 0.12f;
            float spacing = segmentSize * 0.85f;

            PointF[] snakePoints = new PointF[]
            {
                new PointF(cx - spacing * 2.5f, cy - spacing * 1.5f),
                new PointF(cx - spacing * 1.5f, cy - spacing * 1.5f),
                new PointF(cx - spacing * 0.5f, cy - spacing * 1.5f),
                new PointF(cx + spacing * 0.5f, cy - spacing * 0.5f),
                new PointF(cx + spacing * 1.5f, cy - spacing * 0.5f),
                new PointF(cx + spacing * 2.5f, cy - spacing * 0.5f),
                new PointF(cx + spacing * 2.5f, cy + spacing * 0.5f),
                new PointF(cx + spacing * 1.5f, cy + spacing * 0.5f),
                new PointF(cx + spacing * 0.5f, cy + spacing * 0.5f),
                new PointF(cx - spacing * 0.5f, cy + spacing * 1.5f),
                new PointF(cx - spacing * 1.5f, cy + spacing * 1.5f),
                new PointF(cx - spacing * 2.5f, cy + spacing * 1.5f),
            };

            // Glow effect
            if (size >= 32)
            {
                using (GraphicsPath glowPath = new GraphicsPath())
                {
                    for (int i = 0; i < snakePoints.Length; i++)
                    {
                        float r = segmentSize * 1.4f;
                        glowPath.AddEllipse(snakePoints[i].X - r, snakePoints[i].Y - r, r * 2, r * 2);
                    }
                    using (PathGradientBrush glowBrush = new PathGradientBrush(glowPath))
                    {
                        glowBrush.CenterColor = Color.FromArgb(80, 0, 240, 255);
                        glowBrush.SurroundColors = new Color[] { Color.FromArgb(0, 0, 240, 255) };
                        glowBrush.FocusScales = new PointF(0.3f, 0.3f);
                        g.FillPath(glowBrush, glowPath);
                    }
                }
            }

            // Snake body segments
            for (int i = 0; i < snakePoints.Length; i++)
            {
                float segmentRatio = 1f - (i * 0.05f);
                float r = segmentSize * segmentRatio;

                Color bodyColor = Color.FromArgb(255, 0, 240, 255);
                Color glowColor = Color.FromArgb(180, 0, 200, 255);

                if (size >= 24)
                {
                    using (SolidBrush glowBrush = new SolidBrush(glowColor))
                    {
                        g.FillEllipse(glowBrush, snakePoints[i].X - r * 1.15f, snakePoints[i].Y - r * 1.15f, r * 2.3f, r * 2.3f);
                    }
                }

                using (SolidBrush bodyBrush = new SolidBrush(bodyColor))
                {
                    g.FillEllipse(bodyBrush, snakePoints[i].X - r, snakePoints[i].Y - r, r * 2, r * 2);
                }

                if (size >= 32)
                {
                    using (SolidBrush highlightBrush = new SolidBrush(Color.FromArgb(200, 180, 255, 255)))
                    {
                        g.FillEllipse(highlightBrush, snakePoints[i].X - r * 0.35f, snakePoints[i].Y - r * 0.45f, r * 0.7f, r * 0.5f);
                    }
                }
            }

            // Head
            PointF head = snakePoints[0];
            float headR = segmentSize * 1.15f;

            if (size >= 24)
            {
                using (SolidBrush headGlowBrush = new SolidBrush(Color.FromArgb(150, 57, 255, 20)))
                {
                    g.FillEllipse(headGlowBrush, head.X - headR * 1.2f, head.Y - headR * 1.2f, headR * 2.4f, headR * 2.4f);
                }
            }

            using (SolidBrush headBrush = new SolidBrush(Color.FromArgb(255, 57, 255, 20)))
            {
                g.FillEllipse(headBrush, head.X - headR, head.Y - headR, headR * 2, headR * 2);
            }

            // Eyes
            if (size >= 24)
            {
                float eyeOffset = headR * 0.35f;
                float eyeSize = Math.Max(1.5f, headR * 0.22f);

                using (SolidBrush eyeBrush = new SolidBrush(Color.FromArgb(255, 0, 240, 255)))
                {
                    g.FillEllipse(eyeBrush, head.X - eyeOffset - eyeSize/2, head.Y - eyeOffset - eyeSize/2, eyeSize, eyeSize);
                    g.FillEllipse(eyeBrush, head.X + eyeOffset - eyeSize/2, head.Y - eyeOffset - eyeSize/2, eyeSize, eyeSize);
                }

                if (size >= 32)
                {
                    using (SolidBrush highlightBrush = new SolidBrush(Color.White))
                    {
                        float hlSize = Math.Max(0.8f, eyeSize * 0.4f);
                        g.FillEllipse(highlightBrush, head.X - eyeOffset - hlSize/2 + eyeSize*0.15f, head.Y - eyeOffset - hlSize/2 - eyeSize*0.1f, hlSize, hlSize);
                        g.FillEllipse(highlightBrush, head.X + eyeOffset - hlSize/2 + eyeSize*0.15f, head.Y - eyeOffset - hlSize/2 - eyeSize*0.1f, hlSize, hlSize);
                    }
                }
            }

            // Food near tail
            if (size >= 32)
            {
                PointF tail = snakePoints[snakePoints.Length - 1];
                float foodR = segmentSize * 0.5f;
                PointF foodPos = new PointF(tail.X + spacing * 0.8f, tail.Y + spacing * 0.8f);

                using (SolidBrush foodGlowBrush = new SolidBrush(Color.FromArgb(120, 255, 46, 136)))
                {
                    g.FillEllipse(foodGlowBrush, foodPos.X - foodR * 1.5f, foodPos.Y - foodR * 1.5f, foodR * 3, foodR * 3);
                }

                using (SolidBrush foodBrush = new SolidBrush(Color.FromArgb(255, 255, 46, 136)))
                {
                    g.FillEllipse(foodBrush, foodPos.X - foodR, foodPos.Y - foodR, foodR * 2, foodR * 2);
                }
            }
        }
        return bmp;
    }

    static void SaveAsBmp32bpp(Bitmap bmp, Stream stream)
    {
        int width = bmp.Width;
        int height = bmp.Height;
        int stride = width * 4;
        int imageSize = stride * height;
        int fileSize = 14 + 40 + imageSize + ((width + 31) / 32) * 4 * height;

        BinaryWriter bw = new BinaryWriter(stream);
        // BITMAPFILEHEADER
        bw.Write((short)0x4D42); 
        bw.Write(fileSize);
        bw.Write((int)0); 
        bw.Write(14 + 40); 

        // BITMAPINFOHEADER
        bw.Write(40); 
        bw.Write(width);
        bw.Write(height * 2); 
        bw.Write((short)1); 
        bw.Write((short)32); 
        bw.Write(0); 
        bw.Write(imageSize);
        bw.Write(0); 
        bw.Write(0); 
        bw.Write(0); 
        bw.Write(0); 

        BitmapData bd = bmp.LockBits(new Rectangle(0, 0, width, height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        byte[] pixels = new byte[imageSize];
        Marshal.Copy(bd.Scan0, pixels, 0, imageSize);
        bmp.UnlockBits(bd);

        for (int y = height - 1; y >= 0; y--)
        {
            int rowStart = y * stride;
            bw.Write(pixels, rowStart, stride);
        }

        int andMaskStride = ((width + 31) / 32) * 4;
        for (int y = 0; y < height; y++)
        {
            for (int x = 0; x < andMaskStride; x++)
            {
                bw.Write((byte)0);
            }
        }
    }
}