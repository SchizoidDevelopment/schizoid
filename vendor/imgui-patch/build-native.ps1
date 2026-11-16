param(
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$archive = Join-Path $here 'native-jni-sources.zip'
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne '520EC6FBA341A6C4F1CE3AB90F774CF999A3BF26572C46ED846DB1835F6A4D4D') { throw 'Native source archive hash mismatch' }
if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue) -or -not (Get-Command link.exe -ErrorAction SilentlyContinue)) { throw 'Run in a Visual Studio x64 Developer PowerShell with cl.exe and link.exe available.' }
$dst = [System.IO.Path]::GetFullPath($OutputDirectory)
if (Test-Path -LiteralPath $dst) { throw "Output directory already exists: $dst" }
New-Item -ItemType Directory -Path $dst | Out-Null
$jni = Join-Path $dst 'jni'
$objects = Join-Path $dst 'objects'
Expand-Archive -LiteralPath $archive -DestinationPath $jni
New-Item -ItemType Directory -Path $objects | Out-Null
$flags = @('/nologo','/c','/O2','/EHsc','/std:c++14','/MD','/DWIN32',"/I$jni", "/I$(Join-Path $jni 'jni-headers')", "/I$(Join-Path $jni 'jni-headers\win32')")
$sources = Get-Content -LiteralPath (Join-Path $here 'sources.txt') | ForEach-Object {
    if ($_ -notmatch '^[A-Za-z0-9_]+\.cpp$') { throw "Invalid source name: $_" }
    $path = Join-Path $jni $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing source: $path" }
    $path
}
Push-Location $objects
try {
    & cl.exe @flags @sources
    if ($LASTEXITCODE -ne 0) { throw "Native compilation failed: $LASTEXITCODE" }
    $obj = @(Get-ChildItem -LiteralPath $objects -Filter '*.obj' -File | ForEach-Object { $_.FullName })
    if ($obj.Count -ne $sources.Count) { throw "Expected $($sources.Count) objects, found $($obj.Count)" }
    $dll = Join-Path $dst 'imgui-java64.dll'
    & link.exe /nologo /DLL "/OUT:$dll" @obj
    if ($LASTEXITCODE -ne 0) { throw "Native link failed: $LASTEXITCODE" }
    Write-Host "Built $dll"
    Get-FileHash -LiteralPath $dll -Algorithm SHA256 | Format-List
} finally { Pop-Location }
