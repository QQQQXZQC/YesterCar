# Run the whole demo capture: 29 operators, one real battle each.
# Medics get a defender partner (a lone medic never acts: it only heals wounded allies).
# ASCII only -- see the note in capture-battle.ps1.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$capture = Join-Path $PSScriptRoot "capture-battle.ps1"

$medics = @("kaltsit", "reed", "warfarin", "eyjafjalla_the_hvit_aska")
$all = @(
    "amiya", "angelina", "april", "bagpipe", "blaze", "chen", "cuora", "dusk",
    "exusiai", "eyjafjalla", "eyjafjalla_the_hvit_aska", "gumi", "hoshiguma",
    "ines", "kaltsit", "kroos", "mlynar", "mostima", "myrtle", "podenco",
    "reed", "saria", "shamare", "skadi", "surtr", "suzuran", "texas", "w", "warfarin"
)
$fighters = $all | Where-Object { $medics -notcontains $_ }

Write-Host "=== 1/2 fighters: $($fighters.Count) ==="
& powershell -NoProfile -ExecutionPolicy Bypass -File $capture -Ids ($fighters -join ",")

Write-Host "=== 2/2 medics: $($medics.Count) (with cuora as ally) ==="
foreach ($m in $medics) {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $capture -Ids $m -Ally cuora
}

Write-Host "=== capture all done ==="
