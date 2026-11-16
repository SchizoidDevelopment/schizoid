param(
    [Parameter(Mandatory=$true)][string]$BindingClasspath,
    [Parameter(Mandatory=$true)][string]$NativeDirectory,
    [string]$FontDirectory = (Join-Path $PSScriptRoot '../../src/main/resources/assets/schizoid/fonts'),
    [string]$JavaHome = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome) { throw 'Provide -JavaHome or set JAVA_HOME to a JDK.' }
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
foreach ($file in @($java, $javac, (Join-Path $NativeDirectory 'imgui-java64.dll'))) {
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing required file: $file" }
}
$fontDir = (Resolve-Path -LiteralPath $FontDirectory).Path
foreach ($name in @('OpenSans-Regular.ttf','OpenSans-Bold.ttf','fa-solid-900.ttf')) {
    if (-not (Test-Path -LiteralPath (Join-Path $fontDir $name) -PathType Leaf)) { throw "Missing font: $name" }
}
$nativeDir = (Resolve-Path -LiteralPath $NativeDirectory).Path
$work = Join-Path ([System.IO.Path]::GetTempPath()) ("schizoid-imgui-tests-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    $sources = @('FontsExtended.java','TwoAtlas.java','FileOverloads.java') | ForEach-Object { Join-Path $PSScriptRoot "tests/$_" }
    & $javac -encoding UTF-8 -cp $BindingClasspath -d $work @sources
    if ($LASTEXITCODE -ne 0) { throw "javac failed: $LASTEXITCODE" }
    $cp = "$work;$BindingClasspath"
    foreach ($test in @('FontsExtended','TwoAtlas','FileOverloads')) {
        & $java --enable-native-access=ALL-UNNAMED -Xcheck:jni "-Dimgui.library.path=$nativeDir" -cp $cp $test $fontDir
        if ($LASTEXITCODE -ne 0) { throw "$test failed: $LASTEXITCODE" }
    }
} finally {
    # The target is a freshly created unique temp directory, resolved before deletion.
    if ((Resolve-Path -LiteralPath $work).Path -ne $work) { throw 'Unexpected test directory path' }
    Remove-Item -LiteralPath $work -Recurse -Force
}
