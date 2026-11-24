#requires -Version 5.1
<#
.SYNOPSIS
    Cai dat hoac nang cap Training Statistics tren Windows (chay nhu dich vu).

.DESCRIPTION
    - Chuong trinh dat o $InstallDir (ghi de khi nang cap).
    - Du lieu, cau hinh, log dat o $DataRoot (KHONG bi dong cham khi nang cap hoac go cai dat).
    - Cai dich vu Windows "TrainingStats" tu dong khoi dong cung may, mo cong tuong lua.
    Phai chay bang quyen Administrator (tru khi dung -SkipService).

.PARAMETER Port
    Cong lang nghe. Bo trong de duoc hoi (cai moi) hoac giu nguyen (nang cap).

.PARAMETER AdminPassword
    Mat khau Super Admin ban dau (SecureString). Bo trong de duoc hoi. Chi dung khi cai moi.

.PARAMETER SkipService
    Chi sao chep file va tao cau hinh, khong cai dich vu, khong mo tuong lua. Dung de kiem thu.

.PARAMETER ServiceAccount
    Tai khoan chay dich vu. Mac dinh LocalService (it quyen: chi doc/ghi thu muc du lieu duoc cap quyen).
    LocalSystem chi dung khi can quay lai cach chay cu (quyen rong tren may).
#>
param(
    [int]$Port = 0,
    [SecureString]$AdminPassword,
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'TrainingStats'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'TrainingStats'),
    [switch]$AllowPublicNetwork,
    [switch]$SkipService,
    [ValidateSet('LocalService', 'LocalSystem')]
    [string]$ServiceAccount = 'LocalService'
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'TrainingStats'
$FirewallRule = 'TrainingStats HTTP'
$MinPasswordLength = 8

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "LOI: $text" -ForegroundColor Red; exit 1 }

# In cac dong cuoi cua log ung dung va log WinSW, de thay ngay nguyen nhan khi dich vu khong len.
function Show-LogTail($dir) {
    foreach ($name in 'app.log', 'TrainingStatsService.err.log', 'TrainingStatsService.out.log', 'TrainingStatsService.wrapper.log') {
        $f = Join-Path $dir $name
        if (Test-Path $f) {
            Write-Host "`n--- 25 dong cuoi cua $f" -ForegroundColor Yellow
            Get-Content $f -Tail 25 -Encoding UTF8 | ForEach-Object { Write-Host "    $_" }
        }
    }
    $hit = Get-ChildItem $dir -Filter '*.log' -ErrorAction SilentlyContinue | Select-String -Pattern 'checksum mismatch' -SimpleMatch -List
    if ($hit) {
        Write-Host "`nLog co 'checksum mismatch': file migration trong ban cai khac voi luc DB duoc tao. Xem docs/OPERATIONS-GUIDE.md muc 7.1." -ForegroundColor Yellow
    }
}

