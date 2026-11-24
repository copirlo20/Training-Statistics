#requires -Version 5.1
<#
.SYNOPSIS
    Kiem tra nhanh Training Statistics da cai dung chua. Chay tren may chu, nen dung quyen Administrator.

.DESCRIPTION
    In [DAT]/[LOI]/[INFO] cho tung muc, de dung khi nghiem thu o may Windows sach hoac sau khi khoi dong lai may.
    Khong sua doi gi tren he thong.
#>
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'TrainingStats'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'TrainingStats')
)

$ServiceName = 'TrainingStats'
$FirewallRule = 'TrainingStats HTTP'
$script:fail = 0

function Pass($text) { Write-Host "[DAT]  $text" -ForegroundColor Green }
function Bad($text)  { Write-Host "[LOI]  $text" -ForegroundColor Red; $script:fail++ }
function Info($text) { Write-Host "[INFO] $text" -ForegroundColor Gray }
function Title($text) { Write-Host "`n--- $text" -ForegroundColor Cyan }

$isAdmin = (New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Write-Host "Training Statistics - kiem tra cai dat ($env:COMPUTERNAME, $(Get-Date -Format 'yyyy-MM-dd HH:mm'))"
if (-not $isAdmin) { Info 'Dang chay KHONG co quyen Administrator: mot so muc se khong doc duoc.' }

Title 'May chu'
$hasJava = [bool](Get-Command java -ErrorAction SilentlyContinue) -or [bool]$env:JAVA_HOME
if ($hasJava) { Info 'May nay CO Java (PATH hoac JAVA_HOME). Khong phai may sach, ket qua khong chung minh duoc viec khong can cai Java.' }
else { Info 'May nay KHONG co Java trong PATH va JAVA_HOME: dung la may sach cho muc dich kiem tra.' }
$boot = (Get-CimInstance Win32_OperatingSystem).LastBootUpTime
Info "Lan khoi dong may gan nhat: $boot"

Title 'Dich vu Windows'
$svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if (-not $svc) { Bad "Khong co dich vu $ServiceName." }
else {
    if ($svc.Status -eq 'Running') { Pass 'Dich vu dang chay.' } else { Bad "Dich vu dang o trang thai $($svc.Status)." }
    if ($svc.StartType -eq 'Automatic') { Pass 'Kieu khoi dong: Automatic (tu chay cung may).' } else { Bad "Kieu khoi dong la $($svc.StartType), can Automatic." }
    $wmi = Get-CimInstance Win32_Service -Filter "Name='$ServiceName'"
    Info "Chay bang tai khoan: $($wmi.StartName)"
}

Title 'Chuong trinh va tien trinh'
if ((Test-Path (Join-Path $InstallDir 'runtime\bin\java.exe')) -and (Test-Path (Join-Path $InstallDir 'app\trainingstats.jar'))) { Pass "Du JRE va ung dung o $InstallDir" }
else { Bad "Thieu runtime hoac app trong $InstallDir" }
$javaProc = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.ExecutablePath -and $_.ExecutablePath -like (Join-Path $InstallDir '*') } | Select-Object -First 1
if (-not $javaProc -and -not $isAdmin) {
    Info 'BO QUA tien trinh Java: tai khoan thuong khong xem duoc duong dan tien trinh cua dich vu. Chay lai bang Administrator.'
}
elseif ($javaProc) {
    Pass "Tien trinh Java cua dich vu dang chay (PID $($javaProc.ProcessId)), dung JRE dong kem."
    $started = $javaProc.CreationDate
    Info "Tien trinh bat dau luc: $started"
    $gap = ($started - $boot).TotalMinutes
    if ($gap -ge 0 -and $gap -le 15) { Pass ("Tien trinh bat dau sau khi khoi dong may {0:N1} phut: dich vu da tu chay." -f $gap) }
    else { Info 'Tien trinh bat dau khong sat sau lan khoi dong may. Neu dang kiem tra viec tu chay, hay khoi dong lai may roi chay lai script nay.' }
}
else { Bad 'Khong thay tien trinh java.exe cua dich vu.' }

