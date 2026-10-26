# Per-slot vertex diff: our runtime vs official spine-libgdx.
# Tells you *which slot* is wrong (face / cloth / weapon) instead of "the whole model differs".
#
#   powershell -NoProfile -File tools\spine-oracle\slotdiff.ps1
#   powershell -NoProfile -File tools\spine-oracle\slotdiff.ps1 -Only texas,surtr
#   powershell -NoProfile -File tools\spine-oracle\slotdiff.ps1 -Anim Attack -Time 0.3 -Top 8
#
# NOTE: RegionAttachment is skipped on the oracle side -- the oracle loads no texture atlas,
# so its region quads collapse onto the bone origin (a known artifact of the harness).

param(
    [string]$Anim = 'Idle',
    [string]$Time = '0.5',
    [string]$Only = '',
    [string]$ViewerRoot = '',
    [int]$Top = 6,
    [double]$Threshold = 1.0
)

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $here '..\..')
if (-not $ViewerRoot) { $ViewerRoot = $env:SPINE_VIEWER_ROOT }
if (-not $ViewerRoot) { $ViewerRoot = Join-Path $repo '..\character-viewer' }
$viewer = Resolve-Path $ViewerRoot
$dep35 = Join-Path $viewer 'fuzhu-viewer\angelina-viewer\target\dependency'
$dep38 = Join-Path $viewer 'jinwei-viewer\maenna-viewer\target\dependency'
$spine35 = "$dep35\spine-libgdx-3.5.51.1.jar;$dep35\gdx-1.10.0.jar"
$spine38 = "$dep38\spine-libgdx-3.8.99.1.jar;$dep35\gdx-1.10.0.jar"

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$classes = Join-Path $repo 'target\classes'
$out = Join-Path $here 'out'
$out35 = Join-Path $here 'out35'
$out38 = Join-Path $here 'out38'
$v38 = @('eyjafjalla_the_hvit_aska', 'ines', 'kroos', 'mlynar', 'reed')
$root = Join-Path $repo 'data\spine\operator'
$want = if ($Only) { $Only.Split(',') } else { $null }

function Get-SlotInfo($lines) {
    $map = @{}
    foreach ($l in $lines) {
        $m = [regex]::Match($l, '^S (\d+) (\S+) (\S+) a=([0-9.]+)\s*(\S+)?')
        if (-not $m.Success) { continue }
        $type = if ($m.Groups[5].Success) { $m.Groups[5].Value } else { '?' }
        $map[[int]$m.Groups[1].Value] = @{
            label = "$($m.Groups[2].Value) -> $($m.Groups[3].Value)"
            type = $type
        }
    }
    return $map
}

function Get-Points($lines) {
    $map = @{}
    foreach ($l in $lines) {
        $m = [regex]::Match($l, '^V (\d+) (\d+) (.*)$')
        if (-not $m.Success) { continue }
        $slot = [int]$m.Groups[1].Value
        $n = [int]$m.Groups[2].Value
        $toks = $m.Groups[3].Value.Trim() -split ' '
        $pts = New-Object 'System.Collections.Generic.List[double[]]'
        for ($i = 0; $i -lt $n -and $i -lt $toks.Count; $i++) {
            $p = $toks[$i].Split(',')
            $pts.Add([double[]]@([double]$p[0], [double]$p[1]))
        }
        $map[$slot] = $pts
    }
    return $map
}

$summary = @()
foreach ($dir in Get-ChildItem $root -Directory | Sort-Object Name) {
    $id = $dir.Name
    if ($want -and -not ($want -contains $id)) { continue }
    $skel = (Get-ChildItem $dir.FullName -Filter *.skel | Select-Object -First 1).FullName
    if ($v38 -contains $id) {
        $o = & $java -cp "$out38;$spine38" Oracle38 $skel $Anim $Time 2>&1
    } else {
        $o = & $java -cp "$out35;$spine35" Oracle35 $skel $Anim $Time 2>&1
    }
    $y = & $java '-Dfile.encoding=UTF-8' -cp "$out;$classes" com.yestercar.runtime.spine.SpineDump `
        $dir.FullName $Anim $Time 2>&1

    $info = Get-SlotInfo $o
    $op = Get-Points $o
    $yp = Get-Points $y

    $bad = @()
    $checked = 0
    foreach ($k in $op.Keys) {
        $t = if ($info.ContainsKey($k)) { $info[$k].type } else { '?' }
        if ($t -eq 'Region') { continue }
        if (-not $yp.ContainsKey($k)) { continue }
        $checked++
        $a = $op[$k]; $b = $yp[$k]
        if ($a.Count -ne $b.Count) {
            $bad += [pscustomobject]@{ slot = $k; label = $info[$k].label; max = 1e9; note = "count $($a.Count)vs$($b.Count)" }
            continue
        }
        $max = 0.0
        for ($i = 0; $i -lt $a.Count; $i++) {
            if ([math]::Abs($a[$i][0]) -gt 1e6 -or [math]::Abs($a[$i][1]) -gt 1e6) { continue }
            $dx = [math]::Abs($a[$i][0] - $b[$i][0])
            $dy = [math]::Abs($a[$i][1] - $b[$i][1])
            if ($dx -gt $max) { $max = $dx }
            if ($dy -gt $max) { $max = $dy }
        }
        if ($max -gt $Threshold) {
            $bad += [pscustomobject]@{ slot = $k; label = $info[$k].label; max = [math]::Round($max, 1); note = '' }
        }
    }
    $summary += [pscustomobject]@{
        id = $id
        checked = $checked
        badSlots = $bad.Count
        worst = ($bad | Sort-Object max -Descending | Select-Object -First $Top)
    }
}

foreach ($r in $summary) {
    if ($r.badSlots -eq 0) {
        "{0,-26} ok  ({1} mesh slots)" -f $r.id, $r.checked
        continue
    }
    "{0,-26} {1}/{2} mesh slot(s) off" -f $r.id, $r.badSlots, $r.checked
    foreach ($b in $r.worst) {
        "        slot {0,3}  {1,-40} max={2} {3}" -f $b.slot, $b.label, $b.max, $b.note
    }
}