function Test-Admin {
    $p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    return $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

function Restrict-Acl($path) {
    # Che do -SkipService la de kiem thu bang tai khoan thuong: khoa quyen se tu chan chinh nguoi chay.
    if ($SkipService) { return }
    # CHI SYSTEM va Administrators duoc doc/ghi: dung ACL moi hoan toan, khong ke thua, de xoa ca quyen thua
    # (icacls /grant:r chi thay quyen cua hai nhom nay, giu nguyen quyen cua nguoi khac).
    # Co ke thua (OI)(CI) chi dat cho thu muc. Dat len mot FILE thi icacls van bao thanh cong nhung khong ai doc
    # duoc file, ke ca dich vu chay bang SYSTEM (loi ban M0: first-run.properties khong bao gio duoc doc va xoa).
    try {
        $isDir = Test-Path $path -PathType Container
        $acl = if ($isDir) { New-Object System.Security.AccessControl.DirectorySecurity } else { New-Object System.Security.AccessControl.FileSecurity }
        $acl.SetAccessRuleProtection($true, $false)
        $inherit = if ($isDir) { [System.Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit' } else { [System.Security.AccessControl.InheritanceFlags]::None }
        # SYSTEM, Administrators, va LocalService (S-1-5-19) khi dich vu chay bang tai khoan nay.
        $sids = @('S-1-5-18', 'S-1-5-32-544')
        if ($ServiceAccount -eq 'LocalService') { $sids += 'S-1-5-19' }
        foreach ($sid in $sids) {
            $id = New-Object System.Security.Principal.SecurityIdentifier($sid)
            $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($id, 'FullControl', $inherit, 'None', 'Allow')))
        }
        (Get-Item $path -Force).SetAccessControl($acl)
    }
    catch { Fail "Khong dat duoc quyen truy cap cho ${path}: $($_.Exception.Message)" }
}

if (-not $SkipService -and -not (Test-Admin)) {
    Fail 'Can chay bang quyen Administrator. Bam chuot phai install.cmd va chon Run as administrator.'
}

$source = $PSScriptRoot
foreach ($sub in 'app', 'runtime', 'service') {
    if (-not (Test-Path (Join-Path $source $sub))) { Fail "Thieu thu muc '$sub' canh install.ps1. Hay giai nen day du file zip." }
}

$configDir  = Join-Path $DataRoot 'config'
$configFile = Join-Path $configDir 'application.properties'
$dataDir    = Join-Path $DataRoot 'data'
$logDir     = Join-Path $DataRoot 'logs'
$dbFile     = Join-Path $dataDir 'trainingstats.db'
$isUpgrade  = Test-Path $configFile

# ---- Thu thap tham so ----
if ($isUpgrade) {
    Step 'Phat hien ban cai dat cu: nang cap (giu nguyen du lieu va cau hinh)'
    if ($Port -eq 0) {
        $line = Get-Content $configFile | Where-Object { $_ -match '^\s*server\.port\s*=' } | Select-Object -First 1
        if ($line) { $Port = [int](($line -split '=', 2)[1].Trim()) } else { $Port = 8080 }
    }
}
else {
    Step 'Cai dat moi'
    if ($Port -eq 0) {
        $answer = Read-Host 'Cong (port) cho may chu [8080]'
        if ([string]::IsNullOrWhiteSpace($answer)) { $Port = 8080 } else { $Port = [int]$answer }
    }
    if (-not $AdminPassword) {
        while ($true) {
            $p1 = Read-Host "Mat khau Super Admin (toi thieu $MinPasswordLength ky tu)" -AsSecureString
            $p2 = Read-Host 'Nhap lai mat khau' -AsSecureString
            $b1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1)
            $b2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p2)
            try {
                $s1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b1)
                $s2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b2)
                if ($s1.Length -lt $MinPasswordLength) { Write-Host "Mat khau qua ngan." -ForegroundColor Yellow }
                elseif ($s1 -ne $s2) { Write-Host 'Hai lan nhap khong khop.' -ForegroundColor Yellow }
                else { $AdminPassword = $p1; break }
            }
            finally {
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b1)
                [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b2)
            }
        }
    }
}
if ($Port -lt 1024 -or $Port -gt 65535) { Fail "Cong $Port khong hop le (1024-65535)." }

# -SkipService (kiem thu) khong dung toi dich vu that dang chay tren may, ke ca khi da co.
$existing = if ($SkipService) { $null } else { Get-Service -Name $ServiceName -ErrorAction SilentlyContinue }
if (-not $existing -and -not $SkipService) {
    $busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
    if ($busy) { Fail "Cong $Port dang duoc chuong trinh khac su dung. Chon cong khac bang -Port." }
}

# ---- Dung dich vu cu ----
if ($existing -and $existing.Status -ne 'Stopped') {
    Step 'Dung dich vu hien tai'
    Stop-Service -Name $ServiceName -Force
    $existing.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
}

