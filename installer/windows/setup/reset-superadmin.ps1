#requires -Version 5.1
<#
.SYNOPSIS
    Dat lai mat khau (hoac tao moi) tai khoan Super Admin. Chay tren may chu, bang quyen Administrator.

.DESCRIPTION
    Dung khi quen mat khau Super Admin, khi Super Admin bi khoa, hoac khi nang cap tu ban chua co Super Admin.
    Neu tai khoan chua ton tai thi tao moi. Khong dung duoc de nang quyen mot tai khoan khac.
    Dich vu co the van dang chay.

.PARAMETER Username
    Ten tai khoan Super Admin. Mac dinh: admin.
#>
param(
    [string]$Username = 'admin',
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'TrainingStats'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'TrainingStats')
)

$ErrorActionPreference = 'Stop'
function Fail($text) { Write-Host "LOI: $text" -ForegroundColor Red; exit 1 }

$p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Fail 'Can chay bang quyen Administrator.'
}
$java = Join-Path $InstallDir 'runtime\bin\java.exe'
$jar = Join-Path $InstallDir 'app\trainingstats.jar'
if (-not (Test-Path $java) -or -not (Test-Path $jar)) { Fail "Khong thay chuong trinh o $InstallDir" }

$p1 = Read-Host "Mat khau moi cho '$Username'" -AsSecureString
$p2 = Read-Host 'Nhap lai mat khau' -AsSecureString
$b1 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p1)
$b2 = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($p2)
try {
    $s1 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b1)
    $s2 = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($b2)
    if ($s1 -ne $s2) { Fail 'Hai lan nhap khong khop.' }
    # Truyen mat khau qua bien moi truong cua rieng tien trinh nay, khong qua tham so dong lenh
    # (tham so hien trong danh sach tien trinh).
    $env:TS_RESET_PASSWORD = $s1
    $configLocation = 'optional:file:' + (Join-Path $DataRoot 'config').Replace('\', '/') + '/'
    # Tham so -Dx.y=z phai dat trong nhay: PowerShell 5.1 tach no tai dau cham khi goi chuong trinh ngoai.
    & $java '--enable-native-access=ALL-UNNAMED' '-Dfile.encoding=UTF-8' '-Duser.timezone=Asia/Ho_Chi_Minh' -jar $jar --reset-superadmin "--username=$Username" "--spring.config.additional-location=$configLocation"
    $code = $LASTEXITCODE
}
finally {
    Remove-Item Env:TS_RESET_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b1)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($b2)
}
if ($code -ne 0) { Fail 'Khong dat lai duoc. Xem thong bao o tren.' }
Write-Host 'Xong. Dang nhap bang mat khau moi.' -ForegroundColor Green
