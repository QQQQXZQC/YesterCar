# Compare YesterCar's hand-written Spine runtime against the official spine-libgdx runtime.
#
#   powershell -NoProfile -File tools\spine-oracle\diff.ps1 -Build      # 先编译
#   powershell -NoProfile -File tools\spine-oracle\diff.ps1             # Idle @ 0.5s
#   powershell -NoProfile -File tools\spine-oracle\diff.ps1 -Anim Attack -Time 0.3
#   powershell -NoProfile -File tools\spine-oracle\diff.ps1 -Only texas,bagpipe
#
# 三个 jar（spine-libgdx 3.5.51.1 / 3.8.99.1 / gdx 1.10.0）从 character-viewer 借，
# 位置由 -ViewerRoot 或环境变量 SPINE_VIEWER_ROOT 指定。

param(
    [string]$Anim = 'Idle',
    [string]$Time = '0.5',
    [string]$Only = '',
    [string]$ViewerRoot = '',
    [switch]$Build
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
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
$classes = Join-Path $repo 'target\classes'
$out = Join-Path $here 'out'
$out35 = Join-Path $here 'out35'
$out38 = Join-Path $here 'out38'

if (-not (Test-Path $classes)) { throw "先构建主工程（mvn package）：缺少 $classes" }

if ($Build -or -not (Test-Path $out)) {
    New-Item -ItemType Directory -Force -Path $out, $out35, $out38 | Out-Null
    & $javac -encoding UTF-8 -cp "$spine35" -d $out35 (Join-Path $here 'java\Oracle35.java')
    & $javac -encoding UTF-8 -cp "$spine38" -d $out38 (Join-Path $here 'java\Oracle38.java')
    & $javac -encoding UTF-8 -cp $classes -d $out (Join-Path $here 'java\com\yestercar\runtime\spine\SpineDump.java')
    Write-Host "compiled -> $out"
}

$v38 = @('eyjafjalla_the_hvit_aska', 'ines', 'kroos', 'mlynar', 'reed')
$root = Join-Path $repo 'data\spine\operator'
$want = if ($Only) { $Only.Split(',') } else { $null }

function Get-Bones($lines) {
    $map = @{}
    foreach ($l in $lines) {
        $m = [regex]::Match($l, '^B (\d+) (.+?) (-?\d+\.\d+) (-?\d+\.\d+) (-?\d+\.\d+)$')
        if ($m.Success) {
            $map[[int]$m.Groups[1].Value] = @{
                name = $m.Groups[2].Value
                x = [double]$m.Groups[3].Value
                y = [double]$m.Groups[4].Value
                rot = [double]$m.Groups[5].Value
            }
        }
    }
    return $map
}

function Get-Slots($lines) {
    return @($lines | Where-Object { $_ -match '^S \d' })
}

$rows = @()
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

    $ob = Get-Bones $o
    $yb = Get-Bones $y
    $maxD = 0.0
    $maxR = 0.0
    $worst = ''
    foreach ($k in $ob.Keys) {
        if (-not $yb.ContainsKey($k)) { continue }
        $dx = $ob[$k].x - $yb[$k].x
        $dy = $ob[$k].y - $yb[$k].y
        $d = [math]::Sqrt($dx * $dx + $dy * $dy)
        if ($d -gt $maxD) { $maxD = $d; $worst = $ob[$k].name }
        $dr = [math]::Abs($ob[$k].rot - $yb[$k].rot)
        if ($dr -gt 180) { $dr = 360 - $dr }
        if ($dr -gt $maxR) { $maxR = $dr }
    }

    $os = Get-Slots $o
    $ys = Get-Slots $y
    $slotDiff = 0
    for ($i = 0; $i -lt [math]::Min($os.Count, $ys.Count); $i++) {
        if ($os[$i] -ne $ys[$i]) { $slotDiff++ }
    }

    $rows += [pscustomobject]@{
        id = $id
        slotDiff = $slotDiff
        boneMax = [math]::Round($maxD, 2)
        rotMax = [math]::Round($maxR, 2)
        worstBone = $worst
    }
}

$rows | Format-Table -AutoSize | Out-String -Width 200

$ok = @($rows | Where-Object { $_.boneMax -le 1.5 }).Count
Write-Host "bones within 1.5 unit: $ok / $($rows.Count)"
Write-Host "slots (attachment+alpha) identical: $(@($rows | Where-Object { $_.slotDiff -eq 0 }).Count) / $($rows.Count)"
