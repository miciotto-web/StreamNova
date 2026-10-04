// SnakeNeon — launcher autonomo per il gioco Snake (index.html incorporato nell'exe).
// Compilazione:
//   csc /nologo /target:winexe /platform:anycpu /out:SnakeNeon.exe /res:"index.html,index.html" SnakeNeon.cs
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;

namespace SnakeNeon
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            // Estrae il gioco incorporato in una cartella locale (sovrascritta a ogni avvio)
            string dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
                "SnakeNeon");
            Directory.CreateDirectory(dir);
            string htmlPath = Path.Combine(dir, "index.html");

            using (Stream src = Assembly.GetExecutingAssembly()
                                       .GetManifestResourceStream("index.html"))
            {
                if (src == null)
                    throw new ApplicationException("Risorsa index.html non trovata nell'eseguibile.");
                using (FileStream dst = File.Create(htmlPath))
                {
                    src.CopyTo(dst);
                }
            }

            string url = "file:///" + htmlPath.Replace('\\', '/');

            // Apre il gioco in una finestra "app" senza UI del browser (motore Edge/Chrome)
            string[] browsers = new string[]
            {
                Environment.ExpandEnvironmentVariables(@"%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe"),
                Environment.ExpandEnvironmentVariables(@"%ProgramFiles%\Microsoft\Edge\Application\msedge.exe"),
                Environment.ExpandEnvironmentVariables(@"%ProgramFiles%\Google\Chrome\Application\chrome.exe"),
                Environment.ExpandEnvironmentVariables(@"%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe"),
                Environment.ExpandEnvironmentVariables(@"%LocalAppData%\Google\Chrome\Application\chrome.exe")
            };

            foreach (string browser in browsers)
            {
                if (File.Exists(browser))
                {
                    Process.Start(browser, "--app=" + url + " --window-size=560,790");
                    return;
                }
            }

            // Fallback: apre con il browser predefinito (finestra normale)
            Process.Start(new ProcessStartInfo(htmlPath) { UseShellExecute = true });
        }
    }
}