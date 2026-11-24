#requires -Version 5.1
<#
.SYNOPSIS
    Kiem logic nang cap cua setup\install.ps1 ma khong can quyen Administrator (M7.2).

.DESCRIPTION
    Tao mot goi cai gia lap (app, runtime, service la file danh dau), cai vao thu muc tam bang -SkipService, ghi
    mot file du lieu va doi cau hinh, roi cai lai goi phien ban moi. Kiem:
      - chuong trinh (app, runtime, service) duoc thay bang ban moi;
      - thu muc du lieu (DB, file -wal, backups) giu nguyen tung byte;
      - cau hinh application.properties giu nguyen (cong cu), khong tao lai first-run.properties.
    Khong kiem dich vu Windows va tuong lua (can may that, quyen Administrator).
#>
$ErrorActionPreference = 'Stop'
$root = Join-Path ([IO.Path]::GetTempPath()) ('ts-upgrade-' + [Guid]::NewGuid().ToString('N').Substring(0, 8))
$pkg = Join-Path $root 'pkg'
$prog = Join-Path $root 'Program Files\TrainingStats'
$data = Join-Path $root 'ProgramData\TrainingStats'
$fails = @()

function New-Package([string]$version) {
    if (Test-Path $pkg) { Remove-Item $pkg -Recurse -Force }
    foreach ($sub in 'app', 'runtime', 'service') {
        New-Item -ItemType Directory -Path (Join-Path $pkg $sub) -Force | Out-Null
        Set-Content -Path (Join-Path $pkg "$sub\marker.txt") -Value "$sub $version" -Encoding ASCII
    }
    Copy-Item (Join-Path $PSScriptRoot 'setup\*') $pkg -Recurse
    Set-Content -Path (Join-Path $pkg 'VERSION.txt') -Value $version -Encoding ASCII
}

# Chay install.ps1 o tien trinh rieng (no goi exit); SecureString tao ngay trong tien trinh do.
function Invoke-Install([string]$extra = '') {
    $cmd = "& '$pkg\install.ps1' -SkipService -InstallDir '$prog' -DataRoot '$data' " +
        "-AdminPassword (ConvertTo-SecureString 'MatKhauThu@1' -AsPlainText -Force) $extra; exit `$LASTEXITCODE"
    & powershell -NoProfile -ExecutionPolicy Bypass -Command $cmd | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "install.ps1 tra ma loi $LASTEXITCODE" }
}

function Get-Hash([string]$path) { (Get-FileHash -Path $path -Algorithm SHA256).Hash }

try {
    # Lan 1: cai moi.
    New-Package '1.0.0'
    Invoke-Install '-Port 8099'
    $config = Join-Path $data 'config\application.properties'
    if (-not (Test-Path $config)) { $fails += 'cai moi khong tao application.properties' }
    if (-not (Test-Path (Join-Path $data 'config\first-run.properties'))) { $fails += 'cai moi khong tao first-run.properties' }

    # Gia lap may dang chay: ung dung da doc va xoa first-run, co DB, WAL, ban sao luu, nguoi quan tri sua cau hinh.
    Remove-Item (Join-Path $data 'config\first-run.properties')
    $db = Join-Path $data 'data\trainingstats.db'
    New-Item -ItemType Directory -Path (Join-Path $data 'data'), (Join-Path $data 'backups\upgrade') -Force | Out-Null
    [IO.File]::WriteAllBytes($db, [byte[]](1..255))
    Set-Content -Path "$db-wal" -Value 'wal' -Encoding ASCII
    Set-Content -Path (Join-Path $data 'backups\trainingstats-auto-20261121-020000.db') -Value 'bak' -Encoding ASCII
    Add-Content -Path $config -Value 'app.read-pool-size=6' -Encoding ASCII
    $before = @{}
    Get-ChildItem $data -Recurse -File | ForEach-Object { $before[$_.FullName] = Get-Hash $_.FullName }

    # Lan 2: nang cap len ban moi (khong truyen -Port: giu cong cu).
    New-Package '1.1.0'
    Invoke-Install

    foreach ($sub in 'app', 'runtime', 'service') {
        $m = Get-Content (Join-Path $prog "$sub\marker.txt")
        if ($m -ne "$sub 1.1.0") { $fails += "chuong trinh $sub chua duoc thay: $m" }
    }
    if ((Get-Content (Join-Path $prog 'VERSION.txt')) -ne '1.1.0') { $fails += 'VERSION.txt chua duoc thay' }
    foreach ($f in $before.Keys) {
        if (-not (Test-Path $f)) { $fails += "mat file du lieu: $f"; continue }
        if ((Get-Hash $f) -ne $before[$f]) { $fails += "file du lieu bi doi: $f" }
    }
    if (Test-Path (Join-Path $data 'config\first-run.properties')) { $fails += 'nang cap tao lai first-run.properties (lo mat khau moi)' }
    if (-not ((Get-Content $config) -match '^server\.port=8099$')) { $fails += 'nang cap lam mat cong cu' }
}
catch { $fails += "loi: $($_.Exception.Message)" }
finally { Remove-Item $root -Recurse -Force -ErrorAction SilentlyContinue }

if ($fails.Count -eq 0) { Write-Host 'DAT: nang cap thay chuong trinh, giu nguyen du lieu va cau hinh.' -ForegroundColor Green; exit 0 }
Write-Host 'LOI:' -ForegroundColor Red
$fails | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