# ---- Chuong trinh ----
Step "Sao chep chuong trinh vao $InstallDir"
New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
foreach ($sub in 'app', 'runtime', 'service') {
    $target = Join-Path $InstallDir $sub
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    Copy-Item (Join-Path $source $sub) $target -Recurse
}
foreach ($f in 'VERSION.txt', 'uninstall.ps1', 'uninstall.cmd', 'check.ps1', 'reset-superadmin.ps1') {
    if (Test-Path (Join-Path $source $f)) { Copy-Item (Join-Path $source $f) (Join-Path $InstallDir $f) -Force }
}

# ---- Du lieu va cau hinh ----
Step "Chuan bi thu muc du lieu $DataRoot"
foreach ($d in $DataRoot, $configDir, $dataDir, $logDir) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
# Dat lai ca khi nang cap, de sua cac ban cai truoc da de sot quyen thua.
Restrict-Acl $DataRoot

if (-not $isUpgrade -or $PSBoundParameters.ContainsKey('Port')) {
    $dataDirFwd = $dataDir.Replace('\', '/')
    $logFileFwd = (Join-Path $logDir 'app.log').Replace('\', '/')
    $lines = @(
        "server.port=$Port",
        "app.data-dir=$dataDirFwd",
        "logging.file.name=$logFileFwd"
    )
    Set-Content -Path $configFile -Value $lines -Encoding ASCII
}

if (-not $isUpgrade -and -not (Test-Path $dbFile)) {
    Step 'Luu mat khau Super Admin ban dau (ung dung se doc roi xoa file nay o lan chay dau)'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($AdminPassword)
    try {
        $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
        $firstRun = Join-Path $configDir 'first-run.properties'
        Set-Content -Path $firstRun -Value "app.first-run.admin-password=$plain" -Encoding ASCII
        Restrict-Acl $firstRun
    }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
}
elseif ($isUpgrade -and (Test-Path (Join-Path $configDir 'first-run.properties'))) {
    # Ban M0 dat sai quyen tren file nay nen ung dung khong doc, khong xoa duoc. Sua quyen de lan chay toi
    # ung dung tu xu ly (tao Super Admin neu chua co, hoac bo qua) roi xoa file.
    Step 'Sua quyen first-run.properties con sot tu ban cu'
    Restrict-Acl (Join-Path $configDir 'first-run.properties')
}

if ($SkipService) {
    Write-Host "`nDa bo qua dich vu va tuong lua (-SkipService). Chuong trinh o $InstallDir, du lieu o $DataRoot." -ForegroundColor Yellow
    exit 0
}

# ---- Dich vu ----
$winsw = Join-Path $InstallDir 'service\TrainingStatsService.exe'
if (-not (Get-Service -Name $ServiceName -ErrorAction SilentlyContinue)) {
    Step 'Cai dich vu Windows'
    & $winsw install
    if ($LASTEXITCODE -ne 0) { Fail 'Cai dich vu that bai.' }
}
# Dat tai khoan dich vu moi lan cai (ca khi nang cap tu ban chay LocalSystem).
Step "Dat tai khoan chay dich vu: $ServiceAccount"
# --% giu nguyen tham so: PowerShell 5.1 bo mat chuoi rong (password= "") khi goi chuong trinh ngoai.
if ($ServiceAccount -eq 'LocalService') { & sc.exe --% config TrainingStats obj= "NT AUTHORITY\LocalService" password= "" | Out-Null }
else { & sc.exe config $ServiceName obj= 'LocalSystem' | Out-Null }
if ($LASTEXITCODE -ne 0) { Fail "Khong dat duoc tai khoan dich vu $ServiceAccount." }

# ---- Tuong lua ----
Step "Mo cong $Port tren tuong lua"
Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
$profiles = if ($AllowPublicNetwork) { 'Any' } else { 'Domain,Private' }
New-NetFirewallRule -DisplayName $FirewallRule -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port -Profile $profiles | Out-Null

# ---- Khoi dong va kiem tra ----
Step 'Khoi dong dich vu'
try { Start-Service -Name $ServiceName -ErrorAction Stop }
catch {
    Show-LogTail $logDir
    Fail "Khong khoi dong duoc dich vu: $($_.Exception.Message) Xem log o $logDir"
}
# Ung dung can vai giay den vai chuc giay de khoi dong (lan dau, hoac khi nang cap du lieu). In dau cham de biet
# script van dang cho. Ung dung loi khi khoi dong thi tien trinh thoat va dich vu chuyen Stopped: dung ngay, khong
# cho het thoi gian.
Write-Host "Dang cho ung dung tra loi tai http://localhost:$Port/ (toi da 3 phut) " -NoNewline
$ok = $false
$stoppedChecks = 0
$deadline = (Get-Date).AddMinutes(3)
while ((Get-Date) -lt $deadline) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/" -UseBasicParsing -TimeoutSec 3
        if ($r.StatusCode -eq 200) { $ok = $true; break }
    }
    catch { }
    $status = (Get-Service -Name $ServiceName).Status
    if ($status -eq 'Stopped') { $stoppedChecks++ } else { $stoppedChecks = 0 }
    # hai lan lien tiep thay Stopped (khong phai khoang dung ngan giua hai lan WinSW tu khoi dong lai)
    if ($stoppedChecks -ge 2) { break }
    Write-Host '.' -NoNewline
    Start-Sleep -Seconds 2
}
Write-Host ''
if (-not $ok) {
    Show-LogTail $logDir
    $state = (Get-Service -Name $ServiceName).Status
    Fail "Ung dung khong tra loi tai cong $Port (dich vu dang $state). Xem cac dong log o tren hoac thu muc $logDir"
}

