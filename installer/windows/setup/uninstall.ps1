#requires -Version 5.1
<#
.SYNOPSIS
    Go cai dat Training Statistics. Mac dinh GIU LAI du lieu.

.PARAMETER RemoveData
    Xoa luon du lieu, cau hinh, log, ban sao luu o $DataRoot. Khong the khoi phuc. Se hoi xac nhan.
#>
param(
    [string]$InstallDir = (Join-Path $env:ProgramFiles 'TrainingStats'),
    [string]$DataRoot = (Join-Path $env:ProgramData 'TrainingStats'),
    [switch]$RemoveData,
    [switch]$SkipService
)

$ErrorActionPreference = 'Stop'
$ServiceName = 'TrainingStats'
$FirewallRule = 'TrainingStats HTTP'

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "LOI: $text" -ForegroundColor Red; exit 1 }

$p = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
if (-not $SkipService -and -not $p.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Fail 'Can chay bang quyen Administrator.'
}

# Neu dang chay tu chinh thu muc cai dat thi chep sang thu muc tam roi chay lai, vi se xoa thu muc nay.
$here = (Resolve-Path $PSScriptRoot).Path.TrimEnd('\')
if ($here -ieq $InstallDir.TrimEnd('\')) {
    $tmp = Join-Path ([IO.Path]::GetTempPath()) ('ts-uninstall-' + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $tmp | Out-Null
    Copy-Item $PSCommandPath (Join-Path $tmp 'uninstall.ps1')
    $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $tmp 'uninstall.ps1'),
        '-InstallDir', $InstallDir, '-DataRoot', $DataRoot)
    if ($RemoveData) { $argList += '-RemoveData' }
    if ($SkipService) { $argList += '-SkipService' }
    & powershell.exe @argList
    exit $LASTEXITCODE
}

if (-not $SkipService) {
    $svc = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if ($svc) {
        Step 'Dung va go dich vu'
        if ($svc.Status -ne 'Stopped') {
            Stop-Service -Name $ServiceName -Force
            $svc.WaitForStatus('Stopped', [TimeSpan]::FromSeconds(60))
        }
        $winsw = Join-Path $InstallDir 'service\TrainingStatsService.exe'
        if (Test-Path $winsw) { & $winsw uninstall }
    }
    Step 'Go luat tuong lua'
    Get-NetFirewallRule -DisplayName $FirewallRule -ErrorAction SilentlyContinue | Remove-NetFirewallRule
}

if (Test-Path $InstallDir) {
    Step "Xoa chuong trinh o $InstallDir"
    Remove-Item $InstallDir -Recurse -Force
}

if ($RemoveData) {
    if (Test-Path $DataRoot) {
        Write-Host "`nSap xoa VINH VIEN toan bo du lieu o $DataRoot (co ca co so du lieu va ban sao luu)." -ForegroundColor Red
        $confirm = Read-Host 'Go chinh xac chu XOA de xac nhan'
        if ($confirm -ceq 'XOA') {
            Remove-Item $DataRoot -Recurse -Force
            Write-Host 'Da xoa du lieu.'
        }
        else { Write-Host 'Khong xoa du lieu.' -ForegroundColor Yellow }
    }
}
else {
    Write-Host "`nDu lieu duoc GIU LAI o $DataRoot. Cai dat lai se dung tiep du lieu nay." -ForegroundColor Green
}
Write-Host 'Go cai dat xong.' -ForegroundColor Green
