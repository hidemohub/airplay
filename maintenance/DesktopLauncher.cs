using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

internal static class DesktopLauncher {
    [STAThread]
    private static int Main(string[] args) {
        string root = AppDomain.CurrentDomain.BaseDirectory;
        try {
            string logDir = Path.Combine(root, "logs");
            Directory.CreateDirectory(logDir);
            // Use the original command-line tail so Windows quoting is preserved.
            string commandLine = Environment.CommandLine;
            int tail = commandLine.StartsWith("\"")
                ? commandLine.IndexOf('"', 1) + 1
                : (commandLine.IndexOf(' ') < 0 ? commandLine.Length : commandLine.IndexOf(' '));
            var start = new ProcessStartInfo {
                FileName = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), @"WindowsPowerShell\v1.0\powershell.exe"),
                Arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File \"" + Path.Combine(root, @"scripts\desktop-start.ps1") + "\" " + commandLine.Substring(tail),
                WorkingDirectory = root,
                UseShellExecute = false,
                CreateNoWindow = true,
                WindowStyle = ProcessWindowStyle.Hidden
            };
            using (var process = Process.Start(start)) {
                process.WaitForExit();
                if (process.ExitCode != 0) throw new Exception("Startup failed. See logs\\launcher.log and logs\\java.stderr.log.");
            }
            return 0;
        } catch (Exception error) {
            MessageBox.Show(error.Message, "airplay", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
}