Write-Host "`nCai dat thanh cong. Truy cap tu may khac trong mang LAN:" -ForegroundColor Green
if ($isUpgrade) {
    Write-Host "  (Nang cap: neu chua co tai khoan Super Admin, chay $(Join-Path $InstallDir 'reset-superadmin.ps1') de tao.)" -ForegroundColor Yellow
}
# Chi liet ke mang that (dang ket noi va co cong mac dinh), bo qua card ao cua VMware, Hyper-V, WSL.
$realNets = @(Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address -and $_.NetAdapter.Status -eq 'Up' })
$publicNets = @()
foreach ($n in $realNets) {
    $category = (Get-NetConnectionProfile -InterfaceIndex $n.InterfaceIndex -ErrorAction SilentlyContinue).NetworkCategory
    Write-Host ("  http://{0}:{1}/   ({2}, mang {3})" -f $n.IPv4Address.IPAddress, $Port, $n.InterfaceAlias, $category)
    if ($category -eq 'Public') { $publicNets += $n.InterfaceAlias }
}
if ($realNets.Count -eq 0) {
    Write-Host '  Khong tim thay mang nao dang ket noi co cong mac dinh. Dung ipconfig de xem dia chi IP cua may.' -ForegroundColor Yellow
}
if ($publicNets.Count -gt 0 -and -not $AllowPublicNetwork) {
    Write-Host ("`nCANH BAO: mang {0} dang duoc Windows coi la Public, nen may khac se KHONG vao duoc (tuong lua chi mo cho Domain/Private)." -f ($publicNets -join ', ')) -ForegroundColor Yellow
    Write-Host '  Cach 1 (khuyen nghi): doi mang sang Private trong Settings > Network & internet > Wi-Fi > ten mang > Network profile type.' -ForegroundColor Yellow
    Write-Host '  Cach 2: chay lai install.ps1 -AllowPublicNetwork (mo cong ca tren mang Public, chi dung khi may chu khong roi mang tin cay).' -ForegroundColor Yellow
}
