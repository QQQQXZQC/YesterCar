# Capture per-operator in-battle frame sequences (one real battle per operator).
#
# Why one battle per operator: the deploy limit only allows a few units on field,
# while the request is to demo each operator individually. -Dyestercar.ui.squad
# goes through exactly the same director.deploy path as a player click.
#
# Output: <OutRoot>/<id>/frame-000.png ... plus <id>.log
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\spine-demo\capture-battle.ps1 -Ids texas,surtr
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools\spine-demo\capture-battle.ps1 -Ids reed -Ally cuora
#
# NOTE: keep this file ASCII-only. Windows PowerShell 5.1 decodes .ps1 as ANSI
# unless the file has a UTF-8 BOM, and Chinese comments then break parsing.
param(
    [Parameter(Mandatory = $true)][string[]]$Ids,
    [string]$Campaign = "_spine_demo",
    [int]$StartMs = 14000,
    [int]$Frames = 36,
    [int]$Interval = 55,
    [string]$OutRoot = "D:\dsHarness\Arknights\YesterCar\target\demo-shots",
    [int]$TimeoutMs = 120000,
    [string]$JavaHome = "C:\Program Files\Java21\LibericaJDK-21",
    # Optional training partner: a lone medic never acts (it only heals wounded
    # allies), so give it a front-liner to heal.
    [string]$Ally = ""
)

$ErrorActionPreference = "Stop"
$java = Join-Path $JavaHome "bin\java.exe"
$proj = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$jar = Join-Path $proj "target\YesterCar.jar"

if (-not (Test-Path $java)) { throw "java not found: $java" }
if (-not (Test-Path $jar)) { throw "jar not found: $jar (run mvn package first)" }

$flatIds = @()
foreach ($raw in $Ids) {
    foreach ($one in ($raw -split ",")) {
        if ($one.Trim()) { $flatIds += $one.Trim() }
    }
}

New-Item -ItemType Directory -Force -Path $OutRoot | Out-Null

foreach ($id in $flatIds) {
    $dir = Join-Path $OutRoot $id
    $log = Join-Path $OutRoot "$id.log"
    $err = Join-Path $OutRoot "$id.err.log"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    # Drop stale frames so a failed run cannot be mistaken for a good one.
    Get-ChildItem $dir -Filter "frame-*.png" -ErrorAction SilentlyContinue | Remove-Item -Force
    if (Test-Path $log) { Remove-Item $log -Force }
    if (Test-Path $err) { Remove-Item $err -Force }

    $squad = if ($Ally) { "$Ally,$id" } else { $id }
    $javaArgs = @(
        "-Dfile.encoding=UTF-8",
        "-Dyestercar.spine.debug=1",
        "-Dyestercar.ui.autodeploy=1",
        "-Dyestercar.ui.squad=$squad",
        "-Dyestercar.ui.seqshotdir=$dir",
        "-Dyestercar.ui.battleshotms=$StartMs",
        "-Dyestercar.ui.seqshotcount=$Frames",
        "-Dyestercar.ui.seqshotinterval=$Interval",
        "-Dyestercar.ui.shotexit=1",
        "-jar", $jar, "--play", $Campaign
    )
    Write-Host "[capture] $id (squad=$squad)"
    $p = Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $proj `
        -PassThru -RedirectStandardOutput $log -RedirectStandardError $err
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $done = $false
    while (-not $p.HasExited -and $sw.ElapsedMilliseconds -lt $TimeoutMs) {
        # Detect completion by frame count, not by matching console text: the
        # child's stdout is in the platform charset and Chinese markers would
        # need the same decoding on this side.
        $n = (Get-ChildItem $dir -Filter "frame-*.png" -ErrorAction SilentlyContinue).Count
        if ($n -ge $Frames) { $done = $true; break }
        Start-Sleep -Milliseconds 250
    }
    Start-Sleep -Milliseconds 600
    if (-not $p.HasExited) { try { $p.Kill() } catch { } }
    $n = (Get-ChildItem $dir -Filter "frame-*.png" -ErrorAction SilentlyContinue).Count
    Write-Host ("[capture] {0} done={1} frames={2} took={3:n1}s" -f $id, $done, $n, $sw.Elapsed.TotalSeconds)
    if (-not $done) {
        Write-Host "[capture] WARN $id has too few frames"
        if (Test-Path $log) { Get-Content $log -Tail 8 }
    }
}
Write-Host "[capture] all done -> $OutRoot"