Title 'Cong va truy cap'
$port = $null
$cfg = Join-Path $DataRoot 'config\application.properties'
try {
    $line = Get-Content $cfg -ErrorAction Stop | Where-Object { $_ -match '^\s*server\.port\s*=' } | Select-Object -First 1
    if ($line) { $port = [int](($line -split '=', 2)[1].Trim()) }
}
catch { Info 'Khong doc duoc file cau hinh (dung thiet ke neu khong phai Administrator).' }
if (-not $port -and $javaProc) {
    $l = Get-NetTCPConnection -State Listen -OwningProcess $javaProc.ProcessId -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($l) { $port = $l.LocalPort }
}
if (-not $port) {
    $anyRule = Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
    if ($anyRule) {
        $p0 = ($anyRule | Get-NetFirewallPortFilter).LocalPort
        if ("$p0" -match '^\d+$') { $port = [int]$p0; Info "Lay cong tu luat tuong lua: $port" }
    }
}
if (-not $port) { Bad 'Khong xac dinh duoc cong.' }
else {
    Info "Cong: $port"
    $listen = Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue
    if ($listen) { Pass "Co tien trinh dang lang nghe cong $port." } else { Bad "Khong co gi lang nghe cong $port." }
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$port/" -UseBasicParsing -TimeoutSec 5
        if ($r.StatusCode -eq 200) { Pass "http://localhost:$port/ tra ve HTTP 200." } else { Bad "http://localhost:$port/ tra ve $($r.StatusCode)." }
    }
    catch { Bad "http://localhost:$port/ khong tra loi: $($_.Exception.Message)" }
}

Title 'Tuong lua va mang LAN'
$rule = Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue
if (-not $rule) { Bad "Khong co luat tuong lua '$FirewallRule'." }
else {
    $pf = $rule | Get-NetFirewallPortFilter
    $ok = ($rule.Enabled -eq 'True') -and ($rule.Direction -eq 'Inbound') -and ($rule.Action -eq 'Allow') -and ($port -and "$($pf.LocalPort)" -eq "$port")
    if ($ok) { Pass "Luat '$FirewallRule': Inbound, Allow, cong $($pf.LocalPort), profile $($rule.Profile)." }
    else { Bad "Luat '$FirewallRule' khong dung (Enabled=$($rule.Enabled), Dir=$($rule.Direction), Action=$($rule.Action), Port=$($pf.LocalPort))." }
}
$nets = @(Get-NetIPConfiguration | Where-Object { $_.IPv4DefaultGateway -and $_.IPv4Address -and $_.NetAdapter.Status -eq 'Up' })
if ($nets.Count -eq 0) { Bad 'Khong co mang nao dang ket noi co cong mac dinh.' }
foreach ($n in $nets) {
    $cat = (Get-NetConnectionProfile -InterfaceIndex $n.InterfaceIndex -ErrorAction SilentlyContinue).NetworkCategory
    $url = "http://$($n.IPv4Address.IPAddress):$port/"
    $covered = $rule -and (($rule.Profile -eq 'Any') -or ("$($rule.Profile)" -split ',\s*' -contains "$cat"))
    if ($covered) { Pass "$($n.InterfaceAlias) ($cat): luat tuong lua ap dung. Thu tu may khac/dien thoai: $url" }
    else { Bad "$($n.InterfaceAlias) la mang $cat nhung luat khong ap dung cho loai nay, may khac se khong vao duoc $url. Doi mang sang Private hoac chay lai install.ps1 -AllowPublicNetwork." }
}
Info 'Luu y: chinh may chu goi IP cua minh luon duoc, khong chung minh gi. Phai thu tu MAY KHAC hoac DIEN THOAI cung mang.'

Title 'Du lieu va bao mat'
if (Test-Path $DataRoot) {
    try {
        $acl = Get-Acl $DataRoot
        $loose = $acl.Access | Where-Object { $_.IdentityReference.Value -match 'Users|Everyone|Authenticated Users' -and $_.AccessControlType -eq 'Allow' }
        if ($loose) { Bad "Thu muc du lieu cho phep nhom thuong truy cap: $(($loose.IdentityReference.Value) -join ', ')" } else { Pass 'Thu muc du lieu chi cho SYSTEM va Administrators.' }
    }
    catch { Info 'Khong doc duoc quyen thu muc du lieu (dung thiet ke neu khong phai Administrator).' }
    try {
        if (Test-Path (Join-Path $DataRoot 'data\trainingstats.db') -ErrorAction Stop) { Pass 'Co file co so du lieu trainingstats.db.' } else { Bad 'Chua co file co so du lieu.' }
        if (Test-Path (Join-Path $DataRoot 'config\first-run.properties') -ErrorAction Stop) { Bad 'first-run.properties van con (chua mat khau ban dau). Ung dung phai xoa file nay o lan chay dau: kiem tra log, hoac xoa thu cong.' }
        else { Pass 'Khong con first-run.properties (mat khau ban dau da duoc xoa khoi dia).' }
    }
    catch { Info 'BO QUA file co so du lieu va first-run.properties: can quyen Administrator de doc.' }
}
else { Bad "Khong co thu muc du lieu $DataRoot" }

Write-Host ''
if ($script:fail -eq 0) { Write-Host 'KET QUA: tat ca muc kiem tra deu dat.' -ForegroundColor Green }
else { Write-Host "KET QUA: $script:fail muc LOI. Xem cac dong [LOI] o tren." -ForegroundColor Red }
exit $script:fail
