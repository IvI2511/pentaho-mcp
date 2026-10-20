# Busca el JDK (javac + java) como spoon-plugin\build.ps1: JAVA_HOME, el java del PATH o C:\Program Files\Java\jdk*.
# Lo cargan correr.ps1 y nombres.ps1 con «. jdk.ps1».
function Find-JdkBin {
  if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    return (Join-Path $env:JAVA_HOME "bin")
  }
  # via cmd: en PowerShell 5.1 el stderr de un exe con ErrorActionPreference=Stop corta el script
  $props = cmd /c "java -XshowSettings:properties -version 2>&1" | Out-String
  if ($props -match "java\.home = (.+)") {
    $home2 = $Matches[1].Trim()
    if (Test-Path (Join-Path $home2 "bin\javac.exe")) { return (Join-Path $home2 "bin") }
  }
  $cand = Get-ChildItem "C:\Program Files\Java" -Directory -Filter "jdk*" -ErrorAction SilentlyContinue |
          Sort-Object Name -Descending | Select-Object -First 1
  if ($cand -and (Test-Path (Join-Path $cand.FullName "bin\javac.exe"))) { return (Join-Path $cand.FullName "bin") }
  throw "No encuentro un JDK con javac.exe (defini JAVA_HOME)."
}

# Memoria libre en GB (la PC tiene 15 GB y se queda corta con Spoon abierto).
function Get-MemoriaLibreGB {
  $o = Get-CimInstance Win32_OperatingSystem
  return [math]::Round($o.FreePhysicalMemory / 1MB, 2)
}

# Antes de cada JVM: espera hasta que haya $MinGB libres (como mucho $MaxMin minutos) y corta si no llega.
function Wait-Memoria([double]$MinGB, [int]$MaxMin = 15) {
  $inicio = Get-Date
  while ($true) {
    $libre = Get-MemoriaLibreGB
    if ($libre -ge $MinGB) { return }
    if (((Get-Date) - $inicio).TotalMinutes -ge $MaxMin) {
      throw "Hay $libre GB libres y hacen falta $MinGB (se esperaron $MaxMin min). Cerrar algo o bajar -MemoriaMinGB."
    }
    Write-Host "Hay $libre GB libres; espero a tener $MinGB..."
    Start-Sleep -Seconds 15
  }
}
