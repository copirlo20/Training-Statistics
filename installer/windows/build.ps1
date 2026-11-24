#requires -Version 5.1
<#
.SYNOPSIS
    Build ung dung va dong goi thanh thu muc cai dat cho Windows (JRE gon di kem, dich vu WinSW).

.DESCRIPTION
    Ket qua: dist\TrainingStats\ va dist\TrainingStats-<phien ban>.zip
    Can JDK 25 (co jlink) trong JAVA_HOME hoac tham so -JavaHome.
#>
param(
    [switch]$SkipTests,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

# WinSW 2.12.0 (ban tu chua .NET, khong can cai them gi tren may dich).
# GitHub khong cong bo checksum cho ban nay; SHA256 duoi day tinh tu file tai ve va co kich thuoc
# khop voi danh sach release (18243033 byte). Doi phien ban thi phai tinh lai va cap nhat o day.
$WinSwVersion = '2.12.0'
$WinSwUrl     = "https://github.com/winsw/winsw/releases/download/v$WinSwVersion/WinSW-x64.exe"
$WinSwSha256  = '05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da'

# Cac module JDK ung dung can. Neu chay bao NoClassDefFoundError thi them module vao day.
$JdkModules = @(
    'java.base', 'java.logging', 'java.sql', 'java.naming', 'java.management', 'java.instrument',
    'java.desktop', 'java.xml', 'java.xml.crypto', 'java.security.jgss', 'java.security.sasl',
    'java.net.http', 'java.prefs', 'java.transaction.xa', 'java.compiler',
    'jdk.unsupported', 'jdk.zipfs', 'jdk.localedata', 'jdk.charsets', 'jdk.management', 'jdk.crypto.ec'
) -join ','

function Step($text) { Write-Host "`n==> $text" -ForegroundColor Cyan }
function Fail($text) { Write-Host "LOI: $text" -ForegroundColor Red; exit 1 }

$root    = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$backend = Join-Path $root 'backend'
$dist    = Join-Path $root 'dist'
$stage   = Join-Path $dist 'TrainingStats'

Step 'Kiem tra JDK'
if (-not $JavaHome -or -not (Test-Path (Join-Path $JavaHome 'bin\jlink.exe'))) {
    Fail 'Khong tim thay jlink. Dat JAVA_HOME tro toi JDK 25 hoac truyen -JavaHome.'
}
$javaExe = Join-Path $JavaHome 'bin\java.exe'
& $javaExe -version
$env:JAVA_HOME = $JavaHome

Step 'Build ung dung (Maven)'
Push-Location $backend
try {
    # clean: file da xoa o ma nguon (vi du migration cu) khong con nam trong JAR
    $mvnArgs = @('-B', '-q', 'clean', 'package')
    if ($SkipTests) { $mvnArgs += '-DskipTests' }
    & (Join-Path $backend 'mvnw.cmd') @mvnArgs
    if ($LASTEXITCODE -ne 0) { Fail 'Maven build that bai.' }
}
finally { Pop-Location }
$jar = Join-Path $backend 'target\trainingstats.jar'
if (-not (Test-Path $jar)) { Fail "Khong thay $jar" }

Step 'Don thu muc dong goi'
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $stage 'app'), (Join-Path $stage 'service') | Out-Null

Step 'Tao JRE gon bang jlink'
& (Join-Path $JavaHome 'bin\jlink.exe') --add-modules $JdkModules --strip-debug --no-header-files --no-man-pages `
    --compress zip-6 --output (Join-Path $stage 'runtime')
if ($LASTEXITCODE -ne 0) { Fail 'jlink that bai.' }

Step 'Sao chep ung dung'
Copy-Item $jar (Join-Path $stage 'app\trainingstats.jar')

Step "Chuan bi WinSW $WinSwVersion"
$cacheDir = Join-Path $PSScriptRoot ".cache\winsw-$WinSwVersion"
$winSwCached = Join-Path $cacheDir 'WinSW-x64.exe'
if (-not (Test-Path $winSwCached)) {
    New-Item -ItemType Directory -Path $cacheDir -Force | Out-Null
    Invoke-WebRequest -Uri $WinSwUrl -OutFile $winSwCached -UseBasicParsing
}
$actual = (Get-FileHash $winSwCached -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $WinSwSha256) {
    Remove-Item $winSwCached -Force
    Fail "SHA256 cua WinSW khong khop. Mong doi $WinSwSha256, nhan duoc $actual. Da xoa file."
}
Copy-Item $winSwCached (Join-Path $stage 'service\TrainingStatsService.exe')
Copy-Item (Join-Path $PSScriptRoot 'service\TrainingStatsService.xml') (Join-Path $stage 'service\TrainingStatsService.xml')

Step 'Sao chep script cai dat'
foreach ($f in 'install.ps1', 'uninstall.ps1', 'check.ps1', 'reset-superadmin.ps1', 'install.cmd', 'uninstall.cmd') {
    Copy-Item (Join-Path $PSScriptRoot "setup\$f") (Join-Path $stage $f)
}

$version = ([xml](Get-Content (Join-Path $backend 'pom.xml') -Raw)).project.version
Set-Content -Path (Join-Path $stage 'VERSION.txt') -Value $version -Encoding ASCII

Step 'Nen thanh file zip'
$zip = Join-Path $dist "TrainingStats-$version.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
# Khong dung Compress-Archive: no mo file khong chia se nen loi khi phan mem diet virus dang quet file moi do jlink ghi,
# va chi bao loi chu khong dung. Day mo file voi FileShare.ReadWrite, thu lai khi bi khoa, that bai thi dung build.
Add-Type -AssemblyName System.IO.Compression
function Add-ZipEntry($archive, [string]$path, [string]$entryName) {
    for ($try = 1; $try -le 10; $try++) {
        try {
            $in = [IO.File]::Open($path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
            try {
                $entry = $archive.CreateEntry($entryName, [IO.Compression.CompressionLevel]::Optimal)
                $out = $entry.Open()
                try { $in.CopyTo($out) } finally { $out.Dispose() }
            }
            finally { $in.Dispose() }
            return
        }
        catch [IO.IOException] {
            if ($try -eq 10) { Fail "Khong doc duoc $path (dang bi tien trinh khac giu): $($_.Exception.Message)" }
            Start-Sleep -Milliseconds 500
        }
    }
}
$fs = [IO.File]::Create($zip)
try {
    $archive = New-Object IO.Compression.ZipArchive($fs, [IO.Compression.ZipArchiveMode]::Create)
    try {
        $prefix = $stage.TrimEnd('\') + '\'
        foreach ($file in Get-ChildItem $stage -Recurse -File) {
            Add-ZipEntry $archive $file.FullName $file.FullName.Substring($prefix.Length).Replace('\', '/')
        }
    }
    finally { $archive.Dispose() }
}
finally { $fs.Dispose() }

$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host "`nXong. Thu muc: $stage" -ForegroundColor Green
Write-Host "File zip : $zip ($size MB)" -ForegroundColor Green
