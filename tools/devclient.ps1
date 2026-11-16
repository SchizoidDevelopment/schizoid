<#
  Unattended dev client for the spatial workspace.

  One instance, one window, never a pile of zombies. Only processes started by the
  Gradle `runClient` task are ever killed, so a normal Minecraft installation (or the
  user's own client) is never touched.

    .\tools\devclient.ps1 cycle                          # stop -> start into the test world -> screenshot
    .\tools\devclient.ps1 start -Props "schizoid.spatial.autotest=rear"
    .\tools\devclient.ps1 shot  -Out C:\Temp\shot.png
    .\tools\devclient.ps1 stop

  `-Props` is a comma separated list of -D entries handed to the game JVM. The spatial
  workspace reads `schizoid.spatial.autotest` to open itself and to spawn a mirrored twin
  panel, so a screenshot can cover both faces without any input injection.
#>
param(
    [ValidateSet('start', 'stop', 'shot', 'cycle', 'resize', 'quit')] [string]$Action = 'cycle',
    [string]$Props = '',
    [string]$World = 'New World',
    [string]$Out = "$env:TEMP\mc-shot.png",
    [int]$TimeoutSec = 200,
    [int]$Width = 1280,
    [int]$Height = 720,
    # A shot must be of the size under test: maximizing first would undo the resize.
    [switch]$NoMaximize
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$log = Join-Path $env:TEMP 'schizoid-run.log'
$bat = Join-Path $env:TEMP 'mcdev-run.bat'

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class McDevWin {
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hwnd, out RECT r);
  [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd, int cmd);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll")] public static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int L, T, R, B; }
}
"@

function Get-DevClients {
    # Only the Gradle-launched client: its command line carries the Fabric dev launcher.
    Get-CimInstance Win32_Process -Filter "name='java.exe' or name='javaw.exe'" |
    Where-Object { $_.CommandLine -and $_.CommandLine -like '*devlaunchinjector*' }
}

function Stop-Client {
    $clients = Get-DevClients
    foreach ($client in $clients) { Stop-Process -Id $client.ProcessId -Force -ErrorAction SilentlyContinue }
    if ($clients) { Start-Sleep -Seconds 3 }
    # A killed client leaves a session lock behind; the next quick-play refuses to join without this.
    Get-ChildItem -Path (Join-Path $root 'run\saves') -Filter session.lock -Recurse -ErrorAction SilentlyContinue |
    Remove-Item -Force -ErrorAction SilentlyContinue
    Write-Output "stop: $($clients.Count) client(s)"
}

function Start-Client {
    Stop-Client | Out-Null
    $propArg = if ($Props) { "-PgameProp=$Props" } else { '' }
    @"
@echo off
cd /d "$root"
if not exist "$root\gradlew.bat" (
  echo gradlew.bat is missing in $root > "$log"
  exit /b 1
)
call "$root\gradlew.bat" runClient --offline $propArg -PquickPlaySave="$World" > "$log" 2>&1
"@ | Set-Content -Path $bat -Encoding ASCII
    Start-Process -FilePath $bat -WindowStyle Hidden
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        if ((Test-Path $log) -and (Select-String -Path $log -Pattern 'Loaded \d+ advancements' -Quiet)) {
            Start-Sleep -Seconds 4
            Write-Output "start: client is in the world"
            return
        }
    }
    Write-Output "start: TIMEOUT after ${TimeoutSec}s, see $log"
}

function Resize-Client {
    param([int]$Width, [int]$Height)
    $proc = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
    if (-not $proc) { Write-Output 'resize: no Minecraft window'; return }
    # SWP_NOZORDER only: moving the window is not the point, and focus must stay where it is.
    [McDevWin]::SetWindowPos($proc.MainWindowHandle, [IntPtr]::Zero, 0, 0, $Width, $Height, 0x0004) | Out-Null
    Start-Sleep -Milliseconds 600
    Write-Output "resize: ${Width}x${Height}"
}

function Save-Shot {
    param([switch]$NoMaximize)
    $proc = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
    if (-not $proc) { Write-Output 'shot: no Minecraft window'; return }
    if (-not $NoMaximize) {
        [McDevWin]::ShowWindow($proc.MainWindowHandle, 3) | Out-Null   # SW_MAXIMIZE
    }
    Start-Sleep -Milliseconds 900
    $rect = New-Object McDevWin+RECT
    [McDevWin]::GetWindowRect($proc.MainWindowHandle, [ref]$rect) | Out-Null
    $width = $rect.R - $rect.L
    $height = $rect.B - $rect.T
    $bitmap = New-Object System.Drawing.Bitmap $width, $height
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    # PrintWindow on an OpenGL window hands back whatever frame the driver last flushed, which can be
    # seconds old and made every pixel check disagree with the live state. Bring the window to the
    # front and copy the composited screen instead: always the frame that is actually on display.
    [McDevWin]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
    Start-Sleep -Milliseconds 400
    $graphics.CopyFromScreen($rect.L, $rect.T, 0, 0, (New-Object System.Drawing.Size $width, $height))
    $ok = $true
    $bitmap.Save($Out, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
    Write-Output "shot: ok=$ok $($width)x$($height) -> $Out"
}

function Quit-Client {
    param([string]$World = 'New World', [int]$TimeoutSec = 120)
    $proc = Get-Process | Where-Object { $_.MainWindowTitle -like 'Minecraft*' } | Select-Object -First 1
    if (-not $proc) { Write-Output 'quit: no Minecraft window'; return }
    # WM_CLOSE is the title-bar X: Minecraft runs its normal shutdown, which is where the settings
    # config is written. Stop-Process kills the JVM outright and no shutdown hook ever runs, so a
    # "config was not saved" result after stop says nothing about the real quit path.
    [McDevWin]::PostMessage($proc.MainWindowHandle, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-Process -Id $proc.Id -ErrorAction SilentlyContinue)) {
            Write-Output 'quit: exited cleanly'
            return
        }
        Start-Sleep -Milliseconds 500
    }
    Write-Output 'quit: TIMEOUT, process still alive'
}

switch ($Action) {
    'stop' { Stop-Client }
    'quit' { Quit-Client -World $World -TimeoutSec $TimeoutSec }
    'start' { Start-Client }
    'shot' { Save-Shot -NoMaximize:$NoMaximize }
    'resize' { Resize-Client -Width $Width -Height $Height }
    'cycle' { Start-Client; Save-Shot }
}
