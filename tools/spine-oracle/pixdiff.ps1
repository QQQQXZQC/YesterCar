# Pixel-level comparison: official spine-libgdx render vs YesterCar's in-game renderer.
#
#   powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1 -Build
#   powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1                     # all, Idle@mid
#   powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1 -Only texas,mlynar
#   powershell -NoProfile -File tools\spine-oracle\pixdiff.ps1 -Anim Attack -OutDir D:\shots\cmp
#
# 每张图用的是**同一个相机**（世界原点在脚底、中心 (0,200)、1.1 px/单位、512×512），
# 所以两边可以直接逐像素比。差异百分比是"亮度差 > 24"的像素占比。

param(
    [string]$Anim = 'Idle',
    [string]$Time = 'mid',
    [string]$Only = '',
    [string]$ViewerRoot = '',
    [string]$OutDir = '',
    [switch]$Build
)

$ErrorActionPreference = 'Continue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$repo = Resolve-Path (Join-Path $here '..\..')
if (-not $ViewerRoot) { $ViewerRoot = $env:SPINE_VIEWER_ROOT }
if (-not $ViewerRoot) { $ViewerRoot = Join-Path $repo '..\character-viewer' }
$viewer = Resolve-Path $ViewerRoot
$dep35 = Join-Path $viewer 'fuzhu-viewer\angelina-viewer\target\dependency'
$dep38 = Join-Path $viewer 'jinwei-viewer\maenna-viewer\target\dependency'
if (-not $OutDir) { $OutDir = Join-Path $here 'shots' }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
$javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
$classes = Join-Path $repo 'target\classes'
$jar = Join-Path $repo 'target\YesterCar.jar'
$out = Join-Path $here 'out'
$out35 = Join-Path $here 'out35'
$out38 = Join-Path $here 'out38'

$lwjgl = @(
    'gdx-1.10.0.jar', 'gdx-backend-lwjgl3-1.10.0.jar',
    'gdx-platform-1.10.0-natives-desktop.jar',
    'lwjgl-3.2.3.jar', 'lwjgl-3.2.3-natives-windows.jar',
    'lwjgl-glfw-3.2.3.jar', 'lwjgl-glfw-3.2.3-natives-windows.jar',
    'lwjgl-opengl-3.2.3.jar', 'lwjgl-opengl-3.2.3-natives-windows.jar',
    'lwjgl-jemalloc-3.2.3.jar', 'lwjgl-jemalloc-3.2.3-natives-windows.jar',
    'lwjgl-openal-3.2.3.jar', 'lwjgl-openal-3.2.3-natives-windows.jar'
)
function Jars($dep) {
    return (@($lwjgl | ForEach-Object { Join-Path $dep $_ }) + (Join-Path $dep 'jlayer-1.0.1-gdx.jar') + (Join-Path $dep 'jorbis-0.0.17.jar')) -join ';'
}
$cp35 = (Join-Path $dep35 'spine-libgdx-3.5.51.1.jar') + ';' + (Jars $dep35)
$cp38 = (Join-Path $dep38 'spine-libgdx-3.8.99.1.jar') + ';' + (Jars $dep35)

if ($Build -or -not (Test-Path $out35)) {
    New-Item -ItemType Directory -Force -Path $out, $out35, $out38 | Out-Null
    & $javac -encoding UTF-8 -cp $cp35 -d $out35 (Join-Path $here 'java\OfficialShot.java')
    & $javac -encoding UTF-8 -cp $cp38 -d $out38 (Join-Path $here 'java\OfficialShot.java')
    & $javac -encoding UTF-8 -d $out (Join-Path $here 'java\PixelDiff.java')
    Write-Host "compiled -> $out"
}

$v38 = @('eyjafjalla_the_hvit_aska', 'ines', 'kroos', 'mlynar', 'reed')
$root = Join-Path $repo 'data\spine\operator'
$want = if ($Only) { $Only.Split(',') } else { $null }

$rows = @()
foreach ($dir in Get-ChildItem $root -Directory | Sort-Object Name) {
    $id = $dir.Name
    if ($want -and -not ($want -contains $id)) { continue }
    $skel = (Get-ChildItem $dir.FullName -Filter *.skel | Select-Object -First 1).FullName
    $atlas = (Get-ChildItem $dir.FullName -Filter *.atlas | Select-Object -First 1).FullName
    $off = Join-Path $OutDir "official-$id.png"
    $our = Join-Path $OutDir "ours-$id.png"
    $heat = Join-Path $OutDir "heat-$id.png"

    $cp = if ($v38 -contains $id) { $cp38 } else { $cp35 }
    $osub = if ($v38 -contains $id) { $out38 } else { $out35 }
    # 官方渲染要开一个隐藏的 GLFW 窗口；偶尔会 "Failed to create interface"，重试几次。
    if (Test-Path $off) { Remove-Item $off -Force }
    for ($attempt = 0; $attempt -lt 4 -and -not (Test-Path $off); $attempt++) {
        & $java '-Dfile.encoding=UTF-8' -cp "$osub;$cp" OfficialShot $skel $atlas $Anim $Time $off 2>$null | Out-Null
        if (-not (Test-Path $off)) { Start-Sleep -Milliseconds 700 }
    }
    & $java '-Dfile.encoding=UTF-8' -cp $jar com.yestercar.runtime.view.SpineShot `
        $dir.FullName $Anim $Time $our 2>$null | Out-Null

    $r = & $java -cp $out PixelDiff $off $our $heat 2>$null
    $pct = 0.0
    foreach ($line in $r) {
        if ($line -match '^DIFF \d+ ([\d.]+)$') { $pct = [double]$Matches[1] }
    }
    $rows += [pscustomobject]@{ id = $id; diffPct = [math]::Round($pct, 3) }
}

$rows | Sort-Object diffPct -Descending | Format-Table -AutoSize | Out-String -Width 120
$ok = @($rows | Where-Object { $_.diffPct -le 1.0 }).Count
Write-Host "pixel diff <= 1%: $ok / $($rows.Count)"
Write-Host "images: $OutDir"
