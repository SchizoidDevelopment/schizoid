<#
  Isolated, tracked dev client. No real config/accounts/worlds are copied.
  Prepare <IsolatedDir>/game/saves/Verification/level.dat explicitly before start.
    .\tools\devclient.ps1 start -Props "schizoid.spatial.autotest=rear"
    .\tools\devclient.ps1 cycle -FreezePlayer -Out C:\Temp\shot.png
    .\tools\devclient.ps1 shot -Out C:\Temp\shot.png
    .\tools\devclient.ps1 stop
  -IsolatedDir defaults to <project>/build/verification/dev-client; game/, home/,
  logs/, launcher and PID identity live there. Use the same directory for all commands.
  -Props: comma-separated JVM key=value entries. Isolation properties are reserved.
  -FreezePlayer: explicit launch-only opt-in for start/cycle screenshot runs.
  shot only accepts a fresh game-produced F2 PNG; no desktop/window capture fallback.
  -NoMaximize retained for compatibility; screenshots never maximize the window.
  start/cycle throw on timeout; a late client remains tracked (stop explicitly).
  Data-directory isolation is NOT an OS sandbox: mods retain user privileges.
#>
[CmdletBinding()]
param(
    [ValidateSet('start','stop','shot','cycle','resize','quit','key')] [string]$Action = 'cycle',
    [string]$Props = '',
    [string]$World = 'Verification',
    [string]$IsolatedDir = '',
    [string]$Out = "$env:TEMP\mc-shot.png",
    [ValidateRange(1,3600)] [int]$TimeoutSec = 200,
    [ValidateRange(100,16384)] [int]$Width = 1280,
    [ValidateRange(100,16384)] [int]$Height = 720,
    [ValidateRange(1,255)] [int]$Key = 27,
    [ValidateRange(1,100)] [int]$Repeat = 1,
    [switch]$FreezePlayer,
    [switch]$NoMaximize
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot)).TrimEnd('\')
if (-not $IsolatedDir) { $IsolatedDir = Join-Path $root 'build\verification\dev-client' }
$IsolatedDir = [IO.Path]::GetFullPath($IsolatedDir).TrimEnd('\')
$gameDir = Join-Path $IsolatedDir 'game'
$clientHome = Join-Path $IsolatedDir 'home'
$logDir = Join-Path $IsolatedDir 'logs'
$log = Join-Path $logDir 'runClient.log'
$bat = Join-Path $IsolatedDir 'launch.cmd'
$statePath = Join-Path $IsolatedDir 'client.json'
function Assert-NoReparse([string]$Path) {
    $cursor = [IO.Path]::GetFullPath($Path)
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            if ((Get-Item -LiteralPath $cursor -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw "Isolation path traverses a symlink/junction: $cursor"
            }
        }
        $cursor = Split-Path -Parent $cursor
    }
}
function Assert-CmdSafe([string]$Value) {
    if ($Value -match '["%!&|<>^\r\n]') { throw 'Launch values contain forbidden cmd metacharacters/expansions.' }
}
foreach ($path in @($IsolatedDir,$gameDir,$clientHome,$logDir,$log,$bat,$statePath,(Join-Path $IsolatedDir 'wrapper.lock'))) {
    Assert-NoReparse $path
}
foreach ($value in @($root,$IsolatedDir,$World,$Props)) { Assert-CmdSafe $value }
if ($root.Contains(',') -or $IsolatedDir.Contains(',')) { throw 'Isolation/project paths cannot contain commas (gameProp delimiter).' }
if ($IsolatedDir -eq $root -or $IsolatedDir -eq $env:USERPROFILE -or
    $IsolatedDir -eq [IO.Path]::GetPathRoot($IsolatedDir).TrimEnd('\')) {
    throw 'Choose a dedicated isolated directory, not the project, real home or drive root.'
}
if ($World -notmatch '^[^\\/:*?"<>|]+$' -or $World -in @('.','..')) {
    throw 'World must be a single save folder name inside isolated game/saves.'
}
if ($FreezePlayer -and $Action -notin @('start','cycle')) { throw '-FreezePlayer is launch-only (start/cycle).' }
New-Item -ItemType Directory -Force -Path $IsolatedDir | Out-Null
# Serialize commands for this isolation root. Never delete world session.lock files.
$commandLock = [IO.File]::Open((Join-Path $IsolatedDir 'wrapper.lock'),'OpenOrCreate','ReadWrite','None')
function Read-State {
    if (Test-Path -LiteralPath $statePath) {
        $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if ($state.project -ne $root -or $state.gameDir -ne $gameDir -or $state.home -ne $clientHome -or
            $state.launch -notmatch '^[a-f0-9]{32}$') { throw 'Tracked identity does not match project/isolation root.' }
        return $state
    }
}
function Write-State($State) { $State | ConvertTo-Json | Set-Content -LiteralPath $statePath -Encoding UTF8 }
function Test-Argument([string]$CommandLine,[string]$Argument) {
    $pattern = '(?:^|\s)(?:"' + [regex]::Escape($Argument) + '"|' + [regex]::Escape($Argument) + ')(?=\s|$)'
    return [regex]::IsMatch($CommandLine,$pattern,[Text.RegularExpressions.RegexOptions]::IgnoreCase)
}
function Test-ClientIdentity($Client,$State) {
    if (-not $Client.CommandLine -or $Client.CommandLine -notmatch 'devlaunchinjector') { return $false }
    foreach ($arg in @("-Duser.home=$clientHome","-Dschizoid.devclient.project=$root",
        "-Dschizoid.devclient.gameDir=$gameDir","-Dschizoid.devclient.launch=$($State.launch)")) {
        if (-not (Test-Argument $Client.CommandLine $arg)) { return $false }
    }
    return $true
}
function Get-DevClient {
    $state = Read-State
    if (-not $state) { return }
    if ($state.pid) {
        $client = Get-CimInstance Win32_Process -Filter "ProcessId=$([int]$state.pid)"
        if (-not $client) { return }
        if (-not (Test-ClientIdentity $client $state) -or
            $client.CreationDate.ToUniversalTime().Ticks.ToString() -ne $state.created) {
            throw 'Tracked PID identity changed; refusing control/termination.'
        }
        return $client
    }
    # Only adopt this launch's unique token, including a JVM appearing after start timeout.
    $clients = @(Get-CimInstance Win32_Process -Filter "name='java.exe' or name='javaw.exe'" |
        Where-Object { Test-ClientIdentity $_ $state })
    if ($clients.Count -gt 1) { throw 'Ambiguous isolated launch identity; refusing process selection.' }
    if ($clients.Count -eq 1) {
        $state.pid = [int]$clients[0].ProcessId
        $state.created = $clients[0].CreationDate.ToUniversalTime().Ticks.ToString()
        Write-State $state
        return $clients[0]
    }
}
function Get-ClientWindow {
    $client = Get-DevClient
    if (-not $client) { throw 'No tracked isolated client; start it with this wrapper first.' }
    $proc = Get-Process -Id $client.ProcessId -ErrorAction Stop
    if ($proc.MainWindowHandle -eq [IntPtr]::Zero) { throw 'Tracked client has no window yet.' }
    return $proc
}
function Initialize-WindowApi {
    if (-not ('McDevWin' -as [type])) {
        Add-Type @"
using System;
using System.Runtime.InteropServices;
public class McDevWin {
  [DllImport("user32.dll", SetLastError=true)] public static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll", SetLastError=true)] public static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int cx, int cy, uint flags);
}
"@
    }
}
function Send-WindowMessage($Proc,[uint32]$Message,[int]$Code=0) {
    $current = Get-ClientWindow
    if ($current.Id -ne $Proc.Id -or $current.MainWindowHandle -ne $Proc.MainWindowHandle) {
        throw 'Tracked window changed; refusing input.'
    }
    Initialize-WindowApi
    if (-not [McDevWin]::PostMessage($Proc.MainWindowHandle,$Message,[IntPtr]$Code,[IntPtr]::Zero)) {
        throw "Posting tracked window message failed: $Message"
    }
}
function Stop-Client {
    $client = Get-DevClient
    if ($client) {
        Stop-Process -Id $client.ProcessId -Force -ErrorAction Stop
        Wait-Process -Id $client.ProcessId -Timeout 15 -ErrorAction SilentlyContinue
        # Give Gradle/cmd a bounded chance to unwind before an explicit cycle restart.
        $state = Read-State
        if ($state.launcherPid) {
            $launcher = Get-Process -Id $state.launcherPid -ErrorAction SilentlyContinue
            if ($launcher -and $launcher.StartTime.ToUniversalTime().Ticks.ToString() -eq $state.launcherCreated) {
                $null = $launcher.WaitForExit(15000)
            }
        }
        Write-Output "stop: tracked isolated PID $($client.ProcessId) stopped"
    } else { Write-Output 'stop: no tracked isolated client' }
    # No broad Java termination, process-tree kill, save copying or session.lock deletion.
}
function Start-Client {
    $save = Join-Path (Join-Path $gameDir 'saves') $World
    Assert-NoReparse (Join-Path $save 'level.dat')
    if (-not (Test-Path -LiteralPath (Join-Path $save 'level.dat') -PathType Leaf)) {
        throw "Test world missing: $save\level.dat. Prepare the isolated world explicitly; real saves are never copied."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $root 'gradlew.bat') -PathType Leaf)) { throw "gradlew.bat missing: $root" }
    if (Get-DevClient) { throw 'Isolated client already running; quit/stop it explicitly first.' }
    $oldState = Read-State
    if ($oldState -and $oldState.launcherPid) {
        $launcher = Get-Process -Id $oldState.launcherPid -ErrorAction SilentlyContinue
        if ($launcher -and $launcher.StartTime.ToUniversalTime().Ticks.ToString() -eq $oldState.launcherCreated) {
            throw 'Previous launcher is still active (possibly timed out); wait or stop its tracked client.'
        }
    }
    $entries = @()
    if ($Props) {
        foreach ($entry in $Props.Split(',')) {
            $entry = $entry.Trim()
            if ($entry -notmatch '^([^=\s]+)=(.*)$') { throw "Invalid property: $entry (expected key=value)" }
            if ($Matches[1] -in @('user.home','user.dir') -or $Matches[1] -like 'schizoid.devclient.*') {
                throw "Reserved isolation property: $($Matches[1])"
            }
            if ($Matches[1] -eq 'schizoid.spatial.freezePlayer') { throw 'Use explicit -FreezePlayer instead.' }
            $entries += $entry
        }
    }
    foreach ($dir in @($gameDir,$clientHome,$logDir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $state = [pscustomobject]@{ project=$root; gameDir=$gameDir; home=$clientHome;
        launch=[guid]::NewGuid().ToString('N'); pid=$null; created=$null; launcherPid=0; launcherCreated='' }
    $properties = @("user.home=$clientHome","schizoid.devclient.project=$root",
        "schizoid.devclient.gameDir=$gameDir","schizoid.devclient.launch=$($state.launch)") + $entries
    if ($FreezePlayer) { $properties += 'schizoid.spatial.freezePlayer=true' }
    $propertyArg = $properties -join ','
    Assert-CmdSafe $propertyArg
    $launchScript = @"
@echo off
chcp 65001 >nul
setlocal DisableDelayedExpansion
cd /d "$root"
call "$root\gradlew.bat" runClient --offline "-PisolatedGameDir=$gameDir" "-PgameProp=$propertyArg" "-PquickPlaySave=$World" > "$log" 2>&1
exit /b %errorlevel%
"@
    [IO.File]::WriteAllText($bat,$launchScript,(New-Object Text.UTF8Encoding $false))
    Write-State $state
    Set-Content -LiteralPath $log -Value '' -Encoding UTF8
    $launcher = Start-Process -FilePath $env:ComSpec -ArgumentList ('/d /c ""' + $bat + '""') -WindowStyle Hidden -PassThru
    $state.launcherPid = $launcher.Id
    $state.launcherCreated = $launcher.StartTime.ToUniversalTime().Ticks.ToString()
    Write-State $state
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 700
        $client = Get-DevClient
        if ($client -and (Select-String -LiteralPath $log -Pattern 'Loaded \d+ advancements' -Quiet)) {
            Write-Output "start: tracked PID $($client.ProcessId), world-load marker observed; log: $log"
            return
        }
        $launcher.Refresh()
        if ($launcher.HasExited) { throw "runClient exited before readiness (exit $($launcher.ExitCode)); see $log" }
    }
    throw "start: TIMEOUT after $TimeoutSec seconds; readiness NOT confirmed. Client may still start; use stop with same -IsolatedDir. See $log"
}
function Resize-Client {
    $proc = Get-ClientWindow
    Initialize-WindowApi
    if (-not [McDevWin]::SetWindowPos($proc.MainWindowHandle,[IntPtr]::Zero,0,0,$Width,$Height,0x0016)) {
        throw 'resize: tracked window resize failed'
    }
    Write-Output "resize: $Width x $Height"
}
function Save-Shot {
    $proc = Get-ClientWindow
    $shots = Join-Path $gameDir 'screenshots'
    Assert-NoReparse $shots
    New-Item -ItemType Directory -Force -Path $shots | Out-Null
    $before = @(Get-ChildItem -LiteralPath $shots -Filter '*.png' -File | Select-Object -ExpandProperty Name)
    $requested = [datetime]::UtcNow
    Send-WindowMessage $proc 0x0100 0x71
    Start-Sleep -Milliseconds 90
    Send-WindowMessage $proc 0x0101 0x71
    $deadline = (Get-Date).AddSeconds(12)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 400
        $null = Get-ClientWindow
        $fresh = @(Get-ChildItem -LiteralPath $shots -Filter '*.png' -File |
            Where-Object { $_.Name -notin $before -and $_.LastWriteTimeUtc -ge $requested })
        if ($fresh.Count -gt 1) { throw 'shot: multiple fresh PNGs; ambiguous capture refused.' }
        if ($fresh.Count -eq 0) { continue }
        Assert-NoReparse $fresh[0].FullName
        $stream = $null
        try {
            $stream = [IO.File]::Open($fresh[0].FullName,'Open','Read','None')
            if ($stream.Length -lt 45) { continue }
            $signature = New-Object byte[] 8
            $null = $stream.Read($signature,0,8)
            $null = $stream.Seek(-12,'End')
            $tail = New-Object byte[] 12
            $null = $stream.Read($tail,0,12)
            if ([BitConverter]::ToString($signature) -ne '89-50-4E-47-0D-0A-1A-0A' -or
                [BitConverter]::ToString($tail) -ne '00-00-00-00-49-45-4E-44-AE-42-60-82') { continue }
            $destination = [IO.Path]::GetFullPath($Out)
            Assert-NoReparse $destination
            if ($destination -eq $fresh[0].FullName) { throw 'shot: Out must differ from source screenshot.' }
            $target = [IO.File]::Open($destination,'Create','Write','None')
            try { $stream.Position=0; $stream.CopyTo($target) } finally { $target.Dispose() }
            Write-Output "shot: fresh F2 PNG $($fresh[0].Name) -> $destination"
            return
        } catch [IO.IOException] {
            # Screenshot may still be open; retry until capture deadline, never fall back.
        } finally { if ($stream) { $stream.Dispose() } }
    }
    throw 'shot: F2 produced no fresh complete PNG in 12s; no fallback capture attempted.'
}
function Send-Key {
    $proc = Get-ClientWindow
    for ($i=0; $i -lt $Repeat; $i++) {
        Send-WindowMessage $proc 0x0100 $Key
        Start-Sleep -Milliseconds 60
        Send-WindowMessage $proc 0x0101 $Key
        Start-Sleep -Milliseconds 220
    }
    Write-Output "key: $Key x$Repeat"
}
function Quit-Client {
    $proc = Get-ClientWindow
    Send-WindowMessage $proc 0x0010
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        if (-not (Get-DevClient)) { Write-Output 'quit: tracked client exited'; return }
        Start-Sleep -Milliseconds 500
    }
    throw 'quit: TIMEOUT, tracked process still alive (not force-killed).'
}
try {
    switch ($Action) {
        'stop' { Stop-Client }
        'quit' { Quit-Client }
        'start' { Start-Client }
        'shot' { Save-Shot }
        'resize' { Resize-Client }
        'key' { Send-Key }
        'cycle' { Stop-Client; Start-Client; Save-Shot }
    }
} finally { $commandLock.Dispose() }
