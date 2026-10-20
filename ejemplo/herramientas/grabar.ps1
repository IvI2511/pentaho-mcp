param([string]$Carpeta, [string]$Parar, [int]$Ms = 250)
# Saca fotos de la ventana de Spoon cada $Ms ms hasta que aparece el archivo $Parar (para armar un GIF).
Add-Type -TypeDefinition @"
using System; using System.Runtime.InteropServices; using System.Drawing;
public static class Foto2 {
  [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint f);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
  public static void Sacar(IntPtr h, string ruta) {
    RECT r; GetWindowRect(h, out r);
    var bmp = new Bitmap(r.R - r.L, r.B - r.T);
    using (var g = Graphics.FromImage(bmp)) { var hdc = g.GetHdc(); PrintWindow(h, hdc, 2); g.ReleaseHdc(hdc); }
    bmp.Save(ruta, System.Drawing.Imaging.ImageFormat.Png); bmp.Dispose();
  }
}
"@ -ReferencedAssemblies System.Drawing
[Foto2]::SetProcessDPIAware() | Out-Null
New-Item -ItemType Directory -Force $Carpeta | Out-Null
$p = Get-Process javaw | Where-Object { $_.MainWindowTitle -like '*Spoon*' } | Select-Object -First 1
$i = 0
while (-not (Test-Path $Parar) -and $i -lt 2000) {
  [Foto2]::Sacar($p.MainWindowHandle, (Join-Path $Carpeta ("f{0:D4}.png" -f $i)))
  $i++; Start-Sleep -Milliseconds $Ms
}
