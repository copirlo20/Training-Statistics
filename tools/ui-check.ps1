#requires -Version 5.1
<#
.SYNOPSIS
    Kiem tra giao dien tu dong o nhieu do rong man hinh bang Edge/Chrome headless (giao thuc DevTools).

.DESCRIPTION
    Voi moi trang va moi do rong, script do:
      - trang co cuon ngang khong (scrollWidth > innerWidth)
      - nut, lien ket, o nhap nao nho hon 44x44 px
      - o nhap nao co chu duoi 16 px (Safari iOS se tu phong to)
      - loi JavaScript, loi console, vi pham CSP
      - request nao di ra ngoai may chu (CDN, font, anh ben ngoai)
    va chup anh vao thu muc -OutDir. Thoat voi ma 1 neu co loi.

    Can mot may chu dang chay va mot tai khoan Super Admin (de vao duoc moi trang).
    Xem docs/OPERATIONS-GUIDE.md muc 11.3.

.EXAMPLE
    tools\ui-check.ps1 -BaseUrl http://localhost:8080 -Username admin -Password 'MatKhau@123'
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = 'admin',
    [Parameter(Mandatory = $true)][string]$Password,
    [int[]]$Widths = @(360, 390, 768, 1024, 1366, 1920),
    [int]$Height = 900,
    [string]$OutDir = 'ui-check-output',
    [string]$Browser,
    [int]$DebugPort = 9333,
    # Host bi bo qua khi dem request ra ngoai. Mac dinh: phan mem diet virus Kaspersky tu chen script vao moi trang
    # (khong phai do ung dung goi). Them host khac neu may co phan mem tuong tu.
    [string[]]$IgnoreHosts = @('*.kaspersky-labs.com')
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

# ---------------------------------------------------------------- trinh duyet
if (-not $Browser) {
    $candidates = @(
        "${env:ProgramFiles(x86)}\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe",
        "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
        "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe")
    $Browser = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Browser) { throw 'Khong tim thay Edge hoac Chrome. Truyen -Browser.' }

New-Item -ItemType Directory -Path $OutDir -Force | Out-Null
$OutDir = (Resolve-Path $OutDir).Path
$profileDir = Join-Path ([IO.Path]::GetTempPath()) ('ui-check-' + [guid]::NewGuid().ToString('N'))
$proc = Start-Process -FilePath $Browser -PassThru -ArgumentList @(
    '--headless=new', "--remote-debugging-port=$DebugPort", '--remote-allow-origins=*',
    "--user-data-dir=$profileDir", '--no-first-run', '--no-default-browser-check', '--disable-extensions',
    '--disable-gpu', "--window-size=1366,$Height", 'about:blank')

$wsUrl = $null
for ($i = 0; $i -lt 50 -and -not $wsUrl; $i++) {
    Start-Sleep -Milliseconds 200
    try {
        $targets = Invoke-RestMethod "http://127.0.0.1:$DebugPort/json/list" -TimeoutSec 2
        $wsUrl = ($targets | Where-Object { $_.type -eq 'page' } | Select-Object -First 1).webSocketDebuggerUrl
    } catch { }
}
if (-not $wsUrl) { Stop-Process -Id $proc.Id -Force; throw 'Khong ket noi duoc DevTools cua trinh duyet.' }

# ---------------------------------------------------------------- CDP
$ws = New-Object System.Net.WebSockets.ClientWebSocket
$ws.ConnectAsync([Uri]$wsUrl, [Threading.CancellationToken]::None).Wait()
$script:msgId = 0
$script:ignored = 0
$script:events = New-Object System.Collections.ArrayList

function Receive-Message {
    $buffer = New-Object byte[] 262144
    $ms = New-Object IO.MemoryStream
    do {
        $segment = New-Object 'System.ArraySegment[byte]' -ArgumentList @(, $buffer)
        $r = $ws.ReceiveAsync($segment, [Threading.CancellationToken]::None).Result
        $ms.Write($buffer, 0, $r.Count)
    } while (-not $r.EndOfMessage)
    return [Text.Encoding]::UTF8.GetString($ms.ToArray())
}

function Invoke-Cdp([string]$Method, $Params = @{}) {
    $script:msgId++
    $id = $script:msgId
    $json = @{ id = $id; method = $Method; params = $Params } | ConvertTo-Json -Depth 20 -Compress
    $bytes = [Text.Encoding]::UTF8.GetBytes($json)
    $segment = New-Object 'System.ArraySegment[byte]' -ArgumentList @(, $bytes)
    $ws.SendAsync($segment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, [Threading.CancellationToken]::None).Wait()
    while ($true) {
        $msg = Receive-Message | ConvertFrom-Json
        if ($msg.id -eq $id) {
            if ($msg.error) { throw "CDP $Method loi: $($msg.error.message)" }
            return $msg.result
        }
        if ($msg.method) { [void]$script:events.Add($msg) }
    }
}

function Invoke-Js([string]$Expression) {
    $r = Invoke-Cdp 'Runtime.evaluate' @{ expression = $Expression; returnByValue = $true; awaitPromise = $true }
    if ($r.exceptionDetails) { throw "JS loi: $($r.exceptionDetails.text) $($r.exceptionDetails.exception.description)" }
    return $r.result.value
}

function Wait-Ready([int]$TimeoutMs = 10000) {
    $expr = "document.readyState === 'complete' && !document.querySelector('[aria-busy=""true""]') && !document.querySelector('.loading')"
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.ElapsedMilliseconds -lt $TimeoutMs) {
        if (Invoke-Js $expr) { Start-Sleep -Milliseconds 250; return $true }
        Start-Sleep -Milliseconds 150
    }
    return $false
}

function Open-Url([string]$Url) {
    [void](Invoke-Cdp 'Page.navigate' @{ url = $Url })
    Start-Sleep -Milliseconds 300
    return (Wait-Ready)
}

# Luu ban in PDF cua trang (khung A4 theo @page cua print.css) de xem tieng Viet va bo cuc khi in.
function Save-Pdf([string]$Name, [string]$Url) {
    [void](Open-Url $Url)
    Start-Sleep -Milliseconds 800
    $pdf = Invoke-Cdp 'Page.printToPDF' @{ printBackground = $false; preferCSSPageSize = $true }
    [IO.File]::WriteAllBytes((Join-Path $OutDir "$Name.pdf"), [Convert]::FromBase64String($pdf.data))
}

function Set-Width([int]$Width) {
    [void](Invoke-Cdp 'Emulation.setDeviceMetricsOverride' @{
        width = $Width; height = $Height; deviceScaleFactor = 1; mobile = ($Width -lt 600) })
}

$measureJs = @'
(() => {
  const vw = window.innerWidth;
  const sw = document.documentElement.scrollWidth;
  const small = [], smallFont = [];
  for (const el of document.querySelectorAll('a[href], button, input, select, textarea, [role="button"]')) {
    if (el.closest('[hidden]') || el.classList.contains('skip-link')) continue;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') continue;
    let target = el;
    if (el.matches('input[type=checkbox], input[type=radio]')) target = el.closest('label') || el;
    const r = target.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) continue;
    if (r.width < 43.5 || r.height < 43.5) {
      const name = (el.getAttribute('aria-label') || el.textContent || el.name || '').trim().replace(/\s+/g, ' ').slice(0, 30);
      small.push(el.tagName.toLowerCase() + '[' + name + '] ' + Math.round(r.width) + 'x' + Math.round(r.height));
    }
    if (el.matches('input:not([type=checkbox]):not([type=radio]), select, textarea') && parseFloat(cs.fontSize) < 16) {
      smallFont.push((el.name || el.id) + ' ' + cs.fontSize);
    }
  }
  const wide = [...document.querySelectorAll('body *')].filter(e => {
    const r = e.getBoundingClientRect();
    return r.right > vw + 1 && getComputedStyle(e).position !== 'fixed' && !e.closest('.table-scroll');
  }).slice(0, 5).map(e => e.tagName.toLowerCase() + '.' + String(e.className).split(' ')[0]);
  // Các phần tử con trực tiếp của thanh trên không được đè lên nhau (lỗi bố cục mà đo cuộn ngang không thấy).
  const overlaps = [];
  const header = document.querySelector('.app-header');
  if (header) {
    const kids = [...header.children].filter(k => getComputedStyle(k).display !== 'none' && k.getBoundingClientRect().width > 0
      && !k.classList.contains('header-spacer'));
    for (let i = 0; i < kids.length; i++) for (let j = i + 1; j < kids.length; j++) {
      const a = kids[i].getBoundingClientRect(), b = kids[j].getBoundingClientRect();
      if (a.left < b.right - 1 && b.left < a.right - 1 && a.top < b.bottom - 1 && b.top < a.bottom - 1) {
        overlaps.push(kids[i].className.split(' ')[0] + ' / ' + kids[j].className.split(' ')[0]);
      }
    }
  }
  const modals = document.querySelectorAll('.modal-overlay').length;
  // Chữ lạc do lỗi lập trình: append() gốc của DOM in null thành "null", ghép chuỗi với undefined...
  const stray = [];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const t = walker.currentNode.nodeValue.trim();
    if (['null', 'undefined', 'NaN', '[object Object]'].includes(t) || /\bundefined\b|\[object Object\]/.test(t)) stray.push(t.slice(0, 40));
  }
  const loadError = [...document.querySelectorAll('.state-page h1')].some(e => e.textContent.startsWith('Không tải'));
  return JSON.stringify({ vw, sw, overflow: sw > vw, small, smallFont, wide, overlaps, modals, loadError, stray, title: document.title });
})()
'@

function Save-Screenshot([string]$Name) {
    $metrics = Invoke-Cdp 'Page.getLayoutMetrics'
    $w = [int][Math]::Ceiling($metrics.cssContentSize.width)
    $hgt = [int][Math]::Min(4000, [Math]::Ceiling($metrics.cssContentSize.height))
    $shot = Invoke-Cdp 'Page.captureScreenshot' @{
        format = 'png'; captureBeyondViewport = $true
        clip = @{ x = 0; y = 0; width = $w; height = $hgt; scale = 1 } }
    $file = Join-Path $OutDir "$Name.png"
    [IO.File]::WriteAllBytes($file, [Convert]::FromBase64String($shot.data))
}

$ignoreLogPattern = ($IgnoreHosts | ForEach-Object { [regex]::Escape($_).Replace('\*', '[^\s'']*') }) -join '|'
$script:ignoredLogs = 0

function Get-Problems {
    # Loi JS, console.error, CSP, request ra ngoai, sinh ra tu lan kiem tra truoc toi gio.
    $out = @()
    foreach ($e in $script:events) {
        switch ($e.method) {
            'Runtime.exceptionThrown' { $out += 'JS: ' + $e.params.exceptionDetails.exception.description }
            'Runtime.consoleAPICalled' {
                if ($e.params.type -eq 'error') {
                    $parts = $e.params.args | ForEach-Object { if ($null -ne $_.value) { $_.value } else { $_.description } }
                    $out += 'console.error: ' + ($parts -join ' ')
                }
            }
            'Log.entryAdded' {
                $text = [string]$e.params.entry.text
                if ($e.params.entry.level -eq 'error' -and $text -notmatch 'status of 401') {
                    # Kaspersky sua ca header CSP (them host cua no) roi chen style inline bi CSP chan: loi chua ten host do
                    # khong do ung dung gay ra. Ung dung khong dat style inline nao (kiem bang grep), nen chi bo qua loi co ten host.
                    if ($ignoreLogPattern -and $text -match $ignoreLogPattern) { $script:ignoredLogs++ }
                    else { $out += 'log: ' + $text }
                }
            }
            'Network.requestWillBeSent' {
                $u = $e.params.request.url
                if ($u -notlike "$BaseUrl*" -and $u -notlike 'data:*' -and $u -ne 'about:blank') {
                    $uriHost = try { ([Uri]$u).Host } catch { '' }
                    if ($IgnoreHosts | Where-Object { $uriHost -like $_ }) { $script:ignored++ }
                    else { $out += 'request ra ngoai: ' + $u }
                }
            }
        }
    }
    $script:events.Clear()
    return $out
}

# ---------------------------------------------------------------- chay
$results = New-Object System.Collections.ArrayList
function Check([string]$Page, [int]$Width, [scriptblock]$Prepare) {
    Set-Width $Width
    $ready = & $Prepare
    $m = (Invoke-Js $measureJs) | ConvertFrom-Json
    $issues = @()
    if (-not $ready) { $issues += 'trang chua san sang sau 10 giay' }
    if ($m.overflow) { $issues += "cuon ngang: scrollWidth $($m.sw) > $($m.vw) (" + ($m.wide -join ', ') + ')' }
    # Chu trong phan tu flex bi co (nowrap) co the tran ra ngoai ma scrollWidth khong tang. Kich thuoc noi dung
    # cua trinh duyet (cung so dung de chup anh) thi van tang: so them o day.
    $content = [Math]::Ceiling((Invoke-Cdp 'Page.getLayoutMetrics').cssContentSize.width)
    if ($content -gt $Width + 1 -and -not $m.overflow) { $issues += "noi dung rong $content px > $Width px (chu tran khoi khung?)" }
    if ($m.small.Count -gt 0) { $issues += 'vung cham < 44px: ' + ($m.small -join '; ') }
    if ($m.smallFont.Count -gt 0) { $issues += 'o nhap chu < 16px: ' + ($m.smallFont -join '; ') }
    if ($m.overlaps.Count -gt 0) { $issues += 'thanh tren bi de: ' + ($m.overlaps -join '; ') }
    if ($m.loadError) { $issues += 'trang bao "Khong tai duoc trang"' }
    if ($m.stray.Count -gt 0) { $issues += 'chu lac tren trang: ' + ($m.stray -join '; ') }
    # Trang thuong khong duoc con hop thoai cua trang truoc de len (vi du sau khi bam Back).
    if ($m.modals -gt 1) { $issues += "$($m.modals) hop thoai chong len nhau" }
    if ($m.modals -gt 0 -and $Page -notin @('audit-modal', 'more-menu', 'user-move-modal', 'request-reject-modal', 'class-candidates-modal', 'class-teachers-modal', 'grade-confirm-modal', 'result-edit-modal', 'class-new', 'class-edit')) { $issues += "con $($m.modals) hop thoai dang mo de len trang" }
    $issues += Get-Problems
    $name = '{0}-{1}' -f $Page, $Width
    Save-Screenshot $name
    [void]$results.Add([pscustomobject]@{ Trang = $Page; Rong = $Width; KetQua = $(if ($issues.Count) { 'LOI' } else { 'DAT' }); ChiTiet = ($issues -join ' | ') })
}

# Kich ban chuc nang: doan JS tra ve 'OK' hoac mo ta loi.
# File nay PHAI luu UTF-8 co BOM: Windows PowerShell 5.1 doc file khong BOM theo bang ma ANSI, lam vo chu tieng Viet.
function Invoke-Flow([string]$Name, [string]$Js, [int]$Width = 1366) {
    Set-Width $Width
    $msg = try { Invoke-Js $Js } catch { "loi: $($_.Exception.Message)" }
    $issues = @()
    if ($msg -ne 'OK') { $issues += [string]$msg }
    $issues += Get-Problems
    [void]$results.Add([pscustomobject]@{ Trang = "flow:$Name"; Rong = $Width; KetQua = $(if ($issues.Count) { 'LOI' } else { 'DAT' }); ChiTiet = ($issues -join ' | ') })
}

# Dung file .xlsx toi gian de thu nhap Excel (M5). Moi sheet la danh sach dong "a|b|c"; o bat dau bang # la so.
Add-Type -AssemblyName System.IO.Compression
function New-XlsxBase64([System.Collections.Specialized.OrderedDictionary]$Sheets) {
    $utf8 = New-Object System.Text.UTF8Encoding $false
    $ms = New-Object System.IO.MemoryStream
    $zip = New-Object System.IO.Compression.ZipArchive($ms, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    $put = {
        param($name, $text)
        $entry = $zip.CreateEntry($name)
        $w = New-Object System.IO.StreamWriter($entry.Open(), $utf8)
        $w.Write($text); $w.Close()
    }
    $ns = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
    $rel = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
    $overrides = ''; $sheetsXml = ''; $rels = ''; $i = 0
    foreach ($name in $Sheets.Keys) {
        $i++
        $overrides += "<Override PartName=`"/xl/worksheets/sheet$i.xml`" ContentType=`"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml`"/>"
        $sheetsXml += "<sheet name=`"$name`" sheetId=`"$i`" r:id=`"rId$i`"/>"
        $rels += "<Relationship Id=`"rId$i`" Type=`"$rel/worksheet`" Target=`"worksheets/sheet$i.xml`"/>"
        $rowsXml = ''; $r = 0
        foreach ($line in $Sheets[$name]) {
            $r++; $cells = ''; $c = 0
            foreach ($v in ($line -split '\|')) {
                $ref = [string][char](65 + $c) + $r; $c++
                if ($v -eq '') { continue }
                if ($v.StartsWith('#')) { $cells += "<c r=`"$ref`"><v>$($v.Substring(1))</v></c>" }
                else { $cells += "<c r=`"$ref`" t=`"inlineStr`"><is><t>$([System.Security.SecurityElement]::Escape($v))</t></is></c>" }
            }
            $rowsXml += "<row r=`"$r`">$cells</row>"
        }
        & $put "xl/worksheets/sheet$i.xml" "<?xml version=`"1.0`" encoding=`"UTF-8`"?><worksheet xmlns=`"$ns`"><sheetData>$rowsXml</sheetData></worksheet>"
    }
    & $put '[Content_Types].xml' "<?xml version=`"1.0`" encoding=`"UTF-8`"?><Types xmlns=`"http://schemas.openxmlformats.org/package/2006/content-types`"><Default Extension=`"rels`" ContentType=`"application/vnd.openxmlformats-package.relationships+xml`"/><Default Extension=`"xml`" ContentType=`"application/xml`"/><Override PartName=`"/xl/workbook.xml`" ContentType=`"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml`"/>$overrides</Types>"
    & $put '_rels/.rels' "<?xml version=`"1.0`" encoding=`"UTF-8`"?><Relationships xmlns=`"http://schemas.openxmlformats.org/package/2006/relationships`"><Relationship Id=`"rId1`" Type=`"$rel/officeDocument`" Target=`"xl/workbook.xml`"/></Relationships>"
    & $put 'xl/workbook.xml' "<?xml version=`"1.0`" encoding=`"UTF-8`"?><workbook xmlns=`"$ns`" xmlns:r=`"$rel`"><sheets>$sheetsXml</sheets></workbook>"
    & $put 'xl/_rels/workbook.xml.rels' "<?xml version=`"1.0`" encoding=`"UTF-8`"?><Relationships xmlns=`"http://schemas.openxmlformats.org/package/2006/relationships`">$rels</Relationships>"
    $zip.Dispose()
    return [Convert]::ToBase64String($ms.ToArray())
}

# Gan file (base64) vao o chon file cua trang nhap roi bam "Kiem tra file".
$setFileJs = 'const setFile = (b64) => { const bin = atob(b64); const bytes = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i); const f = new File([bytes], "thu-nhap.xlsx", { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" }); const dt = new DataTransfer(); dt.items.add(f); const input = document.querySelector(".importer input[type=file]"); input.files = dt.files; input.dispatchEvent(new Event("change")); input.closest("form").requestSubmit(); };'

$waitJs = 'const wait = async (fn, ms = 6000) => { const t = Date.now(); while (Date.now() - t < ms) { const v = fn(); if (v) return v; await new Promise(r => setTimeout(r, 100)); } return null; };'
# O chon mot gia tri dang radio co tim nhanh (ui/radio-select.js): mo khung neu la nut, bam muc co gia tri can chon.
$waitJs += ' const pickRadio = async (id, value) => { const el = document.getElementById(id); const root = el && el.closest(''.radio-select''); if (!root) return false; if (el.classList.contains(''multi-trigger'') && el.getAttribute(''aria-expanded'') !== ''true'') el.click(); const r = await wait(() => [...root.querySelectorAll(''input[type=radio]'')].find(x => x.value === value)); if (!r) return false; r.click(); return true; };'
# O chon nhieu (ui/multi-select.js): mo khung, danh dau dung tap gia tri can chon, dong khung (onChange chay sau 250 ms).
$waitJs += ' const pickChecks = async (id, values) => { const el = document.getElementById(id); if (!el) return false; const root = el.parentElement; if (el.getAttribute(''aria-expanded'') !== ''true'') el.click(); const ok = await wait(() => root.querySelector(''.menu-panel:not([hidden]) input[type=checkbox]'')); if (!ok) return false; const want = new Set(values.map(String)); for (const b of [...root.querySelectorAll(''input[type=checkbox]'')]) if (b.checked !== want.has(b.value)) b.click(); el.click(); await new Promise(r => setTimeout(r, 400)); return true; };'

try {
    [void](Invoke-Cdp 'Page.enable')
    [void](Invoke-Cdp 'Runtime.enable')
    [void](Invoke-Cdp 'Log.enable')
    [void](Invoke-Cdp 'Network.enable')

    # 1. Trang dang nhap (chua dang nhap)
    foreach ($w in $Widths) { Check 'login' $w { Open-Url "$BaseUrl/login.html" } }

    # Kich ban truoc khi dang nhap
    [void](Open-Url "$BaseUrl/login.html")
    Invoke-Flow 'sai-mat-khau-bao-loi' @"
(async () => { $waitJs
  const f = document.querySelector('form'); f.username.value = 'khong-ton-tai'; f.password.value = 'sai'; f.requestSubmit();
  const a = await wait(() => document.querySelector('.alert-error'));
  if (!a) return 'khong hien thong bao loi';
  if (!a.textContent.includes('không đúng')) return 'thong bao sai: ' + a.textContent;
  return location.pathname.endsWith('login.html') ? 'OK' : 'roi khoi trang dang nhap';
})()
"@
    Invoke-Flow 'tao-25-dong-nhat-ky' @"
(async () => {
  await fetch('/api/v1/auth/csrf');
  const t = decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1]);
  for (let i = 0; i < 25; i++) {
    await fetch('/api/v1/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': t },
      body: JSON.stringify({ username: 'thu-phan-trang-' + i, password: 'x' }) });
  }
  return 'OK';
})()
"@

    # 2. Dang nhap qua chinh form
    Set-Width 1366
    [void](Open-Url "$BaseUrl/login.html")
    $u = $Username.Replace("'", "\'"); $p = $Password.Replace("'", "\'")
    [void](Invoke-Js "(() => { const f = document.querySelector('form'); f.username.value = '$u'; f.password.value = '$p'; f.requestSubmit(); return true; })()")
    $ok = $false
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 200
        if (Invoke-Js "location.pathname.endsWith('index.html') && !!document.querySelector('.app-header .brand')") { $ok = $true; break }
    }
    if (-not $ok) { throw 'Dang nhap qua giao dien that bai. Kiem tra tai khoan, mat khau.' }
    [void](Get-Problems)

    # Nap thu moi module JS (tru app.js, login.js vi chung chay ngay khi nap): bat loi cu phap o ca trang chua mo.
    $jsRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\frontend\js')).Path
    $modules = Get-ChildItem $jsRoot -Recurse -Filter *.js | Where-Object { $_.Name -notin @('app.js', 'login.js') } |
        ForEach-Object { '/js/' + $_.FullName.Substring($jsRoot.Length + 1).Replace([string][char]92, '/') }
    $list = ($modules | ForEach-Object { "'$_'" }) -join ','
    Invoke-Flow 'nap-moi-module' @"
(async () => {
  const failed = [];
  for (const m of [$list]) { try { await import(m); } catch (e) { failed.push(m + ': ' + e.message); } }
  return failed.length === 0 ? 'OK' : failed.join(' | ');
})()
"@

    # Bo bieu tuong SVG hong thi moi bieu tuong bien mat ma khong co loi JS nao: phan tich thu file.
    Invoke-Flow 'bieu-tuong-svg-hop-le' @"
(async () => {
  const text = await (await fetch('/assets/icons.svg')).text();
  const doc = new DOMParser().parseFromString(text, 'image/svg+xml');
  const err = doc.querySelector('parsererror');
  if (err) return 'icons.svg loi cu phap: ' + err.textContent.slice(0, 120);
  const ids = new Set([...doc.querySelectorAll('symbol')].map(s => s.id));
  const used = [...document.querySelectorAll('use')].map(u => u.getAttribute('href').split('#')[1]);
  const missing = used.filter(i => !ids.has(i));
  return missing.length ? 'bieu tuong khong co trong icons.svg: ' + missing.join(', ') : 'OK';
})()
"@
    # Tao mot nguoi dung mau qua API de co trang chi tiet, sua, in.
    $uid = Invoke-Js @"
(async () => {
  const t = decodeURIComponent(document.cookie.match(/XSRF-TOKEN=([^;]+)/)[1]);
  const units = await (await fetch('/api/v1/units/tree')).json();
  const unit = units.find(u => u.level === 2 && u.active);
  const r = await fetch('/api/v1/users', { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': t },
    body: JSON.stringify({ category: 'HSQ-CS', lastName: 'Nguyễn Văn', firstName: 'Mẫu', personalCode: 'UI-' + Date.now(),
      unitId: unit.id, username: 'uimau' + Date.now(), password: 'MatKhau@123' }) });
  const j = await r.json();
  return String(j.id);
})()
"@
    if (-not ($uid -match '^\d+$')) { throw "Khong tao duoc nguoi dung mau: $uid" }
    [void](Get-Problems)

    # Du lieu mau M3: dot, noi dung kiem tra, mot Admin cung don vi voi nguoi mau. Admin mau gui de nghi them nguoi mau vao lop
    # (dang nhap Admin bang fetch, gui de nghi, roi dang nhap lai Super Admin), nen Super Admin nhan thong bao.
    $sampleJson = Invoke-Js @"
(async () => {
  const token = async () => (await (await fetch('/api/v1/auth/csrf')).json()).token;
  const send = async (url, body) => {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': await token() },
      body: JSON.stringify(body) });
    const j = r.status === 204 ? null : await r.json();
    if (!r.ok) throw new Error(url + ': ' + (j?.message || r.status));
    return j;
  };
  const stamp = Date.now();
  const user = await (await fetch('/api/v1/users/$uid')).json();
  const period = await send('/api/v1/periods', { name: 'Đợt kiểm tra UI ' + stamp, startDate: '2026-11-01', endDate: '2026-11-30' });
  const cls = await send('/api/v1/classes', { periodId: period.id, name: 'Nội dung UI ' + stamp,
    examDate: '2026-11-20', location: 'Thao trường' });
  // M4: ba nguoi hoc (khong tai khoan) cung don vi voi nguoi mau; Pham co diem san de thay o nhap bi khoa va sua diem.
  const learners = {};
  for (const [last, first] of [['Phạm Văn', 'Chấm'], ['Hoàng Thị', 'Điểm'], ['Đỗ Văn', 'Biên'], ['Trịnh Văn', 'Lưu']]) {
    const person = await send('/api/v1/users', { category: 'SQ', lastName: last, firstName: first + ' ' + stamp, unitId: user.unit.id });
    const part = await send('/api/v1/classes/' + cls.id + '/participants', { userId: person.id });
    learners[first] = part.id;
  }
  await send('/api/v1/classes/' + cls.id + '/participants/' + learners['Chấm'] + '/result', { score: 7.5 });
  const acls = await send('/api/v1/classes', { periodId: period.id, name: 'Phân công UI ' + stamp, examDate: '2026-11-22' });
  const pa = await send('/api/v1/users', { category: 'SQ', lastName: 'Ngô Văn', firstName: 'PhânA ' + stamp, unitId: user.unit.id });
  const pb = await send('/api/v1/users', { category: 'QNCN', lastName: 'Ngô Văn', firstName: 'PhânB ' + stamp, unitId: user.unit.id });
  const da = await send('/api/v1/users', { category: 'SQ', lastName: 'Vũ Văn', firstName: 'DuyệtA ' + stamp, unitId: user.unit.id });
  const db = await send('/api/v1/users', { category: 'SQ', lastName: 'Vũ Văn', firstName: 'DuyệtB ' + stamp, unitId: user.unit.id });
  const gk = 'uigk' + stamp;
  await send('/api/v1/users', { category: 'SQ', lastName: 'Phan Văn', firstName: 'Khảo', unitId: user.unit.id, username: gk, password: 'MatKhau@123' });
  const admin = 'uiadmin' + stamp;
  await send('/api/v1/users', { category: 'SQ', lastName: 'Lê Văn', firstName: 'Quản', personalCode: 'UIA-' + stamp, unitId: user.unit.id,
    username: admin, password: 'MatKhau@123', role: 'ADMIN' });
  await send('/api/v1/auth/login', { username: admin, password: 'MatKhau@123' });
  const req = await send('/api/v1/classes/' + cls.id + '/requests', { type: 'ADD', userId: $uid });
  for (const p of [da, db]) await send('/api/v1/classes/' + acls.id + '/requests', { type: 'ADD', userId: p.id });
  await send('/api/v1/auth/login', { username: '$u', password: '$p' });
  return JSON.stringify({ classId: cls.id, className: cls.name, rid: req.id, admin, stamp, periodId: period.id, assignClassId: acls.id, personA: pa.id, personB: pb.id, approveA: da.id, approveB: db.id, teacher: gk });
})()
"@
    $sample = $sampleJson | ConvertFrom-Json
    if (-not $sample.rid) { throw "Khong tao duoc lop va de nghi mau: $sampleJson" }
    $rid = $sample.rid
    $cid = $sample.classId
    [void](Get-Problems)

    # M5: file Excel thu nhap. Ma dinh danh cua nguoi mau va cua nguoi hoc chua co diem trong lop mau.
    $codes = (Invoke-Js @"
(async () => {
  const u = await (await fetch('/api/v1/users/$uid')).json();
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const bien = list.find(p => p.user.fullName.startsWith('Đỗ Văn Biên'));
  return JSON.stringify({ user: u.personalCode, unit: u.unit.code, bien: bien.user.personalCode });
})()
"@) | ConvertFrom-Json
    $xst = $sample.stamp
    $hdrUser = 'Mã định danh|Họ đệm *|Tên *|Đơn vị *|Đối tượng *|Cấp bậc|Chức vụ|Chuyên ngành|Ngày sinh|Số điện thoại|Ghi chú'
    $badUsers = [ordered]@{ 'NguoiDung' = @($hdrUser, "|Vũ Văn|Đúng $xst|$($codes.unit)|SQ", "|Vũ Văn|Sai $xst|$($codes.unit)|SQ|Cấp bậc không có", "|Vũ Văn|Ngày sai $xst|$($codes.unit)|SQ||||31/02/2000") }
    $goodUsers = [ordered]@{ 'NguoiDung' = @($hdrUser, "|Vũ Văn|Nhập $xst A|$($codes.unit)|QNCN||||05/09/1995", "|Vũ Văn|Nhập $xst B|$($codes.unit)|HSQ-CS") }
    $badUsersB64 = New-XlsxBase64 $badUsers
    $goodUsersB64 = New-XlsxBase64 $goodUsers
    $classesB64 = New-XlsxBase64 ([ordered]@{
        'NoiDung' = @('Tên nội dung *|Đợt kiểm tra *|Ngày kiểm tra|Địa điểm|Ghi chú', "Nội dung nhập Excel $xst|Đợt kiểm tra UI $xst|25/11/2026|Thao trường 2")
        'NguoiHoc' = @('Tên nội dung *|Mã định danh *', "Nội dung nhập Excel $xst|$($codes.user) - Nguyễn Văn Mẫu") })
    $resultsB64 = New-XlsxBase64 ([ordered]@{ 'Diem' = @('Mã định danh *|Họ tên|Đơn vị|Điểm', "$($codes.bien)|Đỗ Văn Biên||#9") })

    # 3. Cac trang sau dang nhap
    $pages = [ordered]@{
        'home' = '#/'; 'audit' = '#/admin/audit'; 'password' = '#/me/password'; 'notfound' = '#/khong-co-trang-nay'
        'users' = '#/users'; 'user-new' = '#/users/new'; 'user-detail' = "#/users/$uid"; 'user-edit' = "#/users/$uid/edit"
        'user-print' = "#/users/$uid/print"; 'class-print' = "#/classes/$cid/print"; 'units' = '#/admin/units'; 'catalogs' = '#/admin/catalogs'; 'system' = '#/admin/system'
        'notifications' = '#/notifications'; 'request-detail' = "#/requests/$rid"
        'periods' = '#/periods'; 'classes' = '#/classes'; 'class-new' = '#/classes?new=1'; 'class-detail' = "#/classes/$cid"
        'class-edit' = "#/classes/$cid`?edit=1"; 'requests' = '#/requests'
        'class-grade' = "#/classes/$cid/grade"; 'me' = '#/me'
        'users-import' = '#/users/import'; 'classes-import' = '#/classes/import'; 'class-import-results' = "#/classes/$cid/import-results"
        'assign' = "#/assign?periodId=$($sample.periodId)"; 'assign-import' = "#/assign/import?periodId=$($sample.periodId)"
        'period-import-results' = "#/periods/import-results?periodId=$($sample.periodId)"
        'stats' = '#/stats'; 'stats-missing' = '#/stats?tab=missing'
        'stats-unit-kind' = '#/stats?kind=UNIT'; 'class-stats' = "#/stats/class/$cid"
    }
    foreach ($entry in $pages.GetEnumerator()) {
        foreach ($w in $Widths) { Check $entry.Key $w ([scriptblock]::Create("Open-Url '$BaseUrl/index.html$($entry.Value)'")) }
    }

    # 3b. Ban in (M6.3): trang in ket qua mot noi dung cung bo cuc file Excel; che do in den trang, khong thanh dieu huong.
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid/print")
    Invoke-Flow 'in-ket-qua-dong-bo-excel' @"
(async () => { $waitJs
  const sheet = await wait(() => document.querySelector('.print-sheet.print-landscape'));
  if (!sheet) return 'khong co trang in kho ngang';
  if (sheet.querySelector('.ps-app')?.textContent !== 'HỆ THỐNG QUẢN LÝ HUẤN LUYỆN') return 'thieu dai ten he thong nhu file Excel';
  if (!sheet.querySelector('.ps-title')?.textContent.startsWith('KẾT QUẢ KIỂM TRA')) return 'tieu de phai viet hoa nhu file Excel: ' + sheet.querySelector('.ps-title')?.textContent;
  const heads = [...sheet.querySelectorAll('.ps-table thead th')].map(t => t.textContent);
  const want = ['STT', 'Mã định danh', 'Họ tên', 'Cấp bậc', 'Đối tượng', 'Đơn vị', 'Điểm', 'Xếp loại', 'Tình trạng', 'Ghi chú'];
  if (heads.join('|') !== want.join('|')) return 'cot khac file xuat Excel: ' + heads.join('|');
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const rows = sheet.querySelectorAll('.ps-table tbody tr');
  if (rows.length !== Math.max(1, list.length)) return 'so dong khong khop API: ' + rows.length + ' / ' + list.length;
  const ranked = list.find(p => p.user.rankSymbol);
  if (ranked && ![...rows].some(r => r.children[3].textContent === ranked.user.rankSymbol)) return 'cap bac phai in bang ky hieu';
  if (!document.querySelector('.print-toolbar button')) return 'thieu nut In';
  return 'OK';
})()
"@
    [void](Invoke-Cdp 'Emulation.setEmulatedMedia' @{ media = 'print' })
    Invoke-Flow 'ban-in-den-trang' @"
(async () => { $waitJs
  const shown = (sel) => { const e = document.querySelector(sel); return !!e && getComputedStyle(e).display !== 'none'; };
  for (const sel of ['#app-header', '.app-nav', '.print-toolbar', '.back-link']) if (shown(sel)) return 'ban in con ' + sel;
  const head = getComputedStyle(document.querySelector('.ps-head'));
  if (head.backgroundColor !== 'rgba(0, 0, 0, 0)') return 'dai dau ban in con nen mau: ' + head.backgroundColor;
  for (const sel of ['.ps-title', '.ps-app', '.ps-table td', '.ps-table thead th']) if (getComputedStyle(document.querySelector(sel)).color !== 'rgb(0, 0, 0)') return 'chu ban in khong den: ' + sel;
  if (getComputedStyle(document.getElementById('app-main') || document.querySelector('main')).outlineStyle !== 'none') return 'ban in con vien focus';
  const th = getComputedStyle(document.querySelector('.ps-table thead th'));
  if (th.backgroundColor !== 'rgba(0, 0, 0, 0)') return 'tieu de bang ban in con nen: ' + th.backgroundColor;
  return 'OK';
})()
"@
    [void](Invoke-Cdp 'Emulation.setEmulatedMedia' @{ media = '' })
    # In theo bo loc (2026-11-24): trang nguon giu bo loc tren dia chi, nut In mang bo loc sang trang in; trang in chi in
    # phan khop, ghi dong "Bo loc", co nut "In tat ca"; quay lai van con bo loc.
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid`?states=TESTED")
    Invoke-Flow 'in-ket-qua-theo-bo-loc' @"
(async () => { $waitJs
  const link = await wait(() => [...document.querySelectorAll('.page-actions a')].find(a => a.textContent.startsWith('In kết quả') && a.getAttribute('href').includes('states=TESTED')));
  if (!link) return 'nut In khong mang bo loc tinh trang';
  if (link.textContent !== 'In kết quả theo bộ lọc') return 'nhan nut In: ' + link.textContent;
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const tested = list.filter(p => p.state === 'TESTED').length;
  location.hash = link.getAttribute('href');
  const sheet = await wait(() => document.querySelector('.print-sheet'));
  if (!sheet) return 'khong mo trang in';
  const rows = sheet.querySelectorAll('.ps-table tbody tr:not(:has(.ps-empty))').length;
  if (rows !== tested) return 'in ' + rows + ' dong, khop bo loc ' + tested;
  if (!sheet.querySelector('.ps-info').textContent.includes('Bộ lọc: Tình trạng: Đã có điểm')) return 'ban in khong ghi bo loc';
  if (![...document.querySelectorAll('.print-toolbar a')].some(a => a.textContent.startsWith('In tất cả'))) return 'thieu nut In tat ca';
  if (!document.querySelector('.back-link').getAttribute('href').includes('states=TESTED')) return 'quay lai mat bo loc';
  return 'OK';
})()
"@
    Invoke-Flow 'in-thong-tin-ca-nhan-theo-bo-loc' @"
(async () => { $waitJs
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const p = list.find(x => x.result);
  if (!p) return 'du lieu mau khong co nguoi co diem';
  const other = ['FAIL', 'PASS', 'GOOD', 'EXCELLENT'].find(g => g !== p.result.grade);
  location.hash = '#/users/' + p.user.id + '?grades=' + p.result.grade;
  const link = await wait(() => [...document.querySelectorAll('.page-actions a')].find(a => a.getAttribute('href').includes('/print?grades=')));
  if (!link) return 'nut In o chi tiet nguoi dung khong mang bo loc';
  location.hash = link.getAttribute('href');
  const rows = await wait(() => { const r = document.querySelectorAll('.ps-table:not(.ps-facts) tbody tr'); return r.length ? r : null; });
  if (!rows || rows.length !== 1 || rows[0].querySelector('.ps-empty')) return 'loc dung xep loai phai in 1 noi dung';
  location.hash = '#/users/' + p.user.id + '/print?grades=' + other;
  if (!(await wait(() => document.querySelector('.ps-empty')?.textContent === 'Không có nội dung nào khớp bộ lọc.'))) return 'loc xep loai khac van in noi dung';
  if (!document.querySelector('.ps-info').textContent.includes('Bộ lọc: Xếp loại:')) return 'ban in khong ghi bo loc';
  return 'OK';
})()
"@
    # He thong (M7.1, M7.3): tao ban sao luu qua giao dien, co dong moi, lien ket tai ve; cai dat sai bao loi theo o.
    [void](Open-Url "$BaseUrl/index.html#/admin/system")
    Invoke-Flow 'he-thong-sao-luu' @"
(async () => { $waitJs
  const rows = () => [...document.querySelectorAll('table.data tbody tr:not(.row-message)')];
  if (!(await wait(() => document.querySelector('.dl dd') && !document.querySelector('[aria-busy=true]')))) return 'khong hien tinh trang';
  const before = rows().length;
  const btn = [...document.querySelectorAll('.page-head button')].find(b => b.textContent.includes('Tạo bản sao lưu ngay'));
  if (!btn) return 'thieu nut Tao ban sao luu';
  btn.click();
  if (!(await wait(() => rows().length === before + 1))) return 'khong them dong ban sao luu moi: ' + rows().length;
  const link = rows()[0].querySelector('a[href^="/api/v1/admin/backups/trainingstats-manual-"]');
  if (!link) return 'dong moi nhat khong co lien ket tai ve ban thu cong';
  const head = await fetch(link.getAttribute('href'));
  if (!head.ok || !(head.headers.get('content-disposition') || '').includes('attachment')) return 'tai ve that bai: ' + head.status;
  const f = document.querySelector('form');
  f.keep.value = '0'; f.requestSubmit();
  if (!(await wait(() => document.querySelector('.field-error:not(:empty)')))) return 'nhap so ban giu lai sai khong bao loi';
  if (document.documentElement.scrollWidth > innerWidth) return 'trang he thong cuon ngang';
  return 'OK';
})()
"@
    Save-Pdf 'print-class-results' "$BaseUrl/index.html#/classes/$cid/print"
    Save-Pdf 'print-user' "$BaseUrl/index.html#/users/$uid/print"

    # 4. Trang thai mo: hop thoai chi tiet nhat ky, menu "Them" tren dien thoai, loi form
    foreach ($w in $Widths) {
        Check 'audit-modal' $w {
            [void](Open-Url "$BaseUrl/index.html#/admin/audit")
            [void](Invoke-Js "(() => { const b = document.querySelector('.cell-actions button'); if (b) b.click(); return !!b; })()")
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.modal')")
        }
        Check 'user-move-modal' $w ([scriptblock]::Create(@"
            [void](Open-Url '$BaseUrl/index.html#/users/$uid')
            [void](Invoke-Js "(() => { const b = [...document.querySelectorAll('.page-actions button')].find(x => x.textContent.startsWith('Chuy')); if (b) b.click(); return !!b; })()")
            Start-Sleep -Milliseconds 400
            return [bool](Invoke-Js "!!document.querySelector('.modal .radio-inline')")
"@))
        Check 'request-reject-modal' $w ([scriptblock]::Create(@"
            [void](Open-Url '$BaseUrl/index.html#/requests/$rid')
            [void](Invoke-Js "(() => { const b = [...document.querySelectorAll('.page-actions button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Từ chối'); if (b) b.click(); return !!b; })()")
            Start-Sleep -Milliseconds 400
            return [bool](Invoke-Js "!!document.querySelector('.modal textarea')")
"@))
        Check 'class-candidates-modal' $w ([scriptblock]::Create(@"
            [void](Open-Url '$BaseUrl/index.html#/classes/$cid')
            [void](Invoke-Js "(async () => { $waitJs const b = await wait(() => [...document.querySelectorAll('.page-actions button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Thêm người')); if (!b) return false; b.click(); const i = await wait(() => document.getElementById('cand-q')); i.value = 'nguyen'; i.closest('form').requestSubmit(); return !!(await wait(() => document.querySelector('.modal .pick-row'))); })()")
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.modal .pick-row')")
"@))
        Check 'class-teachers-modal' $w ([scriptblock]::Create(@"
            # Sang trang khac truoc: mo lai dung dia chi hien tai khong sinh hashchange nen hop thoai truoc khong dong.
            [void](Open-Url '$BaseUrl/index.html#/')
            [void](Open-Url '$BaseUrl/index.html#/classes/$cid')
            [void](Invoke-Js "(async () => { $waitJs const b = await wait(() => [...document.querySelectorAll('.page-actions button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Gán giám khảo')); if (b) b.click(); return !!b; })()")
            Start-Sleep -Milliseconds 400
            return [bool](Invoke-Js "!!document.getElementById('teacher-q')")
"@))
        Check 'grade-confirm-modal' $w ([scriptblock]::Create(@"
            [void](Open-Url '$BaseUrl/index.html#/')
            [void](Open-Url '$BaseUrl/index.html#/classes/$cid/grade')
            [void](Invoke-Js "(async () => { $waitJs const i = await wait(() => document.querySelector('.grade-input')); if (!i) return false; i.value = '6,6'; i.dispatchEvent(new Event('input')); [...document.querySelectorAll('.grade-bar button')].find(b => b.textContent === 'Lưu điểm').click(); return !!(await wait(() => document.querySelector('.modal table.grade-confirm'))); })()")
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.modal table.grade-confirm')")
"@))
        Check 'result-edit-modal' $w ([scriptblock]::Create(@"
            [void](Open-Url '$BaseUrl/index.html#/')
            [void](Open-Url '$BaseUrl/index.html#/classes/$cid')
            [void](Invoke-Js "(async () => { $waitJs const b = await wait(() => [...document.querySelectorAll('table.data tbody button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Sửa điểm')); if (b) b.click(); return !!(await wait(() => document.querySelector('.modal textarea'))); })()")
            Start-Sleep -Milliseconds 400
            return [bool](Invoke-Js "!!document.querySelector('.modal textarea')")
"@))
        Check 'bell-menu-open' $w {
            [void](Open-Url "$BaseUrl/index.html#/me/password")
            [void](Open-Url "$BaseUrl/index.html#/")
            [void](Invoke-Js "(async () => { $waitJs const b = await wait(() => document.querySelector('.header-bell')); b.click(); return !!(await wait(() => document.querySelector('.bell-menu .menu-panel:not([hidden]) .menu-item, .bell-menu .menu-panel:not([hidden]) .menu-empty'))); })()")
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.bell-menu .menu-panel:not([hidden])')")
        }
        Check 'user-menu-open' $w {
            [void](Open-Url "$BaseUrl/index.html#/me/password")
            [void](Open-Url "$BaseUrl/index.html#/")
            [void](Invoke-Js "(() => { document.querySelector('.user-menu-btn').click(); return true; })()")
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.user-menu .menu-panel:not([hidden]) .header-logout')")
        }
        Check 'users-import-report' $w {
            [void](Open-Url "$BaseUrl/index.html#/")
            [void](Open-Url "$BaseUrl/index.html#/users/import")
            [void](Invoke-Js ('(async () => { ' + $waitJs + ' ' + $setFileJs + " await wait(() => document.querySelector('.importer input[type=file]')); setFile('" + $badUsersB64 + "'); return !!(await wait(() => document.querySelector('.import-result .alert'))); })()"))
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.import-result .alert-error')")
        }
        Check 'user-form-errors' $w {
            [void](Open-Url "$BaseUrl/index.html#/users/new")
            [void](Invoke-Js "(() => { document.querySelector('form').requestSubmit(); return true; })()")
            Start-Sleep -Milliseconds 200
            return [bool](Invoke-Js "document.querySelectorAll('.field-error:not(:empty)').length >= 3")
        }
        Check 'password-errors' $w {
            [void](Open-Url "$BaseUrl/index.html#/me/password")
            [void](Invoke-Js "(() => { document.querySelector('form').requestSubmit(); return true; })()")
            Start-Sleep -Milliseconds 200
            return [bool](Invoke-Js "document.querySelectorAll('.field-error:not(:empty)').length === 3")
        }
    }
    foreach ($w in ($Widths | Where-Object { $_ -lt 600 })) {
        Check 'more-menu' $w {
            # Sang trang khac truoc de menu cua lan kiem truoc (cung dia chi) duoc dong.
            [void](Open-Url "$BaseUrl/index.html#/me/password")
            [void](Open-Url "$BaseUrl/index.html#/")
            [void](Invoke-Js "(() => { const b = document.querySelector('.tabbar button'); b.click(); return true; })()")
            Start-Sleep -Milliseconds 300
            return [bool](Invoke-Js "!!document.querySelector('.modal .more-list')")
        }
    }

    # 5. Kich ban chuc nang sau khi dang nhap
    [void](Open-Url "$BaseUrl/index.html#/admin/audit")
    Invoke-Flow 'phan-trang-nhat-ky' @"
(async () => { $waitJs
  const info = () => document.querySelector('.pagination .page-info').textContent;
  if (!info().startsWith('1–20 /')) return 'trang 1 sai: ' + info();
  const rows1 = document.querySelectorAll('table.data tbody tr').length;
  document.querySelector('.pagination button[aria-label="Trang sau"]').click();
  const ok = await wait(() => info().startsWith('21–') && !document.querySelector('[aria-busy]'));
  if (!ok) return 'khong sang duoc trang 2: ' + info();
  return rows1 === 20 ? 'OK' : 'trang 1 co ' + rows1 + ' dong';
})()
"@
    Invoke-Flow 'loc-theo-hanh-dong' @"
(async () => { $waitJs
  const sel = document.getElementById('f-action'); sel.value = 'LOGIN_FAILED'; sel.dispatchEvent(new Event('change'));
  const ok = await wait(() => !document.querySelector('[aria-busy]')
    && document.querySelectorAll('table.data tbody .badge').length > 0
    && [...document.querySelectorAll('table.data tbody .badge')].every(b => b.textContent === 'Đăng nhập thất bại'));
  return ok ? 'OK' : 'loc khong dung';
})()
"@
    Invoke-Flow 'thanh-phan-select-nhieu-lua-chon' @"
(async () => {
  const { createSelect } = await import('/js/ui/select.js');
  const s = createSelect({ id: 'tst', name: 'tst', multiple: true, value: ['b'],
    options: [{ value: 'a', label: 'A' }, { value: 'b', label: 'B' }, { value: 'c', label: 'C' }] });
  document.body.append(s.element);
  s.element.querySelector('input[value=a]').click();
  s.element.querySelector('input[value=b]').click();
  const v = JSON.stringify(s.value()); s.element.remove();
  return v === '[\"a\"]' ? 'OK' : 'gia tri ' + v;
})()
"@
    Invoke-Flow 'hop-thoai-xac-nhan' @"
(async () => {
  const { confirmDialog } = await import('/js/ui/modal.js');
  const p1 = confirmDialog('Thu?', { okLabel: 'Dong y' });
  document.querySelector('.modal-foot .btn:last-child').click();
  const r1 = await p1;
  const p2 = confirmDialog('Thu lan 2?');
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  const r2 = await p2;
  const left = document.querySelectorAll('.modal-overlay').length;
  return r1 === true && r2 === false && left === 0 ? 'OK' : 'ket qua ' + r1 + ' ' + r2 + ' con ' + left;
})()
"@
    Invoke-Flow 'bang-sap-xep' @"
(async () => { $waitJs
  const { createTable } = await import('/js/ui/table.js');
  const calls = [];
  const t = createTable({ columns: [{ key: 'name', label: 'Ten', sortable: true }],
    load: async (q) => { calls.push(q.sort + ':' + q.dir); return { items: [{ name: 'x' }], page: 0, size: 20, total: 1 }; } });
  document.body.append(t.element);
  await t.reload(true);
  const btn = t.element.querySelector('.th-sort');
  btn.click(); await wait(() => calls.length >= 2);
  btn.click(); await wait(() => calls.length >= 3);
  const sort = t.element.querySelector('th').getAttribute('aria-sort');
  t.element.remove();
  return calls.join(',') === 'null:asc,name:asc,name:desc' && sort === 'descending' ? 'OK' : calls.join(',') + ' ' + sort;
})()
"@
    # So ngau nhien de chay lap lai tren cung DB khong bi trung ten.
    $tok = Get-Random -Minimum 1000 -Maximum 9999
    [void](Open-Url "$BaseUrl/index.html#/users/new")
    Invoke-Flow 'tao-nguoi-dung-qua-form' @"
(async () => { $waitJs
  const f = document.querySelector('form');
  f.lastName.value = 'Trần Thị'; f.firstName.value = 'Hạnh $tok'; f.personalCode.value = '';
  if (f.querySelector('select[name=unitId]')) return 'o don vi van la select';
  const trig = f.querySelector('.radio-select .multi-trigger');
  if (!trig) return 'thieu o chon don vi dang radio';
  trig.click();
  const panel = await wait(() => trig.parentElement.querySelector('.menu-panel:not([hidden])'));
  if (!panel) return 'khong mo khung chon don vi';
  const allRadios = [...panel.querySelectorAll('input[type=radio]')];
  const target = allRadios[allRadios.length - 1];
  const targetName = target.closest('label').querySelector('span').firstChild.textContent;
  const s = panel.querySelector('input[type=search]');
  s.value = targetName; s.dispatchEvent(new Event('input'));
  const shown = [...panel.querySelectorAll('input[type=radio]')];
  if (!shown.some(r => r.value === target.value) || (allRadios.length > 2 && shown.length >= allRadios.length)) return 'tim nhanh don vi khong loc: ' + shown.length + '/' + allRadios.length;
  shown.find(r => r.value === target.value).click();
  if (!(await wait(() => panel.hidden && trig.textContent.includes(targetName)))) return 'chon don vi khong dong khung hoac khong ghi ten: ' + trig.textContent;
  if (!f.category || !f.category.required) return 'thieu o Doi tuong bat buoc';
  f.category.value = 'SQ';
  // Chuyên ngành là danh sách ô đánh dấu hiện sẵn (người dùng chọn giữ như cũ).
  const specBox = f.querySelector('input[name=specialtyIds]');
  if (!specBox) return 'chuyen nganh phai la danh sach o danh dau hien san';
  specBox.click();
  // Từ 1024 px cả form và nút Lưu nằm trọn trong màn hình, không phải cuộn (2026-11-22).
  if (innerWidth >= 1024) {
    const submit = f.querySelector('button[type=submit]').getBoundingClientRect();
    if (document.documentElement.scrollHeight > innerHeight + 1) return 'form them nguoi dung phai cuon: ' + document.documentElement.scrollHeight + ' / ' + innerHeight;
    if (submit.bottom > innerHeight) return 'nut Tao nguoi dung nam ngoai man hinh';
  }
  f.requestSubmit();
  const ok = await wait(() => /#\/users\/\d+$/.test(location.hash) && document.querySelector('h1')?.textContent === 'Trần Thị Hạnh $tok');
  if (!ok) return 'khong sang trang chi tiet: ' + location.hash + ' ' + (document.querySelector('.field-error:not(:empty), .alert')?.textContent || '');
  // De trong ma dinh danh thi he thong tu cap dang ND + 6 chu so.
  const code = [...document.querySelectorAll('.dl .mono')].map(e => e.textContent).find(t => /^ND\d{6,}$/.test(t));
  return code ? 'OK' : 'khong thay ma tu cap dang ND000001';
})()
"@
    # Cap tai khoan o trang chi tiet (nguoi vua tao qua form chua co tai khoan), mat khau mac dinh.
    Invoke-Flow 'cap-tai-khoan-o-chi-tiet' @"
(async () => { $waitJs
  const btn = await wait(() => [...document.querySelectorAll('.card-actions button')].find(b => b.textContent === 'Cấp tài khoản'));
  if (!btn) return 'khong co nut Cap tai khoan';
  btn.click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai';
  if (!document.querySelector('.modal').textContent.includes('123456789')) return 'hop thoai khong bao mat khau mac dinh';
  f.username.value = 'uicap$tok' + (Date.now() % 100000);
  f.requestSubmit();
  const ok = await wait(() => !document.querySelector('.modal') && [...document.querySelectorAll('.card-actions button')].some(b => b.textContent === 'Đổi tài khoản'));
  if (!ok) return 'khong cap duoc: ' + (document.querySelector('.modal .field-error:not(:empty), .modal .alert-error')?.textContent || '');
  const id = location.hash.match(/(\d+)$/)[1];
  const u = await (await fetch('/api/v1/users/' + id)).json();
  if (!u.username || !u.hasPassword) return 'API: chua co tai khoan hoac mat khau';
  location.hash = '#/users/' + id + '/edit';
  const form = await wait(() => document.querySelector('form [name=lastName]')?.form);
  if (!form || form.querySelector('[name=username]')) return 'trang sua van con o tai khoan';
  if (innerWidth >= 1024 && document.documentElement.scrollHeight > innerHeight + 1) return 'form sua nguoi dung phai cuon: ' + document.documentElement.scrollHeight + ' / ' + innerHeight;
  return 'OK';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/users")
    Invoke-Flow 'tim-kiem-khong-dau' @"
(async () => { $waitJs
  document.getElementById('u-q').value = 'tran thi hanh $tok';
  document.querySelector('.filters form').requestSubmit();
  const ok = await wait(() => !document.querySelector('[aria-busy]')
    && [...document.querySelectorAll('table.data tbody .cell-link')].some(a => (a.querySelector('.cell-title') || a).textContent === 'Trần Thị Hạnh $tok')
    && document.querySelectorAll('table.data tbody tr').length === 1);
  return ok ? 'OK' : 'khong tim thay hoac thua dong: ' + document.querySelectorAll('table.data tbody tr').length;
})()
"@
    Invoke-Flow 'nguoi-dung-loc-nhieu-lua-chon' @"
(async () => { $waitJs
  if (document.querySelector('.filters form select')) return 'bo loc nguoi dung van dung o chon mot gia tri';
  for (const id of ['u-unit', 'u-rank', 'u-pos', 'u-spec', 'u-status', 'u-role']) if (!document.getElementById(id)?.classList.contains('multi-trigger')) return 'thieu o chon nhieu ' + id;
  if (innerWidth >= 1024) {
    const cells = [...document.querySelectorAll('.filters form > .field')];
    // Ô canh đáy (nhãn dài có thể hai dòng): đếm hàng theo cạnh dưới.
    const tops = [...new Set(cells.map(c => Math.round(c.getBoundingClientRect().bottom)))];
    if (tops.length !== 2) return 'bo loc nguoi dung phai dung 2 hang: ' + tops.length;
    // Ô Cấp bậc rộng bằng các ô lựa chọn khác (2026-11-22).
    const rankW = document.getElementById('u-rank').closest('.field').getBoundingClientRect().width;
    const catW = document.getElementById('u-cat').closest('.field').getBoundingClientRect().width;
    if (Math.abs(rankW - catW) > 2) return 'o Cap bac khong rong bang o khac: ' + Math.round(rankW) + ' / ' + Math.round(catW);
  }
  const q = document.getElementById('u-q'); q.value = ''; q.closest('form').requestSubmit();
  const openMs = async (id) => { document.getElementById(id).click(); return await wait(() => { const p = document.getElementById(id).parentElement.querySelector('.menu-panel:not([hidden])'); return p && p.querySelectorAll('input[type=checkbox]').length ? p : null; }); };
  const rows = () => [...document.querySelectorAll('table.data tbody tr:not(.row-message)')].length;
  const unitA = (await (await fetch('/api/v1/users/$($sample.personA)')).json()).unit.id;
  const up = await openMs('u-unit');
  const a = up.querySelector('input[type=checkbox][value="' + unitA + '"]');
  if (!a) return 'khong co don vi cua nguoi mau';
  a.click();
  const b = [...up.querySelectorAll('input[type=checkbox]')].find(x => x.value !== String(unitA) && !x.checked);
  b.click();
  document.getElementById('u-unit').click();
  const sp = await openMs('u-status');
  [...sp.querySelectorAll('input[type=checkbox]')].filter(x => x.value === 'ACTIVE' || x.value === 'INACTIVE').forEach(x => x.click());
  document.getElementById('u-status').click();
  const api = await (await fetch('/api/v1/users?size=20&unitId=' + unitA + ',' + b.value + '&status=ACTIVE,INACTIVE')).json();
  if (api.total < 1) return 'mau khong co nguoi trong 2 don vi';
  const ok = await wait(() => !document.querySelector('[aria-busy]') && rows() === api.items.length);
  if (!ok) return 'so dong khong khop API: ' + rows() + ' / ' + api.items.length;
  if (!document.getElementById('u-unit').textContent.includes('2 đơn vị')) return 'o don vi khong ghi 2 don vi';
  [...document.querySelectorAll('.filters button')].find(x => x.textContent === 'Bỏ lọc').click();
  const all = await (await fetch('/api/v1/users?size=20')).json();
  const back = await wait(() => !document.querySelector('[aria-busy]') && rows() === all.items.length && document.getElementById('u-unit').textContent.includes('Tất cả'));
  return back ? 'OK' : 'Bo loc khong tra lai danh sach day du';
})()
"@
    Save-Screenshot 'users-filters-1366'
    # Thao tac tren trang chi tiet nguoi dung, bam that qua giao dien
    [void](Open-Url "$BaseUrl/index.html#/users/$uid")
    Invoke-Flow 'khoa-va-mo-khoa-qua-giao-dien' @"
(async () => { $waitJs
  const click = (prefix) => { const b = [...document.querySelectorAll('.page-actions button')].find(x => x.textContent.startsWith(prefix)); if (b) b.click(); return !!b; };
  if (!click('Khóa')) return 'khong co nut Khoa';
  const ok1 = await wait(() => document.querySelector('.modal-foot .btn-danger, .modal-foot .btn:last-child'));
  if (!ok1) return 'khong mo hop thoai xac nhan';
  document.querySelector('.modal-foot .btn:last-child').click();
  const locked = await wait(() => document.querySelector('.page-sub .badge')?.textContent === 'Đã khóa');
  if (!locked) return 'chua chuyen sang Da khoa: ' + document.querySelector('.page-sub')?.textContent;
  if (!click('Mở khóa')) return 'khong co nut Mo khoa';
  await wait(() => document.querySelector('.modal-foot'));
  document.querySelector('.modal-foot .btn:last-child').click();
  const active = await wait(() => document.querySelector('.page-sub .badge')?.textContent === 'Hoạt động');
  return active ? 'OK' : 'chua mo khoa lai';
})()
"@
    Invoke-Flow -Width 360 'chuyen-don-vi-chon-radio' @"
(async () => { $waitJs
  const more = [...document.querySelectorAll('.page-actions button')].find(b => b.textContent.includes('Thao tác khác'));
  if (!more) return 'khong co nut Thao tac khac';
  more.click();
  const item = await wait(() => [...document.querySelectorAll('.menu-panel:not([hidden]) .menu-item')].find(b => b.textContent.trim() === 'Chuyển đơn vị'));
  if (!item) return 'khong co muc Chuyen don vi';
  item.click();
  const box = await wait(() => document.querySelector('.modal .radio-inline'));
  if (!box) return 'hop thoai chuyen don vi khong co danh sach radio';
  if (document.querySelector('.modal select')) return 'hop thoai chuyen don vi van dung select';
  const radios = () => [...box.querySelectorAll('input[type=radio]')];
  const total = radios().length;
  if (total < 2) return 'qua it don vi de chon';
  const last = radios()[total - 1];
  const name = last.closest('label').querySelector('span').firstChild.textContent;
  const s = box.querySelector('input[type=search]');
  s.value = name; s.dispatchEvent(new Event('input'));
  if (!radios().some(r => r.value === last.value)) return 'tim nhanh khong ra ' + name;
  radios().find(r => r.value === last.value).click();
  if (!box.querySelector('.radio-current').textContent.includes(name)) return 'khong ghi don vi da chon: ' + box.querySelector('.radio-current').textContent;
  if (box.querySelector('label.multi-option').getBoundingClientRect().height < 44) return 'muc chon thap hon 44px';
  if (document.documentElement.scrollWidth > innerWidth) return 'trang bi cuon ngang';
  return 'OK';
})()
"@
    Save-Screenshot 'user-move-radio-360'
    [void](Invoke-Js "(() => { document.querySelector('.modal .modal-close')?.click(); return true; })()")
    Invoke-Flow 'dat-lai-mat-khau-qua-giao-dien' @"
(async () => { $waitJs
  const b = [...document.querySelectorAll('.page-actions button')].find(x => x.textContent.startsWith('Đặt lại'));
  if (!b) return 'khong co nut Dat lai mat khau';
  b.click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai';
  f.newPassword.value = 'ngan'; f.confirm.value = 'ngan'; f.requestSubmit();
  const err = await wait(() => f.querySelector('.field-error:not(:empty)'));
  if (!err) return 'khong bao loi mat khau ngan';
  f.newPassword.value = 'DatLaiQuaGd@1'; f.confirm.value = 'DatLaiQuaGd@1'; f.requestSubmit();
  const ok = await wait(() => !document.querySelector('.modal') && document.querySelector('.toast-success'));
  return ok ? 'OK' : 'khong dong hop thoai hoac khong co thong bao';
})()
"@
    # Thong bao va de nghi (M3.4): chuong co so chua doc, bam thong bao sang de nghi, duyet qua giao dien.
    [void](Open-Url "$BaseUrl/index.html#/")
    Invoke-Flow 'chuong-co-so-chua-doc' @"
(async () => { $waitJs
  const c = await wait(() => { const e = document.querySelector('.header-bell .bell-count'); return e && !e.hidden && /^\d+\+?$/.test(e.textContent) ? e : null; });
  if (!c) return 'chuong khong hien so chua doc';
  const label = document.querySelector('.header-bell').getAttribute('aria-label');
  return label.includes('chưa đọc') ? 'OK' : 'aria-label sai: ' + label;
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/notifications")
    Invoke-Flow -Width 360 'bam-thong-bao-mo-de-nghi' @"
(async () => { $waitJs
  const b = await wait(() => [...document.querySelectorAll('.notice')].find(x => x.textContent.includes('$($sample.className)')));
  if (!b) return 'khong thay thong bao cua lop mau';
  if (!b.classList.contains('notice-unread')) return 'thong bao moi khong danh dau chua doc';
  b.click();
  const g = await wait(() => location.hash === '#/requests?focus=$rid' && document.querySelector('.req-group-focus'));
  if (!g) return 'khong mo danh sach de nghi danh dau nhom: ' + location.hash;
  const link = g.querySelector('.req-row-focus a.cell-link');
  if (!link || link.getAttribute('href') !== '#/requests/$rid') return 'khong danh dau dung de nghi trong nhom';
  if (document.querySelectorAll('.req-group').length < 1) return 'khong hien ca danh sach';
  link.click();
  const ok = await wait(() => location.hash === '#/requests/$rid' && document.querySelector('h1')?.textContent.startsWith('Đề nghị'));
  return ok ? 'OK' : 'khong sang trang de nghi: ' + location.hash;
})()
"@
    Invoke-Flow -Width 360 'duyet-de-nghi-qua-giao-dien' @"
(async () => { $waitJs
  const b = await wait(() => [...document.querySelectorAll('.page-actions button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Duyệt'));
  if (!b) return 'khong co nut Duyet';
  b.click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai duyet';
  f.requestSubmit();
  const done = await wait(() => !document.querySelector('.modal') && document.querySelector('.page-sub .badge')?.textContent === 'Đã duyệt');
  if (!done) return 'chua chuyen sang Da duyet: ' + (document.querySelector('.modal .alert')?.textContent || document.querySelector('.page-sub')?.textContent);
  const list = await (await fetch('/api/v1/classes/$($sample.classId)/participants')).json();
  return list.some(p => p.user.id === $uid) ? 'OK' : 'nguoi mau chua vao lop';
})()
"@
    # Trang lop (M3.5): tim ung vien va them vao lop, ghi ly do vang, bang cham tren giao dien.
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid")
    Invoke-Flow -Width 360 'them-nguoi-vao-lop-qua-giao-dien' @"
(async () => { $waitJs
  const b = await wait(() => [...document.querySelectorAll('.page-actions button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Thêm người'));
  if (!b) return 'khong co nut Them nguoi';
  b.click();
  const input = await wait(() => document.getElementById('cand-q'));
  if (!input) return 'khong mo hop thoai tim';
  input.value = 'tran thi hanh $tok'; input.closest('form').requestSubmit();
  const add = await wait(() => [...document.querySelectorAll('.modal .pick-row')].find(r => r.textContent.includes('Hạnh $tok'))?.querySelector('button'));
  if (!add) return 'khong thay ung vien';
  add.click();
  if (!(await wait(() => [...document.querySelectorAll('.modal .pick-row')].some(r => r.textContent.includes('Đã thêm'))))) return 'khong them duoc';
  document.querySelector('.modal-close').click();
  const ok = await wait(() => [...document.querySelectorAll('table.data tbody tr')].some(tr => tr.textContent.includes('Hạnh $tok')));
  return ok ? 'OK' : 'chua hien trong danh sach nguoi hoc';
})()
"@
    Invoke-Flow 'ghi-ly-do-vang-qua-giao-dien' @"
(async () => { $waitJs
  const row = await wait(() => [...document.querySelectorAll('table.data tbody tr')].find(tr => tr.textContent.includes('Hạnh $tok')));
  if (!row) return 'khong thay dong nguoi hoc';
  const b = [...row.querySelectorAll('button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Lý do vắng');
  if (!b) return 'khong co nut Ly do vang';
  b.click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai';
  const sel = f.querySelector('select'); sel.value = [...sel.options].find(o => o.value).value; sel.dispatchEvent(new Event('change', { bubbles: true }));
  f.requestSubmit();
  const ok = await wait(() => !document.querySelector('.modal') && [...document.querySelectorAll('table.data tbody tr')]
    .some(tr => tr.textContent.includes('Hạnh $tok') && tr.textContent.includes('Không kiểm tra')));
  return ok ? 'OK' : 'chua hien Khong kiem tra';
})()
"@
    Invoke-Flow -Width 360 'them-don-vi-vao-lop-qua-giao-dien' @"
(async () => { $waitJs
  const b = await wait(() => [...document.querySelectorAll('.page-actions button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Thêm đơn vị'));
  if (!b) return 'khong co nut Them don vi';
  b.click();
  const input = await wait(() => document.getElementById('cand-q'));
  if (!input) return 'khong mo hop thoai';
  input.value = 'tham muu'; input.closest('form').requestSubmit();
  const add = await wait(() => [...document.querySelectorAll('.modal .pick-row button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Thêm'));
  if (!add) return 'khong thay don vi de them';
  add.click();
  if (!(await wait(() => [...document.querySelectorAll('.modal .pick-row')].some(r => r.textContent.includes('Đã thêm'))))) return 'khong them duoc';
  document.querySelector('.modal-close').click();
  const ok = await wait(() => [...document.querySelectorAll('table.data tbody tr')].some(tr => tr.textContent.includes('Cả đơn vị')));
  return ok ? 'OK' : 'chua hien don vi trong danh sach';
})()
"@
    Invoke-Flow -Width 360 'go-nguoi-va-loc-da-go' @"
(async () => { $waitJs
  const row = await wait(() => [...document.querySelectorAll('table.data tbody tr')].find(tr => tr.textContent.includes('Hạnh $tok')));
  if (!row) return 'khong thay dong nguoi hoc';
  [...row.querySelectorAll('button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Gỡ').click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai go';
  f.reason.value = 'Kiểm tra giao diện'; f.requestSubmit();
  const gone = await wait(() => !document.querySelector('.modal') && ![...document.querySelectorAll('table.data tbody tr')].some(tr => tr.textContent.includes('Hạnh $tok')));
  if (!gone) return 'chua go khoi danh sach';
  [...document.querySelectorAll('.segmented button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Đã gỡ').click();
  const ok = await wait(() => [...document.querySelectorAll('table.data tbody tr')].some(tr => tr.textContent.includes('Hạnh $tok') && tr.textContent.includes('Kiểm tra giao diện')));
  return ok ? 'OK' : 'bo loc Da go khong hien nguoi vua go';
})()
"@
    Invoke-Flow 'nguoi-hoc-tim-nhanh-loc-nhieu-don-vi' @"
(async () => { $waitJs
  const back = document.querySelector('.back-link');
  if (!back || back.getAttribute('href') !== '#/classes' || back.nextElementSibling?.className !== 'page-head') return 'thieu nut quay lai o goc tren trai';
  if (document.querySelector('.page-head .btn-secondary')?.textContent.includes('Về')) return 'nut quay lai van nam trong nhom thao tac';
  if (!document.getElementById('p-q') || !document.getElementById('p-unit')) return 'thieu o tim nhanh hoac loc don vi (tab Da go)';
  [...document.querySelectorAll('.segmented button')].find(x => x.textContent.trim() === 'Đang tham gia').click();
  const rows = () => [...document.querySelectorAll('table.data tbody tr:not(.row-message)')];
  const all = await wait(() => rows().length >= 3 ? rows() : null);
  if (!all) return 'danh sach nguoi hoc qua it';
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const codes = list.map(p => p.user.personalCode).filter(Boolean);
  if (all.some(tr => codes.some(c => tr.children[0].textContent.includes(c)))) return 'cot ho ten van hien ma dinh danh';
  const one = list.find(p => p.user.kind === 'PERSON' && p.user.personalCode);
  const q = document.getElementById('p-q');
  q.value = one.user.personalCode; q.dispatchEvent(new Event('input', { bubbles: true }));
  const byCode = await wait(() => { const r = rows(); return r.length === 1 && r[0].textContent.includes(one.user.fullName) ? r : null; });
  if (!byCode) return 'tim theo ma khong ra dung 1 nguoi';
  q.value = one.snapshotUnit.name.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase(); q.dispatchEvent(new Event('input', { bubbles: true }));
  const byUnit = await wait(() => { const r = rows(); return r.length >= 1 && r.every(tr => tr.textContent.includes(one.snapshotUnit.name)) ? r : null; });
  if (!byUnit) return 'tim khong dau theo ten don vi khong khop';
  q.value = ''; q.dispatchEvent(new Event('input', { bubbles: true }));
  await wait(() => rows().length === all.length);
  document.getElementById('p-unit').click();
  const panel = await wait(() => { const p = document.getElementById('p-unit').parentElement.querySelector('.menu-panel:not([hidden])'); return p && p.querySelectorAll('input[type=checkbox]').length >= 2 ? p : null; });
  if (!panel) return 'o loc don vi thieu lua chon';
  const inUnits = (vals) => list.filter(p => vals.some(v => p.snapshotUnit.code === v || p.snapshotUnit.code.startsWith(v + '.'))).length;
  const opts = [...panel.querySelectorAll('input[type=checkbox]')];
  const first = opts.find(b => inUnits([b.value]) < list.length);
  if (!first) return 'mau can nguoi hoc o it nhat 2 don vi';
  first.click();
  const oneUnit = await wait(() => rows().length === inUnits([first.value]));
  if (!oneUnit) return 'loc 1 don vi khong khop: ' + rows().length + ' / ' + inUnits([first.value]);
  // Danh sách được vẽ lại sau khi lọc: lấy lại ô đánh dấu đang gắn trên trang (bấm ô đã bị gỡ thì không có tác dụng).
  const second = [...panel.querySelectorAll('input[type=checkbox]')].find(b => b.value !== first.value && !b.checked);
  second.click();
  document.getElementById('p-unit').click();
  const want = inUnits([first.value, second.value]);
  const two = await wait(() => rows().length === want ? rows() : null);
  if (!two) return 'loc 2 don vi khong khop: ' + rows().length + ' / ' + want + ' [' + first.value + ',' + second.value + '] hien: '
    + rows().map(tr => tr.children[0].textContent).join('; ') + ' | can: ' + list.filter(p => [first.value, second.value].some(v => p.snapshotUnit.code === v || p.snapshotUnit.code.startsWith(v + '.'))).map(p => p.user.fullName + '@' + p.snapshotUnit.code).join('; ');
  if (!document.getElementById('p-unit').textContent.includes('2 đơn vị')) return 'o loc khong ghi 2 don vi';
  document.getElementById('p-unit').click();
  (await wait(() => [...document.querySelectorAll('.menu-panel:not([hidden]) .multi-foot button')].find(b => b.textContent === 'Bỏ chọn'))).click();
  document.getElementById('p-unit').click();
  if (!(await wait(() => rows().length === all.length))) return 'bo chon don vi khong hien lai du danh sach';
  const tall = [...document.querySelectorAll('.state-line')].filter(s => s.getBoundingClientRect().height > 34);
  return tall.length === 0 ? 'OK' : 'cot tinh trang xuong dong o man lon: ' + tall[0].textContent;
})()
"@
    Invoke-Flow 'nguoi-hoc-loc-tinh-trang-xep-loai' @"
(async () => { $waitJs
  for (const id of ['p-state', 'p-grade', 'p-cat']) if (!document.getElementById(id)?.classList.contains('multi-trigger')) return 'thieu o loc ' + id;
  const rows = () => [...document.querySelectorAll('table.data tbody tr:not(.row-message)')];
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  if (!(await wait(() => rows().length === list.length))) return 'danh sach chua day du';
  const openMs = async (id) => { document.getElementById(id).click(); return await wait(() => { const p = document.getElementById(id).parentElement.querySelector('.menu-panel:not([hidden])'); return p && p.querySelectorAll('input[type=checkbox]').length ? p : null; }); };
  const resetBtn = () => [...document.querySelectorAll('.p-toolbar button')].find(b => b.textContent === 'Bỏ lọc');
  // Tình trạng: chọn "Chưa kiểm tra", số dòng và số trong nhãn khớp API.
  const sp = await openMs('p-state');
  const notTested = list.filter(p => p.state === 'NOT_TESTED').length;
  const nt = sp.querySelector('input[value=NOT_TESTED]');
  if (!nt || !nt.closest('label').textContent.includes('(' + notTested + ')')) return 'nhan Chua kiem tra khong ghi dung so: ' + nt?.closest('label').textContent;
  nt.click();
  document.getElementById('p-state').click();
  if (!(await wait(() => rows().length === notTested && rows().every(tr => tr.textContent.includes('Chưa kiểm tra'))))) return 'loc Chua kiem tra khong khop: ' + rows().length + ' / ' + notTested;
  resetBtn().click();
  if (!(await wait(() => rows().length === list.length))) return 'Bo loc khong hien lai du danh sach';
  // Xếp loại: chọn xếp loại có trong danh sách.
  const graded = list.filter(p => p.result);
  if (graded.length) {
    const g = graded[0].result.grade;
    const gp = await openMs('p-grade');
    gp.querySelector('input[value=' + g + ']').click();
    document.getElementById('p-grade').click();
    const want = graded.filter(p => p.result.grade === g).length;
    if (!(await wait(() => rows().length === want))) return 'loc xep loai ' + g + ' khong khop: ' + rows().length + ' / ' + want;
    resetBtn().click();
    if (!(await wait(() => rows().length === list.length))) return 'Bo loc xep loai khong hien lai du danh sach';
  }
  // Đối tượng: chỉ liệt kê loại có trong danh sách.
  const cp = await openMs('p-cat');
  const kinds = new Set(list.map(p => p.user.kind === 'UNIT' ? 'UNIT' : (p.user.category || 'NONE')));
  const vals = [...cp.querySelectorAll('input[type=checkbox]')].map(b => b.value);
  document.getElementById('p-cat').click();
  if (vals.length !== kinds.size || vals.some(v => !kinds.has(v))) return 'o doi tuong liet ke sai: ' + vals.join(',');
  // Từ 1024 px, các ô của thanh lọc nằm trên một hàng.
  if (innerWidth >= 1024) {
    const tops = new Set([...document.querySelectorAll('.p-toolbar > .field')].map(f => Math.round(f.getBoundingClientRect().bottom)));
    if (tops.size !== 1) return 'thanh loc nguoi hoc xuong ' + tops.size + ' hang';
  }
  return 'OK';
})()
"@
    Save-Screenshot 'class-participant-filters-1366'
    # Cham diem (M4.3): o lon ban phim so, xem truoc xep loai, xac nhan, sau khi luu hien diem da ghi; nguoi da co diem bi khoa.
    $st = $sample.stamp
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid/grade")
    Invoke-Flow -Width 360 'nhap-sai-diem-bao-loi' @"
(async () => { $waitJs
  const row = await wait(() => [...document.querySelectorAll('.grade-row')].find(c => c.textContent.includes('Biên $st')));
  if (!row) return 'khong thay dong nguoi hoc';
  const i = row.querySelector('.grade-input');
  if (!i || i.getAttribute('inputmode') !== 'decimal') return 'o nhap diem khong dung ban phim so';
  i.value = '6.55'; i.dispatchEvent(new Event('input'));
  if (!row.querySelector('.grade-preview').textContent.includes('một chữ số thập phân')) return 'khong bao loi qua mot chu so thap phan';
  if (i.getAttribute('aria-invalid') !== 'true') return 'o nhap khong danh dau loi';
  [...document.querySelectorAll('.grade-bar button')].find(b => b.textContent === 'Lưu điểm').click();
  await new Promise(r => setTimeout(r, 300));
  if (document.querySelector('.modal')) return 'diem sai van mo hop thoai xac nhan';
  i.value = ''; i.dispatchEvent(new Event('input'));
  return 'OK';
})()
"@
    Invoke-Flow 'cham-diem-gom-don-vi-va-loc' @"
(async () => { $waitJs
  const rows = await wait(() => { const r = [...document.querySelectorAll('.grade-group .grade-row')]; return r.length ? r : null; });
  if (!rows) return 'danh sach khong gom theo don vi';
  const list = (await (await fetch('/api/v1/classes/$cid/participants')).json()).filter(p => !p.result);
  const heads = [...document.querySelectorAll('.grade-group-head h2')].map(x => x.textContent);
  const want = [...new Set(list.map(p => p.snapshotUnit.name))];
  if (heads.length !== want.length || want.some(n => !heads.includes(n))) return 'nhom don vi khong khop: ' + heads.join(',') + ' / ' + want.join(',');
  const withCode = list.find(p => p.user.personalCode);
  const r = rows.find(x => x.textContent.includes(withCode.user.fullName));
  const line = r.querySelector('.grade-line');
  if (!line || !line.textContent.includes(withCode.user.personalCode) || line.getBoundingClientRect().height > 26) return 'ho ten va ma khong tren mot dong';
  if (r.querySelector('.grade-input').getBoundingClientRect().width > 72) return 'o nhap diem qua rong';
  const sb = document.querySelector('.grade-toolbar [role=radiogroup]').getBoundingClientRect();
  const qb = document.getElementById('grade-search').getBoundingClientRect();
  const ub = document.getElementById('grade-unit').getBoundingClientRect();
  if (Math.abs(sb.bottom - qb.bottom) > 3 || Math.abs(ub.bottom - qb.bottom) > 3) return 'o loc khong thang hang: ' + [qb.bottom, ub.bottom, sb.bottom].map(Math.round).join('/');
  document.getElementById('grade-unit').click();
  const panel = await wait(() => { const p = document.getElementById('grade-unit').parentElement.querySelector('.menu-panel:not([hidden])'); return p && p.querySelectorAll('input[type=checkbox]').length ? p : null; });
  if (!panel) return 'khong mo duoc loc don vi';
  const box = panel.querySelector('input[type=checkbox][value="' + withCode.snapshotUnit.code + '"]');
  box.click(); document.getElementById('grade-unit').click();
  const n = list.filter(p => p.snapshotUnit.code === box.value || p.snapshotUnit.code.startsWith(box.value + '.')).length;
  const ok = await wait(() => document.querySelectorAll('.grade-group').length === 1 && document.querySelectorAll('.grade-row').length === n);
  if (!ok) return 'loc don vi khong khop: ' + document.querySelectorAll('.grade-row').length + ' / ' + n;
  document.getElementById('grade-unit').click();
  (await wait(() => [...document.querySelectorAll('.menu-panel:not([hidden]) .multi-foot button')].find(b => b.textContent === 'Bỏ chọn'))).click();
  document.getElementById('grade-unit').click();
  return (await wait(() => document.querySelectorAll('.grade-row').length === list.length)) ? 'OK' : 'bo chon don vi khong hien lai du';
})()
"@
    Invoke-Flow 'cham-diem-luu-nhieu-nguoi-mot-lan' @"
(async () => { $waitJs
  if ([...document.querySelectorAll('.grade-row')].find(c => c.textContent.includes('Chấm $st'))) return 'nguoi da co diem van hien o loc Chua co diem';
  const rows = await wait(() => { const r = [...document.querySelectorAll('.grade-row')]; return r.length ? r : null; });
  const cols = getComputedStyle(document.querySelector('.grade-grid')).gridTemplateColumns.split(' ').length;
  if (cols < 2) return 'man rong phai co it nhat 2 nguoi moi hang, dang ' + cols;
  if (rows[0].getBoundingClientRect().height > 110) return 'moi nguoi chiem qua cao: ' + Math.round(rows[0].getBoundingClientRect().height) + 'px';
  if (document.querySelectorAll('.grade-row button.btn').length) return 'van con nut luu tren tung nguoi';
  const a = rows.find(c => c.textContent.includes('Điểm $st'));
  const b = rows.find(c => c.textContent.includes('Lưu $st'));
  if (!a || !b) return 'thieu nguoi hoc mau';
  const ia = a.querySelector('.grade-input'); const ib = b.querySelector('.grade-input');
  ia.value = '6,6'; ia.dispatchEvent(new Event('input'));
  if (!a.querySelector('.grade-preview').textContent.includes('Khá')) return 'xem truoc xep loai sai: ' + a.querySelector('.grade-preview').textContent;
  ib.value = '9'; ib.dispatchEvent(new Event('input'));
  const bar = document.querySelector('.grade-bar');
  if (!bar.textContent.includes('2 điểm')) return 'thanh luu khong dem dung: ' + bar.textContent;
  [...bar.querySelectorAll('button')].find(x => x.textContent === 'Lưu điểm').click();
  const m = await wait(() => document.querySelector('.modal table.grade-confirm'));
  if (!m) return 'khong mo buoc xac nhan';
  const t = m.textContent;
  if (!t.includes('Điểm $st') || !t.includes('6.6') || !t.includes('Khá') || !t.includes('Lưu $st') || !t.includes('9.0') || !t.includes('Giỏi')) return 'xac nhan thieu ten, diem hoac xep loai: ' + t;
  [...document.querySelectorAll('.modal-foot button')].find(x => x.textContent === 'Lưu 2 điểm').click();
  const saved = await wait(() => document.querySelectorAll('.grade-row.is-saved').length === 2);
  if (!saved) return 'khong chuyen sang da luu: ' + (document.querySelector('.toast')?.textContent || '');
  if (document.querySelector('.grade-row.is-saved .grade-input')) return 'da luu ma o nhap chua khoa';
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const sa = list.find(p => p.user.fullName.includes('Điểm $st')); const sb = list.find(p => p.user.fullName.includes('Lưu $st'));
  if (sa?.result?.scoreX10 !== 66 || sb?.result?.scoreX10 !== 90) return 'may chu ghi sai diem';
  [...document.querySelectorAll('.segmented button')].find(x => x.textContent === 'Tất cả').click();
  const locked = await wait(() => [...document.querySelectorAll('.grade-row.is-done')].find(c => c.textContent.includes('Chấm $st')));
  if (!locked || locked.querySelector('.grade-input')) return 'nguoi da co diem khong bi khoa o nhap';
  return locked.textContent.includes('7.5') ? 'OK' : 'khong hien diem cu';
})()
"@
    # Xep loai xem truoc o giao dien phai khop cot grade cua DB o moi diem bien (R15): cham that qua API roi so.
    Invoke-Flow 'xep-loai-xem-truoc-khop-may-chu' @"
(async () => {
  const { previewGrade } = await import('/js/core/refdata.js');
  const token = async () => (await (await fetch('/api/v1/auth/csrf')).json()).token;
  const send = async (url, body) => {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': await token() }, body: JSON.stringify(body) });
    const j = await r.json(); if (!r.ok) throw new Error(url + ': ' + j.message); return j;
  };
  const me = await (await fetch('/api/v1/users/$uid')).json();
  const cls = await send('/api/v1/classes', { periodId: $($sample.periodId), name: 'Nội dung điểm biên UI $st' });
  const bad = [];
  for (const s of [0, 4.9, 5.0, 6.5, 6.6, 7.9, 8.0, 10]) {
    const person = await send('/api/v1/users', { category: 'SQ', lastName: 'Biên', firstName: String(s), unitId: me.unit.id });
    const part = await send('/api/v1/classes/' + cls.id + '/participants', { userId: person.id });
    const saved = await send('/api/v1/classes/' + cls.id + '/participants/' + part.id + '/result', { score: s });
    if (saved.result.grade !== previewGrade(Math.round(s * 10))) bad.push(s + ': may chu ' + saved.result.grade + ', xem truoc ' + previewGrade(Math.round(s * 10)));
  }
  return bad.length ? bad.join(' | ') : 'OK';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid")
    Invoke-Flow 'sua-diem-va-lich-su' @"
(async () => { $waitJs
  const row = () => [...document.querySelectorAll('table.data tbody tr')].find(tr => tr.textContent.includes('Chấm $st'));
  const b = await wait(() => row() && [...row().querySelectorAll('button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Sửa điểm'));
  if (!b) return 'khong co nut Sua diem';
  b.click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai sua diem';
  f.score.value = '7.5'; f.score.dispatchEvent(new Event('input')); f.reason.value = 'Thử'; f.requestSubmit();
  if (!(await wait(() => f.querySelector('.field-error:not(:empty)')))) return 'khong bao loi diem moi giong diem cu';
  f.score.value = '8'; f.score.dispatchEvent(new Event('input'));
  if (!f.querySelector('.score-preview').textContent.includes('Giỏi')) return 'xem truoc khi sua sai';
  f.reason.value = 'Phúc khảo giao diện'; f.requestSubmit();
  const ok = await wait(() => !document.querySelector('.modal') && row()?.textContent.includes('8.0') && row()?.textContent.includes('Đã sửa'));
  if (!ok) return 'bang chua cap nhat diem da sua: ' + (row()?.textContent || '');
  [...row().querySelectorAll('button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Sửa điểm').click();
  const h = await wait(() => [...document.querySelectorAll('.modal .pick-row')].find(r => r.textContent.includes('7.5') && r.textContent.includes('8.0')));
  if (!h) return 'lich su khong co lan sua';
  const good = h.textContent.includes('Phúc khảo giao diện');
  document.querySelector('.modal-close').click();
  return good ? 'OK' : 'lich su thieu ly do';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/users/$uid")
    Invoke-Flow -Width 360 'lich-su-diem-o-trang-nguoi-dung' @"
(async () => { $waitJs
  const card = await wait(() => [...document.querySelectorAll('.card')].find(c => c.querySelector('h2')?.textContent === 'Nội dung kiểm tra đã tham gia và điểm'));
  if (!card) return 'khong co the lich su diem';
  const ok = await wait(() => [...card.querySelectorAll('tbody tr')].some(tr => tr.textContent.includes('$($sample.className)') && tr.textContent.includes('Chưa kiểm tra')));
  if (!ok) return 'lich su khong co noi dung mau: ' + card.textContent.slice(0, 200);
  if (![...card.querySelectorAll('.hist-head h3')].some(x => x.textContent.startsWith('Đợt kiểm tra UI'))) return 'lich su khong gom theo dot';
  const has = () => [...card.querySelectorAll('tbody tr')].some(tr => tr.textContent.includes('$($sample.className)'));
  const exam = (await (await fetch('/api/v1/classes/$cid')).json()).examDate;
  const next = new Date(exam + 'T00:00:00Z'); next.setUTCDate(next.getUTCDate() + 1);
  const from = card.querySelector('[id^=hist-from]'), to = card.querySelector('[id^=hist-to]');
  if (!from || !to) return 'thieu o loc theo ngay';
  const setDate = (el, v) => { el.value = v; el.dispatchEvent(new Event('change')); };
  setDate(from, next.toISOString().slice(0, 10));
  if (!(await wait(() => !has()))) return 'loc tu ngay sau ngay kiem tra van hien noi dung mau';
  setDate(from, ''); setDate(to, exam);
  if (!(await wait(() => has()))) return 'loc den dung ngay kiem tra khong hien noi dung mau';
  setDate(from, next.toISOString().slice(0, 10));
  if (!card.querySelector('.field-error')?.textContent.includes('Đến ngày')) return 'khong bao loi tu ngay sau den ngay';
  setDate(from, ''); setDate(to, '');
  if (!(await wait(() => has()))) return 'bo loc ngay khong tra lai danh sach';
  const trig = card.querySelector('[id^=hist-state]');
  trig.click();
  const box = await wait(() => trig.parentElement.querySelector('.menu-panel:not([hidden]) input[value=TESTED]'));
  if (!box) return 'khong mo duoc bo loc tinh trang';
  box.click(); trig.click();
  const gone = await wait(() => ![...card.querySelectorAll('tbody tr')].some(tr => tr.textContent.includes('$($sample.className)')));
  return gone ? 'OK' : 'loc tinh trang Da co diem khong an noi dung chua kiem tra';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    Invoke-Flow 'lich-su-diem-nhieu-dot-chuan-bi' @"
(async () => {
  const token = async () => (await (await fetch('/api/v1/auth/csrf')).json()).token;
  const send = async (url, body) => {
    const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': await token() }, body: JSON.stringify(body) });
    const j = await r.json(); if (!r.ok) throw new Error(url + ': ' + j.message); return j;
  };
  const period = await send('/api/v1/periods', { name: 'Đợt đối chiếu cột $st', startDate: '2026-10-01', endDate: '2026-10-31' });
  for (const [name, score] of [['Nội dung đối chiếu A $st', 9.5], ['Nội dung đối chiếu B $st', null]]) {
    const cls = await send('/api/v1/classes', { periodId: period.id, name });
    const part = await send('/api/v1/classes/' + cls.id + '/participants', { userId: $uid });
    if (score !== null) await send('/api/v1/classes/' + cls.id + '/participants/' + part.id + '/result', { score });
  }
  return 'OK';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/users/$uid")
    Invoke-Flow 'lich-su-diem-cot-thang-hang' @"
(async () => { $waitJs
  document.getElementById('toast-region')?.replaceChildren();
  const card = await wait(() => [...document.querySelectorAll('.card')].find(c => c.querySelector('h2')?.textContent === 'Nội dung kiểm tra đã tham gia và điểm'));
  if (!card) return 'khong co the lich su diem';
  const tables = await wait(() => { const t = [...card.querySelectorAll('.hist-group table.data')]; return t.length && t.every(x => x.querySelector('tbody tr:not(.row-message)')) ? t : null; });
  if (!tables) return 'lich su chua co bang';
  const edges = (sel) => tables.map(t => { const c = t.querySelector(sel); return c ? Math.round(c.getBoundingClientRect().left) + '/' + Math.round(c.getBoundingClientRect().width) : 'x'; });
  for (const sel of ['th.hist-col-date', 'th.hist-col-result']) {
    if (new Set(edges(sel)).size !== 1) return 'cot lech giua cac dot: ' + sel + ' ' + edges(sel).join(' ');
  }
  for (const td of card.querySelectorAll('.hist-group tbody td')) {
    const boxes = [...td.querySelectorAll('a, .score, .badge, .cell-sub')].map(e => e.getBoundingClientRect());
    if (boxes.length && Math.max(...boxes.map(b => b.top)) >= Math.min(...boxes.map(b => b.bottom))) return 'o bi xuong dong: ' + td.textContent.trim();
  }
  return 'OK';
})()
"@
    Save-Screenshot 'user-history-columns-1366'
    [void](Open-Url "$BaseUrl/index.html#/")
    Invoke-Flow 'thu-gon-thanh-ben' @"
(async () => { $waitJs
  const nav = document.getElementById('app-nav');
  const toggle = document.querySelector('.nav-toggle');
  if (!toggle || getComputedStyle(toggle).display === 'none') return 'khong co nut thu gon o 1366 px';
  const wide = nav.getBoundingClientRect().width;
  toggle.click();
  const narrow = await wait(() => nav.getBoundingClientRect().width < wide - 100 ? nav.getBoundingClientRect().width : 0);
  if (!narrow) return 'thanh ben khong thu gon: ' + nav.getBoundingClientRect().width;
  if (toggle.getAttribute('aria-expanded') !== 'false') return 'aria-expanded sai khi thu gon';
  toggle.click();
  const back = await wait(() => nav.getBoundingClientRect().width >= wide - 1);
  return back ? 'OK' : 'khong mo lai duoc thanh ben';
})()
"@
    Invoke-Flow -Width 360 'xem-nhanh-thong-bao' @"
(async () => { $waitJs
  document.querySelector('.header-bell').click();
  const item = await wait(() => document.querySelector('.bell-menu .menu-panel:not([hidden]) .menu-item'));
  if (!item) return 'khung thong bao khong co muc nao';
  const r = document.querySelector('.bell-menu .menu-panel').getBoundingClientRect();
  if (r.left < 0 || r.right > window.innerWidth + 1) return 'khung thong bao tran man hinh: ' + Math.round(r.left) + '..' + Math.round(r.right);
  document.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  if (!document.querySelector('.bell-menu .menu-panel').hidden) return 'Esc khong dong khung';
  document.querySelector('.header-bell').click();
  (await wait(() => document.querySelector('.bell-menu .menu-panel:not([hidden]) .menu-item'))).click();
  const moved = await wait(() => location.hash !== '#/' && document.querySelector('.bell-menu .menu-panel').hidden);
  return moved ? 'OK' : 'bam thong bao khong chuyen trang';
})()
"@
    # Thao tac ngay tren dong (kieu Admindek): khoa, mo khoa nguoi dung khong can vao trang chi tiet.
    [void](Open-Url "$BaseUrl/index.html#/users")
    Invoke-Flow 'khoa-mo-khoa-tren-dong' @"
(async () => { $waitJs
  const btn = (row, label) => [...row.querySelectorAll('button, a')].find(x => x.getAttribute('aria-label') === label);
  const u = await (await fetch('/api/v1/users/$uid')).json();
  const q = await wait(() => document.getElementById('u-q'));
  q.value = u.personalCode; q.closest('form').requestSubmit();
  const row = () => [...document.querySelectorAll('table.data tbody tr')].find(tr => tr.textContent.includes(u.personalCode));
  if (!(await wait(() => row() && btn(row(), 'Khóa')))) return 'khong co nut Khoa tren dong';
  if (!btn(row(), 'Sửa') || !btn(row(), 'Xóa')) return 'thieu nut Sua hoac Xoa tren dong';
  btn(row(), 'Khóa').click();
  (await wait(() => document.querySelector('.modal-foot .btn:last-child'))).click();
  if (!(await wait(() => row()?.textContent.includes('Đã khóa') && btn(row(), 'Mở khóa')))) return 'dong chua chuyen sang Da khoa';
  btn(row(), 'Mở khóa').click();
  (await wait(() => document.querySelector('.modal-foot .btn:last-child'))).click();
  const ok = await wait(() => row()?.textContent.includes('Hoạt động'));
  return ok ? 'OK' : 'chua mo khoa lai';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/classes?periodId=$($sample.periodId)")
    Invoke-Flow -Width 360 'thao-tac-tren-dong-lop' @"
(async () => { $waitJs
  const row = await wait(() => [...document.querySelectorAll('table.data tbody tr')].find(tr => tr.textContent.includes('$($sample.className)')));
  if (!row) return 'khong thay lop mau';
  const labels = [...row.querySelectorAll('.row-actions [aria-label]')].map(x => x.getAttribute('aria-label'));
  const need = ['Xem chi tiết', 'Chấm điểm', 'Sửa', 'Đóng nội dung kiểm tra', 'Xóa'];
  const missing = need.filter(l => !labels.includes(l));
  return missing.length ? 'thieu nut: ' + missing.join(', ') : 'OK';
})()
"@
    Invoke-Flow 'noi-dung-chua-cham-xong-noi-bat' @"
(async () => { $waitJs
  const api = await (await fetch('/api/v1/classes?periodId=$($sample.periodId)&size=100')).json();
  const mine = api.items.find(c => c.name === '$($sample.className)');
  if (!mine || mine.pendingCount < 1) return 'lop mau phai con nguoi chua cham: ' + JSON.stringify(mine);
  const row = await wait(() => [...document.querySelectorAll('table.data tbody tr')].find(tr => tr.textContent.includes('$($sample.className)')));
  if (!row) return 'khong thay lop mau';
  if (!row.classList.contains('row-attention')) return 'dong lop chua cham xong khong duoc to noi bat';
  if (!row.textContent.includes('Còn ' + mine.pendingCount + ' chưa chấm')) return 'thieu chu Con N chua cham: ' + row.textContent;
  // Tiến độ chấm một dòng (2026-11-22): nhãn, thanh, số đã có điểm cùng hàng để dòng bảng không cao lệch.
  // Thanh tiến độ ẩn ở bảng hẹp (600–1439 px), chỉ so các phần đang hiện.
  const parts = [...row.querySelectorAll('.progress-cell > .badge, .progress-mini, .progress-count')].map(e => e.getBoundingClientRect()).filter(p => p.width > 0);
  if (parts.length < 2) return 'o tien do thieu thanh phan: ' + parts.length;
  const wrap = row.closest('.table-scroll') || row.closest('table').parentElement;
  if (wrap.scrollWidth > wrap.clientWidth + 1) return 'bang noi dung kiem tra tran ngang: ' + wrap.scrollWidth + ' / ' + wrap.clientWidth;
  const mid = parts.map(p => (p.top + p.bottom) / 2);
  if (Math.max(...mid) - Math.min(...mid) > 4) return 'tien do cham khong nam tren mot dong: ' + mid.map(Math.round).join(',');
  // Ô thao tác là ô bảng thật, cao bằng hàng (trước đây bị display: block của dạng thẻ nên đường kẻ dưới lệch).
  const act = row.querySelector('td.cell-actions');
  if (getComputedStyle(act).display !== 'table-cell') return 'o thao tac khong phai o bang: ' + getComputedStyle(act).display;
  if (Math.abs(act.getBoundingClientRect().bottom - row.getBoundingClientRect().bottom) > 1) return 'o thao tac lech day hang';
  const doneRow = [...document.querySelectorAll('table.data tbody tr')].find(tr => { const c = api.items.find(x => tr.textContent.includes(x.name)); return c && c.participantCount > 0 && c.pendingCount === 0; });
  if (doneRow && doneRow.classList.contains('row-attention')) return 'lop da cham xong van bi to';
  [...document.querySelectorAll('.filters [role=radio]')].find(b => b.textContent === 'Chưa chấm xong').click();
  const want = api.items.filter(c => c.pendingCount > 0).length;
  const ok = await wait(() => { const r = [...document.querySelectorAll('table.data tbody tr:not(.row-message)')]; return r.length === Math.min(want, 20) && r.every(tr => tr.classList.contains('row-attention')) ? r : null; });
  if (!ok) return 'loc Chua cham xong khong khop API: ' + document.querySelectorAll('table.data tbody tr').length + ' / ' + want;
  const all = await (await fetch('/api/v1/classes?ungraded=true&size=1')).json();
  location.hash = '#/';
  const alert = await wait(() => document.querySelector('a.dash-alert'));
  if (!alert || !alert.textContent.startsWith(all.total + ' nội dung')) return 'trang chu khong nhac noi dung chua cham xong: ' + alert?.textContent;
  return alert.getAttribute('href') === '#/classes?ungraded=true' ? 'OK' : 'lien ket nhac sai';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/me/password")
    Invoke-Flow 'menu-chi-danh-dau-muc-dung' @"
(async () => { $waitJs
  await wait(() => document.querySelector('#app-nav [aria-current=page]'));
  const cur = [...document.querySelectorAll('#app-nav [aria-current=page]')].map(a => a.textContent.trim());
  return cur.length === 1 && cur[0] === 'Đổi mật khẩu' ? 'OK' : 'muc dang chon: ' + cur.join(', ');
})()
"@
    # Them noi dung kiem tra bang hop thoai, gan giam khao ngay trong hop thoai (goi y theo ten, ma, tai khoan).
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/classes")
    Invoke-Flow 'them-noi-dung-qua-hop-thoai-kem-giam-khao' @"
(async () => { $waitJs
  const add = await wait(() => [...document.querySelectorAll('.page-head button')].find(b => b.textContent.includes('Thêm nội dung kiểm tra')));
  if (!add) return 'khong co nut Them noi dung kiem tra';
  add.click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai';
  if (!location.hash.startsWith('#/classes')) return 'van chuyen sang trang rieng: ' + location.hash;
  const name = 'Nội dung hộp thoại $st';
  f.querySelector('[name=name]').value = name;
  f.querySelector('[name=periodId]').value = '$($sample.periodId)';
  const ti = f.querySelector('.teacher-picker input');
  ti.value = '$($sample.teacher)'; ti.dispatchEvent(new Event('input'));
  const sug = await wait(() => [...document.querySelectorAll('.modal .suggest-item:not([disabled])')].find(b => b.textContent.includes('Khảo')));
  if (!sug) return 'khong hien goi y giam khao';
  sug.click();
  if (!(await wait(() => document.querySelector('.modal .chip')?.textContent.includes('Khảo')))) return 'khong them giam khao vao danh sach chon';
  f.requestSubmit();
  if (!(await wait(() => !document.querySelector('.modal')))) return 'hop thoai khong dong: ' + (document.querySelector('.modal .field-error:not(:empty), .modal .alert')?.textContent || '');
  const list = await (await fetch('/api/v1/classes?periodId=$($sample.periodId)&size=100')).json();
  const c = list.items.find(x => x.name === name);
  if (!c) return 'khong tao duoc noi dung';
  const detail = await (await fetch('/api/v1/classes/' + c.id)).json();
  return detail.teachers.some(t => t.fullName.includes('Khảo')) ? 'OK' : 'noi dung chua co giam khao';
})()
"@
    # De nghi dang cho gom theo noi dung kiem tra: chon tat ca cua mot noi dung roi duyet ngay tai trang.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/requests")
    Invoke-Flow 'duyet-hang-loat-theo-noi-dung' @"
(async () => { $waitJs
  const group = await wait(() => [...document.querySelectorAll('.req-group')].find(g => g.querySelector('h2')?.textContent.includes('Phân công UI $st')));
  if (!group) return 'khong gom de nghi theo noi dung';
  const rows = group.querySelectorAll('.req-row').length;
  if (rows < 2) return 'thieu de nghi mau trong nhom: ' + rows;
  group.querySelector('.req-all input').click();
  const bar = document.querySelector('.assign-bar');
  if (bar.hidden || !bar.textContent.includes('Đã chọn ' + rows)) return 'chon tat ca khong chon du: ' + bar.textContent;
  [...bar.querySelectorAll('button')].find(b => b.textContent.includes('Duyệt')).click();
  const f = await wait(() => document.querySelector('.modal form'));
  if (!f) return 'khong mo hop thoai duyet';
  f.requestSubmit();
  const gone = await wait(() => ![...document.querySelectorAll('.req-group h2')].some(h => h.textContent.includes('Phân công UI $st')), 8000);
  if (!gone) return 'nhom van con sau khi duyet: ' + (document.querySelector('.toast')?.textContent || '');
  const list = await (await fetch('/api/v1/classes/$($sample.assignClassId)/participants')).json();
  return [$($sample.approveA), $($sample.approveB)].every(id => list.some(p => p.user.id === id)) ? 'OK' : 'nguoi chua vao noi dung sau khi duyet';
})()
"@
    # Thao tac khac ngay tren dong cua danh sach nguoi dung (khong phai vao chi tiet): cap tai khoan.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/users")
    Invoke-Flow 'thao-tac-khac-tren-dong-nguoi-dung' @"
(async () => { $waitJs
  const code = (await (await fetch('/api/v1/users/$($sample.personA)')).json()).personalCode;
  document.getElementById('u-q').value = code;
  document.querySelector('.filters form').requestSubmit();
  const row = await wait(() => { const r = [...document.querySelectorAll('table.data tbody tr')]; return r.length === 1 && r[0].textContent.includes(code) ? r[0] : null; });
  if (!row) return 'khong tim ra nguoi mau';
  const more = [...row.querySelectorAll('button')].find(b => b.getAttribute('aria-label') === 'Thao tác khác');
  if (!more) return 'khong co nut Thao tac khac tren dong';
  more.click();
  const list = await wait(() => document.querySelector('.modal .more-list'));
  if (!list) return 'khong mo danh sach thao tac';
  const labels = [...list.querySelectorAll('a, button')].map(x => x.textContent.trim());
  for (const need of ['Chuyển đơn vị', 'Đổi quyền', 'Cấp tài khoản', 'Khóa tài khoản', 'Xóa']) if (!labels.includes(need)) return 'thieu thao tac ' + need + ': ' + labels.join(', ');
  [...list.querySelectorAll('button')].find(b => b.textContent.trim() === 'Cấp tài khoản').click();
  const f = await wait(() => document.querySelector('.modal form [name=username]')?.form);
  if (!f) return 'khong mo hop thoai cap tai khoan';
  f.username.value = 'uipa$st'; f.requestSubmit();
  if (!(await wait(() => !document.querySelector('.modal')))) return 'hop thoai cap tai khoan khong dong';
  const u = await (await fetch('/api/v1/users/$($sample.personA)')).json();
  return u.username === 'uipa$st' && u.hasPassword ? 'OK' : 'chua cap duoc tai khoan';
})()
"@
    # Thong ke: loc theo noi dung kiem tra cua dot da chon.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/stats?periodId=$($sample.periodId)")
    Invoke-Flow 'thong-ke-loc-theo-noi-dung' @"
(async () => { $waitJs
  const sel = await wait(() => { const s = document.getElementById('s-class'); return s && !s.disabled && [...s.options].some(o => o.value === '$cid') ? s : null; });
  if (!sel) return 'o chon noi dung kiem tra khong co noi dung cua dot';
  sel.value = '$cid'; sel.dispatchEvent(new Event('change', { bubbles: true }));
  const api = await (await fetch('/api/v1/stats/grade-by-unit?group=top&periodId=$($sample.periodId)&classId=$cid')).json();
  const ok = await wait(() => { const f = document.querySelector('table.data-stats tfoot tr'); return f && Number(f.children[1].textContent.trim().split(/\s/)[0]) === api.totals.total; });
  if (!ok) return 'dong tong khong khop API cua noi dung: ' + api.totals.total;
  document.getElementById('s-period').value = ''; document.getElementById('s-period').dispatchEvent(new Event('change', { bubbles: true }));
  return (await wait(() => document.getElementById('s-class').disabled)) ? 'OK' : 'bo chon dot ma o noi dung van mo';
})()
"@
    # Bang phan cong (ma tran nguoi x noi dung, ARCHITECTURE 3.12): Super Admin xem toan bo danh sach, loc, chon o, ap dung them truc tiep.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/assign?periodId=$($sample.periodId)")
    Invoke-Flow 'phan-cong-tung-don-vi-va-loc' @"
(async () => { $waitJs
  const grid = await wait(() => document.querySelector('table.assign tbody tr'));
  if (!grid) return 'khong co bang phan cong';
  if (!document.querySelector('.assign-note')?.textContent.includes('toàn bộ danh sách')) return 'Super Admin phai duoc bao la xem toan bo danh sach';
  const heads = [...document.querySelectorAll('table.assign thead th.assign-col')];
  if (heads.length < 2) return 'thieu cot noi dung kiem tra: ' + heads.length;
  if (!heads.every(t => t.textContent.includes('Thêm ngay'))) return 'Super Admin phai co che do Them ngay o moi cot';
  if (heads.some(t => t.querySelector('.badge, button'))) return 'tieu de cot chi duoc co ten va ngay (con nhan che do hoac nut Chon het)';
  if ([...document.querySelectorAll('.assign-group .assign-name')].some(t => /\b\d{4}(\.\d+)*\b/.test(t.textContent))) return 'dong don vi van hien ma don vi';
  // Bộ lọc có 7 mục nên có thể xuống hàng: nhóm Đang mở / Tất cả cao bằng ô nhập và thẳng hàng với nút Bỏ lọc.
  const segR = document.querySelector('.filters .segmented[role=radiogroup]').getBoundingClientRect();
  const inR = document.getElementById('as-q').getBoundingClientRect();
  const resetR = [...document.querySelectorAll('.filters button')].find(b => b.textContent === 'Bỏ lọc').getBoundingClientRect();
  if (innerWidth >= 1024 && (Math.abs(segR.bottom - resetR.bottom) > 3 || segR.height > inR.height + 4)) return 'nut Dang mo / Tat ca lech hang voi o loc: ' + Math.round(segR.top) + '-' + Math.round(segR.bottom) + ' / ' + Math.round(resetR.top) + '-' + Math.round(resetR.bottom);
  // Chọn từng đơn vị (2026-11-22): ô chọn đơn vị nằm trong bộ lọc, giữa Đợt kiểm tra và Đối tượng; bảng chỉ một nhóm.
  const sel = document.getElementById('as-unit');
  if (!sel || !sel.classList.contains('multi-trigger') || !sel.closest('.filters')) return 'o chon don vi phai la o chon nhieu trong bo loc';
  const radios = () => [...sel.parentElement.querySelectorAll('input[type=checkbox]')];
  const unitVal = () => document.querySelector('.assign-nav')?.dataset.unitId;
  const before = (a, b) => Boolean(document.getElementById(a).compareDocumentPosition(document.getElementById(b)) & Node.DOCUMENT_POSITION_FOLLOWING);
  if (!before('as-period', 'as-unit') || !before('as-unit', 'as-category')) return 'o don vi phai nam giua Dot kiem tra va Doi tuong';
  // Ô đơn vị rộng như các ô lọc khác, không còn rộng gấp đôi (2026-11-22).
  if (innerWidth >= 1024 && sel.closest('.field').getBoundingClientRect().width > document.getElementById('as-category').closest('.field').getBoundingClientRect().width + 4) return 'o don vi rong hon o loc khac';
  if (document.querySelector('.assign-unitbar')) return 'van con thanh don vi rieng';
  const units = (await (await fetch('/api/v1/assignments/units?periodId=$($sample.periodId)')).json()).filter(u => u.people > 0);
  if (radios().length !== units.length) return 'so don vi trong o chon khong khop API: ' + radios().length + ' / ' + units.length;
  if (radios().some(r => /người/.test(r.closest('label').textContent)) || /người/.test(sel.textContent)) return 'lua chon don vi van ghi so nguoi';
  sel.click();
  const uPanel = await wait(() => sel.parentElement.querySelector('.menu-panel:not([hidden])'));
  if (!uPanel) return 'khong mo khung chon don vi';
  const uSearch = uPanel.querySelector('input[type=search]');
  const lastU = units[units.length - 1];
  uSearch.value = lastU.name.toLowerCase(); uSearch.dispatchEvent(new Event('input'));
  if (!radios().some(r => r.value === String(lastU.id))) return 'tim nhanh khong ra don vi ' + lastU.name;
  if (units.length > 2 && radios().length >= units.length) return 'tim nhanh khong loc danh sach don vi';
  uSearch.value = ''; uSearch.dispatchEvent(new Event('input'));
  sel.click();
  const rowsNow = () => [...document.querySelectorAll('table.assign tbody tr:not(.assign-group)')];
  const groups = () => document.querySelectorAll('table.assign .assign-group').length;
  const hasA = () => rowsNow().some(r => r.textContent.includes('PhânA $($sample.stamp)'));
  const unitA = (await (await fetch('/api/v1/users/$($sample.personA)')).json()).unit.id;
  if (!(await pickChecks('as-unit', [unitA]))) return 'khong chon duoc don vi cua nguoi A';
  if (!(await wait(() => unitVal() === String(unitA) && sel.textContent.includes(units.find(u => u.id === unitA).name)))) return 'chon don vi khong chuyen sang don vi do hoac khong ghi ten: ' + unitVal() + ' ' + sel.textContent;
  const inA = await (await fetch('/api/v1/assignments/matrix?periodId=$($sample.periodId)&unit=' + unitA + '&size=1')).json();
  if (!(await wait(() => groups() === 1 && rowsNow().length === Math.min(inA.total, 200) && hasA()))) return 'chon don vi cua nguoi mau khong hien dung danh sach: ' + rowsNow().length + ' / ' + inA.total;
  // Dòng người chỉ có họ tên; ô chọn nhìn nhỏ bằng biểu tượng chú giải nhưng vùng bấm vẫn đủ 44px.
  const nameCell = rowsNow().find(r => r.textContent.includes('PhânA $($sample.stamp)')).querySelector('.assign-name');
  if (nameCell.querySelector('.cell-sub') || nameCell.textContent.includes('·')) return 'dong nguoi con thong tin di kem: ' + nameCell.textContent;
  const box = document.querySelector('table.assign .assign-cell .assign-box').getBoundingClientRect();
  const sample = document.querySelector('.assign-legend .assign-box').getBoundingClientRect();
  const hit = document.querySelector('table.assign .assign-cell').getBoundingClientRect();
  if (Math.abs(box.width - sample.width) > 1 || Math.abs(box.height - sample.height) > 1) return 'o chon khong bang bieu tuong chu giai: ' + box.width + ' / ' + sample.width;
  if (hit.width < 44 || hit.height < 44) return 'vung bam o chon nho hon 44px: ' + hit.width + 'x' + hit.height;
  // Vừa khít: không khung cuộn, không cuộn ngang; bảng và thanh Trước, Sau gọn trong một màn hình.
  const tbl = document.querySelector('.assign-table');
  if (tbl.scrollWidth > tbl.clientWidth + 1) return 'bang van cuon ngang: ' + tbl.scrollWidth + ' / ' + tbl.clientWidth;
  if (tbl.scrollHeight > tbl.clientHeight + 1) return 'bang van co khung cuon doc';
  const frame = document.querySelector('.assign-frame').getBoundingClientRect();
  const headerH = document.getElementById('app-header').getBoundingClientRect().height;
  if (frame.height > innerHeight - headerH) return 'bang khong vua man hinh: ' + Math.round(frame.height) + ' / ' + Math.round(innerHeight - headerH);
  // Trước, Sau ngay dưới bảng, canh theo mép phải bảng (cột nội dung cuối cùng).
  const navRow = document.querySelector('.assign-nav .btn-row').getBoundingClientRect();
  const tr = tbl.getBoundingClientRect();
  if (navRow.top < tr.bottom || navRow.top - tr.bottom > 24) return 'nut Truoc, Sau khong nam ngay duoi bang';
  if (Math.abs(navRow.right - tr.right) > 2) return 'nut Truoc, Sau khong canh theo cot noi dung cuoi: ' + Math.round(navRow.right) + ' / ' + Math.round(tr.right);
  const navBtn = (re) => [...document.querySelectorAll('.assign-nav button')].find(b => re.test(b.getAttribute('aria-label')));
  const uVals = units.map(u => String(u.id));
  const idx = uVals.indexOf(String(unitA));
  if (idx < uVals.length - 1) {
    // Chọn hai đơn vị: Trước, Sau chỉ đi qua hai đơn vị đó ("Đơn vị 1/2").
    if (!(await pickChecks('as-unit', [unitA, uVals[idx + 1]]))) return 'khong chon duoc hai don vi';
    if (!(await wait(() => unitVal() === String(unitA) && document.querySelector('.assign-nav').getAttribute('aria-label').includes('Đơn vị 1/2')))) return 'chon hai don vi khong giu don vi dang xem hoac khong ghi Don vi 1/2: ' + document.querySelector('.assign-nav').getAttribute('aria-label');
    // Sau: hết trang của đơn vị thì sang đơn vị kế; Trước: quay lại trang cuối của đơn vị trước.
    for (let k = 0; k < 30 && unitVal() === String(unitA); k++) { navBtn(/sau$/).click(); await wait(() => true); }
    if (!(await wait(() => unitVal() === uVals[idx + 1] && groups() <= 1 && !hasA()))) return 'nut Sau khong chuyen sang don vi ke';
    if (navBtn(/sau$/) && navBtn(/trước$/).getAttribute('aria-label') !== 'Đơn vị trước') return 'dau don vi ma nut Truoc khong phai Don vi truoc';
    navBtn(/trước$/).click();
    if (!(await wait(() => unitVal() === String(unitA) && hasA()))) return 'nut Truoc khong quay lai don vi truoc';
  }
  const openMs = async (id) => { document.getElementById(id).click(); return await wait(() => { const p = document.getElementById(id).parentElement.querySelector('.menu-panel:not([hidden])'); return p && p.querySelectorAll('input[type=checkbox]').length ? p : null; }); };
  const spPanel = await openMs('as-specialty');
  if (!spPanel || spPanel.querySelectorAll('input[type=checkbox]').length < 5) return 'o chon chuyen nganh thieu lua chon';
  document.getElementById('as-specialty').click();
  if (![...document.querySelectorAll('[role=radiogroup] [role=radio]')].some(b => b.textContent === 'Đang mở' && b.getAttribute('aria-checked') === 'true')) return 'thieu nut chon Dang mo / Tat ca';
  const page = document.querySelector('.assign-frame').parentElement.getBoundingClientRect();
  if (heads.length <= 3 && document.querySelector('.assign-table').getBoundingClientRect().width > page.width - 2 && innerWidth >= 1024) return 'it noi dung ma bang van gian het chieu ngang';
  const q = document.getElementById('as-q');
  q.value = 'PhânA $($sample.stamp)'; q.dispatchEvent(new Event('input', { bubbles: true }));
  const one = await wait(() => { const r = rowsNow(); return r.length === 1 ? r[0] : null; });
  if (!one) return 'tim ten khong ra dung 1 nguoi';
  const code = (await (await fetch('/api/v1/users/$($sample.personA)')).json()).personalCode;
  q.value = code; q.dispatchEvent(new Event('input', { bubbles: true }));
  const byCode = await wait(() => { const r = rowsNow(); return r.length === 1 && r[0].textContent.includes('PhânA $($sample.stamp)') ? r[0] : null; });
  if (!byCode) return 'tim ma dinh danh khong ra dung 1 nguoi';
  if (byCode.textContent.includes(code)) return 'dong nguoi van hien ma dinh danh';
  // Lọc không ra ai: khối thông báo nêu điều kiện lọc, có nút Bỏ lọc đưa bảng trở lại.
  q.value = 'khong-ai-ten-nay-$($sample.stamp)'; q.dispatchEvent(new Event('input', { bubbles: true }));
  const empty = await wait(() => document.querySelector('.assign-empty'));
  if (!empty || !empty.querySelector('h2')?.textContent.includes('Không tìm thấy người phù hợp')) return 'thieu thong bao khong co ket qua';
  if (!empty.textContent.includes('khong-ai-ten-nay')) return 'thong bao khong neu dieu kien loc';
  const clear = [...empty.querySelectorAll('button')].find(b => b.textContent === 'Bỏ lọc');
  if (!clear) return 'thong bao khong co nut Bo loc';
  clear.click();
  if (!(await wait(() => q.value === '' && hasA()))) return 'Bo loc khong dua bang tro lai';
  const cp = await openMs('as-category');
  if (!cp) return 'thieu o loc doi tuong';
  cp.querySelector('input[value=QNCN]').click(); document.getElementById('as-category').click();
  const qn = await (await fetch('/api/v1/assignments/matrix?periodId=$($sample.periodId)&unit=' + unitA + '&category=QNCN&size=1')).json();
  const okCat = await wait(() => rowsNow().length === qn.total && rowsNow().some(r => r.textContent.includes('PhânB $($sample.stamp)')) && !hasA());
  return okCat ? 'OK' : 'loc doi tuong QNCN khong khop: ' + rowsNow().length + ' / ' + qn.total;
})()
"@
    # Anh bo loc dang mo (co o Don vi) va khoi thong bao khi loc khong ra ai, de xem bang mat o 1366 va 360 px.
    Save-Screenshot 'assign-filters-1366'
    [void](Invoke-Js "(async () => { $waitJs document.getElementById('as-unit').click(); return !!(await wait(() => document.getElementById('as-unit').parentElement.querySelector('.menu-panel:not([hidden])'))); })()")
    Save-Screenshot 'assign-unit-picker-1366'
    [void](Invoke-Js "(() => { document.getElementById('as-unit').click(); return true; })()")
    Invoke-Flow -Width 360 'phan-cong-khong-co-ket-qua' @"
(async () => { $waitJs
  document.querySelector('.filters').open = true;
  const q = document.getElementById('as-q');
  q.value = 'khong-ai-ten-nay'; q.dispatchEvent(new Event('input', { bubbles: true }));
  const empty = await wait(() => document.querySelector('.assign-empty'));
  if (!empty) return 'thieu thong bao khong co ket qua';
  document.querySelector('.filters').open = false;
  window.scrollTo(0, 0);
  return 'OK';
})()
"@
    Save-Screenshot 'assign-empty-360'
    [void](Open-Url "$BaseUrl/index.html#/stats")
    Invoke-Flow 'thong-ke-loc-nhieu-don-vi' @"
(async () => { $waitJs
  const trig = await wait(() => document.getElementById('s-unit'));
  if (!trig || !trig.classList.contains('multi-trigger')) return 'o don vi thong ke phai la o chon nhieu';
  trig.closest('details').open = true;
  if (!trig.textContent.includes('Toàn Lữ đoàn') || trig.classList.contains('is-active')) return 'mac dinh phai la Toan Lu doan: ' + trig.textContent;
  trig.click();
  await wait(() => trig.parentElement.querySelector('.menu-panel:not([hidden]) input[type=checkbox]'));
  const vals = [...trig.parentElement.querySelectorAll('input[type=checkbox]')].map(b => b.value);
  trig.click();
  if (vals.length < 2) return 'khong du don vi de chon';
  // Chọn hai đơn vị thì lọc lại ngay, gọi API kèm unitId=a,b.
  const seen = []; const origFetch = window.fetch;
  window.fetch = (u, o) => { seen.push(decodeURIComponent(String(u))); return origFetch(u, o); };
  if (!(await pickChecks('s-unit', [vals[0], vals[1]]))) return 'khong chon duoc don vi';
  if (!(await wait(() => trig.classList.contains('is-active') && trig.textContent.includes('2 đơn vị')))) return 'chon hai don vi khong cap nhat nut: ' + trig.textContent;
  const refetched = await wait(() => seen.some(u => u.includes('unitId=' + vals[0] + ',' + vals[1])));
  window.fetch = origFetch;
  if (!refetched) return 'chon don vi khong loc lai so lieu (khong goi API voi unitId=a,b)';
  trig.click();
  const sPanel = await wait(() => trig.parentElement.querySelector('.menu-panel:not([hidden])'));
  if (!sPanel) return 'khong mo lai khung';
  // Đơn vị con thụt vào so với đơn vị cấp 1 (".field label" từng đè mất lề), chữ mục không đậm như nhãn ô.
  const opts = [...sPanel.querySelectorAll('label.multi-option')];
  const rootOpt = opts.find(l => /^\d+$/.test(l.querySelector('.cell-sub')?.textContent || ''));
  const childOpt = opts.find(l => (l.querySelector('.cell-sub')?.textContent || '').includes('.'));
  if (rootOpt && childOpt && childOpt.querySelector('input').getBoundingClientRect().left - rootOpt.querySelector('input').getBoundingClientRect().left < 12) return 'don vi con khong thut vao';
  if (rootOpt && Number(getComputedStyle(rootOpt).fontWeight) > 500) return 'chu muc chon dam nhu nhan o';
  return 'OK';
})()
"@
    Save-Screenshot 'stats-unit-picker-1366'
    Invoke-Flow 'thong-ke-bo-chon-don-vi' @"
(async () => { $waitJs
  if (!(await pickChecks('s-unit', []))) return 'khong bo chon duoc don vi';
  const trig = document.getElementById('s-unit');
  return (await wait(() => !trig.classList.contains('is-active') && trig.textContent.includes('Toàn Lữ đoàn'))) ? 'OK' : 'khong tro ve Toan Lu doan';
})()
"@
    # Phan cong bang Excel: tai file mau tu trang, nhap lai dung file vua tai thi khong co thay doi, khong loi.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/assign/import?periodId=$($sample.periodId)")
    Invoke-Flow 'phan-cong-bang-excel-tai-va-kiem' @"
(async () => { $waitJs
  const link = await wait(() => [...document.querySelectorAll('a.btn')].find(a => a.getAttribute('href')?.startsWith('/api/v1/assignments/template')));
  if (!link || !link.getAttribute('href').includes('periodId=$($sample.periodId)')) return 'thieu lien ket tai file mau theo dot';
  if (document.getElementById('ai-period')?.value !== '$($sample.periodId)') return 'o chon dot khong dung dot dang xem';
  const res = await fetch(link.getAttribute('href'));
  if (!res.ok || !(res.headers.get('content-type') || '').includes('spreadsheet')) return 'tai file mau loi: ' + res.status;
  const blob = await res.blob();
  const input = document.querySelector('input[type=file]');
  const dt = new DataTransfer(); dt.items.add(new File([blob], 'phan-cong.xlsx', { type: blob.type }));
  input.files = dt.files; input.dispatchEvent(new Event('change', { bubbles: true }));
  [...document.querySelectorAll('button')].find(b => b.textContent.includes('Kiểm tra file')).click();
  const box = await wait(() => document.querySelector('.import-result .alert-success, .import-result .alert-error'));
  if (!box) return 'khong co ket qua kiem tra';
  // File mẫu vừa tải chưa sửa gì: đọc được, không lỗi, không có thay đổi nào.
  return box.classList.contains('alert-success') && box.textContent.includes('không có lỗi') ? 'OK' : 'file mau nhap lai bi bao loi: ' + box.textContent.slice(0, 200);
})()
"@
    # Cham diem ca dot bang Excel (Super Admin): trang Dot kiem tra co lien ket; tai file mau, nhap lai nguyen file thi khong loi.
    $cPeriod = Invoke-Js "(async () => String((await (await fetch('/api/v1/classes/$cid')).json()).period.id))()"
    [void](Open-Url "$BaseUrl/index.html#/periods")
    Invoke-Flow 'dot-kiem-tra-co-lien-ket-cham-ca-dot' @"
(async () => { $waitJs
  const a = await wait(() => document.querySelector('a[href="#/periods/import-results?periodId=$cPeriod"]'));
  return a ? 'OK' : 'thieu lien ket cham diem ca dot o dong cua dot';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/periods/import-results?periodId=$cPeriod")
    Invoke-Flow 'cham-diem-ca-dot-tai-va-kiem' @"
(async () => { $waitJs
  const link = await wait(() => [...document.querySelectorAll('a.btn')].find(a => a.getAttribute('href') === '/api/v1/periods/$cPeriod/results/import-template'));
  if (!link) return 'thieu lien ket tai file mau cua dot';
  if (document.getElementById('pr-period')?.value !== '$cPeriod') return 'o chon dot khong dung dot dang xem';
  const res = await fetch(link.getAttribute('href'));
  if (!res.ok || !(res.headers.get('content-type') || '').includes('spreadsheet')) return 'tai file mau loi: ' + res.status;
  const blob = await res.blob();
  const input = document.querySelector('input[type=file]');
  const dt = new DataTransfer(); dt.items.add(new File([blob], 'cham-diem.xlsx', { type: blob.type }));
  input.files = dt.files; input.dispatchEvent(new Event('change', { bubbles: true }));
  [...document.querySelectorAll('button')].find(b => b.textContent.includes('Kiểm tra file')).click();
  const box = await wait(() => document.querySelector('.import-result .alert-success, .import-result .alert-error'));
  if (!box) return 'khong co ket qua kiem tra';
  if (document.documentElement.scrollWidth > innerWidth) return 'trang cuon ngang';
  return box.classList.contains('alert-success') && box.textContent.includes('không có lỗi') ? 'OK' : 'file mau nhap lai bi bao loi: ' + box.textContent.slice(0, 200);
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/assign?periodId=$($sample.periodId)")
    Invoke-Flow 'phan-cong-super-admin-them-truc-tiep' @"
(async () => { $waitJs
  const colIdx = await wait(() => { const i = [...document.querySelectorAll('table.assign thead th.assign-col')].findIndex(t => t.textContent.includes('Phân công UI $($sample.stamp)')); return i >= 0 ? i + 1 : null; });
  if (!colIdx) return 'khong thay cot Phan cong UI';
  const unitA = (await (await fetch('/api/v1/users/$($sample.personA)')).json()).unit.id;
  if (!(await pickChecks('as-unit', [unitA]))) return 'khong chon duoc don vi cua nguoi A';
  if (!(await wait(() => [...document.querySelectorAll('table.assign tbody tr:not(.assign-group)')].some(r => r.textContent.includes('PhânA $($sample.stamp)'))))) return 'chon don vi khong hien nguoi A';
  const q = document.getElementById('as-q');
  q.value = 'PhânA $($sample.stamp)'; q.dispatchEvent(new Event('input', { bubbles: true }));
  const row = await wait(() => { const r = [...document.querySelectorAll('table.assign tbody tr:not(.assign-group)')]; return r.length === 1 ? r[0] : null; });
  if (!row) return 'tim ten khong ra dung 1 nguoi';
  const cell = row.children[colIdx].querySelector('button.assign-cell');
  if (!cell || !cell.classList.contains('is-empty')) return 'o khong o trang thai trong';
  cell.click();
  if (!cell.classList.contains('is-add')) return 'o khong chuyen sang chon them';
  const bar = document.querySelector('.assign-bar');
  if (bar.hidden || !bar.textContent.includes('1 thay đổi')) return 'thanh ap dung khong hien: ' + bar.textContent;
  [...bar.querySelectorAll('button')].find(b => b.textContent === 'Áp dụng').click();
  if (!(await wait(() => document.querySelector('.modal-foot')))) return 'khong mo hop thoai xac nhan';
  document.querySelector('.modal-foot .btn:last-child').click();
  const done = await wait(() => document.querySelector('table.assign tbody tr:not(.assign-group) button.assign-cell.is-in'));
  if (!done) return 'o khong chuyen sang Da tham gia: ' + (document.querySelector('.toast')?.textContent || '');
  const list = await (await fetch('/api/v1/classes/$($sample.assignClassId)/participants')).json();
  return list.some(p => p.user.id === $($sample.personA)) ? 'OK' : 'nguoi chua vao noi dung theo API';
})()
"@
    # Font Inter nam trong may chu (khong CDN): da tai du cac tap ky tu, ap dung cho than trang, khong tai tu ngoai.
    [void](Open-Url "$BaseUrl/index.html#/")
    Invoke-Flow 'font-inter-hien-thi-tieng-viet' @"
(async () => {
  await document.fonts.ready;
  const loaded = [...document.fonts].filter(f => f.family.replace(/["']/g, '') === 'Inter' && f.status === 'loaded').length;
  if (loaded < 2) return 'chi tai duoc ' + loaded + ' tap font Inter';
  const ff = getComputedStyle(document.body).fontFamily;
  if (!ff.startsWith('Inter')) return 'font than trang khong phai Inter: ' + ff;
  if (!document.fonts.check('16px Inter', 'Nội dung kiểm tra ế ử ọ')) return 'Inter khong ve duoc chu Viet';
  const outside = performance.getEntriesByType('resource').filter(e => /\.(woff2?|ttf|otf)(\?|$)/.test(e.name) && !e.name.startsWith(location.origin));
  return outside.length ? 'font tai tu ngoai: ' + outside[0].name : 'OK';
})()
"@
    # Thong ke (M6): so tren giao dien khop voi du lieu goc lay tu API khac.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/stats/class/$cid")
    Invoke-Flow 'thong-ke-lop-khop-du-lieu' @"
(async () => { $waitJs
  const card = await wait(() => [...document.querySelectorAll('.stat-grid-kpi .stat-card')].find(c => c.querySelector('.stat-label').textContent === 'Đã có điểm'));
  if (!card) return 'khong thay the Da co diem';
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const people = list.filter(p => p.user.kind === 'PERSON' && !p.removed);
  const tested = people.filter(p => p.result).length;
  const shown = Number(card.querySelector('.stat-value').textContent);
  if (shown !== tested) return 'giao dien ' + shown + ', danh sach lop ' + tested;
  const st = await (await fetch('/api/v1/stats/class/$cid')).json();
  const hist = st.histogram.reduce((a, b) => a + b.count, 0);
  if (hist !== tested) return 'tong phan bo diem ' + hist + ' khac ' + tested;
  const grades = { FAIL: 0, PASS: 0, GOOD: 0, EXCELLENT: 0 };
  people.filter(p => p.result).forEach(p => grades[p.result.grade]++);
  if (grades.FAIL !== st.counts.fail || grades.PASS !== st.counts.pass || grades.GOOD !== st.counts.good || grades.EXCELLENT !== st.counts.excellent)
    return 'xep loai khong khop: ' + JSON.stringify(grades) + ' / ' + JSON.stringify(st.counts);
  const legend = document.querySelector('.chart-donut .chart-legend').textContent;
  if (!['Không đạt', 'Đạt', 'Khá', 'Giỏi'].every(x => legend.includes(x))) return 'chu giai thieu chu xep loai';
  return document.querySelector('.chart-columns svg') ? 'OK' : 'khong co bieu do phan bo diem';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/stats?periodId=$($sample.periodId)")
    Invoke-Flow 'thong-ke-bang-khop-tong' @"
(async () => { $waitJs
  const foot = await wait(() => document.querySelector('table.data-stats tfoot'));
  if (!foot) return 'khong co dong tong';
  const col = (tr, i) => Number(tr.children[i].textContent.trim().split(/\s/)[0]);
  const rows = [...document.querySelectorAll('table.data-stats tbody tr')];
  for (let i = 1; i <= 2; i++) {
    const sum = rows.reduce((a, tr) => a + col(tr, i), 0);
    if (sum !== col(foot.rows[0], i)) return 'cot ' + i + ': tong dong ' + sum + ' khac dong tong ' + col(foot.rows[0], i);
  }
  const kpi = [...document.querySelectorAll('.stat-grid-kpi .stat-card')].find(c => c.querySelector('.stat-label').textContent === 'Tổng số');
  if (Number(kpi.querySelector('.stat-value').textContent) !== col(foot.rows[0], 1)) return 'the Tong so khac dong tong';
  const api = await (await fetch('/api/v1/stats/grade-by-unit?group=top&periodId=$($sample.periodId)')).json();
  if (api.totals.total !== col(foot.rows[0], 1)) return 'dong tong khac API';
  const unitBtn = [...document.querySelectorAll('.segmented button')].find(b => b.textContent === 'Tập thể đơn vị');
  unitBtn.click();
  const ok = await wait(() => document.querySelector('.stats-kind')?.textContent.includes('Điểm tập thể đơn vị'));
  return ok ? 'OK' : 'khong doi sang diem tap the';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/stats?tab=missing&periodId=$($sample.periodId)")
    Invoke-Flow 'thong-ke-chua-kiem-tra' @"
(async () => { $waitJs
  const api = await (await fetch('/api/v1/stats/not-tested?periodId=$($sample.periodId)')).json();
  const ok = await wait(() => document.querySelector('.chart-grid-side .data-table tbody tr'));
  if (!ok) return 'khong co bang';
  const rows = [...document.querySelectorAll('.chart-grid-side .data-table tbody tr:not(.row-message)')].length;
  if (rows !== Math.min(api.total, 20)) return 'bang ' + rows + ' dong, API ' + api.total;
  if ([...document.querySelectorAll('.segmented button')].some(b => b.textContent === 'Điểm theo đơn vị')) return 'van con the Diem theo don vi';
  const btn = [...document.querySelectorAll('.segmented button')].find(b => b.textContent.startsWith('Tất cả ('));
  return btn && btn.textContent.includes('(' + api.total + ')') ? 'OK' : 'nut loc khong ghi dung so: ' + btn?.textContent;
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/me")
    [void](Open-Url "$BaseUrl/index.html#/")
    Invoke-Flow 'dashboard-co-bieu-do' @"
(async () => { $waitJs
  const grid = await wait(() => document.querySelector('.dash-charts'));
  if (!grid) return 'khong co khu bieu do';
  if (grid.querySelectorAll(':scope > .card').length !== 6) return 'khong du 6 the bieu do';
  const pct = (t) => { const p = t.pass + t.good + t.excellent; return t.tested ? (Math.round(p * 1000 / t.tested) / 10) + '%' : '—'; };
  const o = await (await fetch('/api/v1/stats/overview?group=top')).json();
  const center = grid.querySelector('.donut-value').textContent;
  if (center !== pct(o.totals)) return 'giua vong ca nhan ' + center + ' khac ' + pct(o.totals);
  const unitCard = grid.querySelector('.dash-unit-result');
  if (!unitCard || !unitCard.textContent.includes('tập thể đơn vị')) return 'thieu the rieng diem tap the don vi';
  const ou = await (await fetch('/api/v1/stats/overview?kind=UNIT')).json();
  const uc = unitCard.querySelector('.donut-value')?.textContent;
  if (uc !== pct(ou.totals)) return 'giua vong tap the ' + uc + ' khac ' + pct(ou.totals);
  if (grid.querySelector('.chart-aside')) return 'van con dong tap the gop trong the ca nhan';
  if ([...document.querySelectorAll('.card h2')].some(x => x.textContent === 'Thông tin tài khoản')) return 'trang chu van hien thong tin tai khoan';
  return grid.querySelector('.hbar-list') ? 'OK' : 'khong co thanh theo don vi';
})()
"@
    # Nhap Excel (M5): kiem tra bao loi theo dong, tai danh sach loi, ghi that cho nguoi dung, lop, diem.
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/users/import")
    Invoke-Flow -Width 360 'nhap-nguoi-dung-excel-bao-loi' @"
(async () => { $waitJs $setFileJs
  const t = await fetch('/api/v1/users/import-template');
  if (!t.ok || !t.headers.get('content-type').includes('spreadsheetml')) return 'khong tai duoc file mau: ' + t.status;
  await wait(() => document.querySelector('.importer input[type=file]'));
  setFile('$badUsersB64');
  const a = await wait(() => document.querySelector('.import-result .alert'));
  if (!a || !a.classList.contains('alert-error')) return 'khong bao loi: ' + (a?.textContent || '');
  const text = document.querySelector('.import-result').textContent;
  if (!text.includes('Cấp bậc') || !text.includes('Ngày sinh')) return 'thieu loi theo cot: ' + text.slice(0, 200);
  if ([...document.querySelectorAll('.import-result button')].some(b => b.textContent === 'Ghi dữ liệu')) return 'co loi ma van cho ghi';
  const csv = [...document.querySelectorAll('.import-result button')].find(b => b.textContent.includes('CSV'));
  if (!csv) return 'khong co nut tai danh sach loi';
  csv.click();
  const found = await (await fetch('/api/v1/users?q=' + encodeURIComponent('dung $xst'))).json();
  return found.total === 0 ? 'OK' : 'file co loi ma da ghi ' + found.total + ' nguoi';
})()
"@
    Invoke-Flow 'nhap-nguoi-dung-excel-ghi' @"
(async () => { $waitJs $setFileJs
  setFile('$goodUsersB64');
  const ok = await wait(() => document.querySelector('.import-result .alert-success'));
  if (!ok) return 'khong qua buoc kiem tra: ' + (document.querySelector('.import-result')?.textContent || '').slice(0, 200);
  if (document.querySelectorAll('.import-result table.data tbody tr').length !== 2) return 'xem truoc khong du 2 dong';
  [...document.querySelectorAll('.import-result button')].find(b => b.textContent === 'Ghi dữ liệu').click();
  const confirmBtn = await wait(() => document.querySelector('.modal-foot .btn:last-child'));
  if (!confirmBtn) return 'khong co buoc xac nhan ghi';
  confirmBtn.click();
  const done = await wait(() => document.querySelector('.import-result .alert-success')?.textContent.includes('Đã ghi'));
  if (!done) return 'khong bao da ghi: ' + document.querySelector('.import-result')?.textContent.slice(0, 200);
  const found = await (await fetch('/api/v1/users?q=' + encodeURIComponent('nhap $xst'))).json();
  return found.total === 2 ? 'OK' : 'tim thay ' + found.total + ' nguoi sau khi nhap';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/classes/import")
    Invoke-Flow 'nhap-lop-excel' @"
(async () => { $waitJs $setFileJs
  if (document.getElementById('import-period')) return 'van con o chon dot tren giao dien';
  await wait(() => document.querySelector('.importer input[type=file]'));
  setFile('$classesB64');
  const ok = await wait(() => document.querySelector('.import-result .alert'));
  if (!ok?.classList.contains('alert-success')) return 'kiem tra loi: ' + (ok?.textContent || '').slice(0, 200) + ' ' + (document.querySelector('.import-result')?.textContent || '').slice(0, 300);
  [...document.querySelectorAll('.import-result button')].find(b => b.textContent === 'Ghi dữ liệu').click();
  (await wait(() => document.querySelector('.modal-foot .btn:last-child'))).click();
  const done = await wait(() => document.querySelector('.import-result .alert-success')?.textContent.includes('Đã tạo 1 nội dung kiểm tra'));
  if (!done) return 'khong tao duoc lop';
  const list = await (await fetch('/api/v1/classes?periodId=$($sample.periodId)&size=100')).json();
  const c = list.items.find(x => x.name === 'Nội dung nhập Excel $xst');
  return c && c.participantCount === 1 ? 'OK' : 'lop moi khong dung: ' + JSON.stringify(c);
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid/import-results")
    Invoke-Flow -Width 360 'nhap-diem-excel' @"
(async () => { $waitJs $setFileJs
  const t = await fetch('/api/v1/classes/$cid/results/import-template');
  if (!t.ok) return 'khong tai duoc file mau diem: ' + t.status;
  await wait(() => document.querySelector('.importer input[type=file]'));
  setFile('$resultsB64');
  const ok = await wait(() => document.querySelector('.import-result .alert'));
  if (!ok?.classList.contains('alert-success')) return 'kiem tra loi: ' + (document.querySelector('.import-result')?.textContent || '').slice(0, 300);
  [...document.querySelectorAll('.import-result button')].find(b => b.textContent === 'Ghi dữ liệu').click();
  (await wait(() => document.querySelector('.modal-foot .btn:last-child'))).click();
  const done = await wait(() => document.querySelector('.import-result .alert-success')?.textContent.includes('Đã ghi điểm cho 1'));
  if (!done) return 'khong ghi duoc diem';
  const list = await (await fetch('/api/v1/classes/$cid/participants')).json();
  const p = list.find(x => x.user.personalCode === '$($codes.bien)');
  return p?.result?.scoreX10 === 90 && p.result.grade === 'EXCELLENT' ? 'OK' : 'diem sau khi nhap: ' + JSON.stringify(p?.result);
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid")
    Invoke-Flow -Width 360 'xuat-diem-excel' @"
(async () => { $waitJs
  const a = await wait(() => [...document.querySelectorAll('.page-actions a.btn')].find(x => x.textContent === 'Xuất điểm Excel'));
  if (!a) return 'khong co nut xuat diem o trang noi dung';
  if (a.getAttribute('href') !== '/api/v1/classes/$cid/results/export') return 'sai duong dan: ' + a.getAttribute('href');
  const r = await fetch(a.getAttribute('href'));
  if (!r.ok || !r.headers.get('content-type').includes('spreadsheetml')) return 'khong tai duoc file xuat: ' + r.status;
  if (!(r.headers.get('content-disposition') || '').includes('.xlsx')) return 'thieu ten file .xlsx';
  const b = new Uint8Array(await r.arrayBuffer());
  return b[0] === 0x50 && b[1] === 0x4B ? 'OK' : 'file xuat khong phai xlsx';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/me/password")
    $newPw = 'DoiThu@' + (Get-Random -Maximum 99999)
    foreach ($pair in @(@($Password, $newPw), @($newPw, $Password))) {
        $cur = $pair[0].Replace("'", "\'"); $nxt = $pair[1].Replace("'", "\'")
        Invoke-Flow 'doi-mat-khau' @"
(async () => { $waitJs
  document.querySelectorAll('.toast').forEach(t => t.remove());
  const f = document.querySelector('form');
  f.currentPassword.value = '$cur'; f.newPassword.value = '$nxt'; f.confirmPassword.value = '$nxt'; f.requestSubmit();
  const t = await wait(() => document.querySelector('.toast-success'));
  if (!t) return 'khong co thong bao thanh cong: ' + (document.querySelector('.alert, .field-error:not(:empty)')?.textContent || '');
  return f.currentPassword.value === '' ? 'OK' : 'form chua duoc xoa';
})()
"@
    }
    # Bam dang xuat roi moi kiem tra o lan goi sau: trang chuyen sang login.html se huy ngu canh JS dang chay.
    # Nut dang xuat nam trong menu tai khoan: mo menu roi bam.
    Invoke-Flow 'dang-xuat' "(() => { document.querySelector('.user-menu-btn').click(); setTimeout(() => document.querySelector('.header-logout').click(), 0); return 'OK'; })()"
    Start-Sleep -Milliseconds 1500
    Invoke-Flow 've-trang-dang-nhap' @"
(async () => { $waitJs
  return (await wait(() => location.pathname.endsWith('login.html') && document.querySelector('form'))) ? 'OK' : 'khong ve trang dang nhap: ' + location.pathname;
})()
"@
    Invoke-Flow 'sau-dang-xuat-bi-chan' @"
(async () => { const r = await fetch('/api/v1/me'); return r.status === 401 ? 'OK' : 'status ' + r.status; })()
"@

    # 6. Admin mau (cung don vi voi nguoi mau): xem lop trong nhanh, trang tao de nghi, gui de nghi go qua giao dien.
    Set-Width 1366
    [void](Open-Url "$BaseUrl/login.html")
    $adm = $sample.admin
    [void](Invoke-Js "(() => { const f = document.querySelector('form'); f.username.value = '$adm'; f.password.value = 'MatKhau@123'; f.requestSubmit(); return true; })()")
    $ok = $false
    for ($i = 0; $i -lt 50; $i++) {
        Start-Sleep -Milliseconds 200
        if (Invoke-Js "location.pathname.endsWith('index.html') && !!document.querySelector('.app-header .brand')") { $ok = $true; break }
    }
    if (-not $ok) { throw 'Admin mau khong dang nhap duoc' }
    [void](Get-Problems)
    $adminPages = [ordered]@{ 'admin-class-detail' = "#/classes/$cid"; 'admin-request-new' = "#/requests/new?classId=$cid"; 'admin-requests' = '#/requests'; 'admin-me' = '#/me'; 'admin-assign' = "#/assign?periodId=$($sample.periodId)" }
    foreach ($entry in $adminPages.GetEnumerator()) {
        foreach ($w in $Widths) { Check $entry.Key $w ([scriptblock]::Create("Open-Url '$BaseUrl/index.html$($entry.Value)'")) }
    }
    [void](Open-Url "$BaseUrl/index.html#/requests/new?classId=$cid")
    Invoke-Flow -Width 360 'admin-tao-de-nghi-them-don-vi' @"
(async () => { $waitJs
  const seg = await wait(() => [...document.querySelectorAll('.segmented button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Cả đơn vị'));
  if (!seg) return 'khong co lua chon Ca don vi';
  seg.click();
  const sel = await wait(() => document.getElementById('rq-unit'));
  if (!sel) return 'khong co o chon don vi';
  if (!sel.classList.contains('multi-trigger')) return 'o chon don vi phai la o chon nhieu';
  sel.click();
  const opt = await wait(() => sel.parentElement.querySelector('.menu-panel:not([hidden]) input[type=checkbox]'));
  sel.click();
  if (!opt) return 'khong co don vi nao trong pham vi';
  if (!(await pickChecks('rq-unit', [opt.value]))) return 'khong chon duoc don vi';
  sel.closest('form').requestSubmit();
  const ok1 = await wait(() => document.querySelector('.modal-foot'));
  if (!ok1) return 'khong mo hop thoai xac nhan';
  document.querySelector('.modal-foot .btn:last-child').click();
  const ok = await wait(() => /#\/requests\/\d+$/.test(location.hash) && document.querySelector('h1')?.textContent === 'Đề nghị thêm người học');
  return ok ? 'OK' : 'khong sang trang de nghi: ' + location.hash + ' ' + (document.querySelector('.toast')?.textContent || '');
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/")
    [void](Open-Url "$BaseUrl/index.html#/assign?periodId=$($sample.periodId)")
    Invoke-Flow 'admin-phan-cong-chi-thay-nhanh-va-gui-de-nghi' @"
(async () => { $waitJs
  if (!(await wait(() => document.querySelector('table.assign tbody tr')))) return 'khong co bang phan cong';
  const note = document.querySelector('.assign-note')?.textContent || '';
  if (!note.includes('chỉ xem được người thuộc')) return 'Admin phai duoc bao chi xem nhanh cua minh: ' + note;
  const me = await (await fetch('/api/v1/me')).json();
  const root = me.unit.code;
  const tree = await (await fetch('/api/v1/units/tree')).json();
  const mine = new Set(tree.filter(u => u.code === root || u.code.startsWith(root + '.')).map(u => u.name));
  const names = [...document.querySelectorAll('table.assign .assign-group .assign-unit')].map(e => e.textContent);
  if (names.length === 0 || names.some(n => !mine.has(n))) return 'Admin thay don vi ngoai nhanh ' + root + ': ' + names.join(',');
  const branchIds = new Set(tree.filter(u => u.code === root || u.code.startsWith(root + '.')).map(u => String(u.id)));
  document.getElementById('as-unit').click();
  await wait(() => document.getElementById('as-unit').parentElement.querySelector('.menu-panel:not([hidden]) input[type=checkbox]'));
  const uRadios = [...document.getElementById('as-unit').parentElement.querySelectorAll('input[type=checkbox]')];
  document.getElementById('as-unit').click();
  if (!uRadios.length || uRadios.some(r => !branchIds.has(r.value))) return 'o chon don vi co don vi ngoai nhanh';
  const unitB = (await (await fetch('/api/v1/users/$($sample.personB)')).json()).unit.id;
  if (!(await pickChecks('as-unit', [unitB]))) return 'khong chon duoc don vi cua nguoi B';
  if (!(await wait(() => [...document.querySelectorAll('table.assign tbody tr:not(.assign-group)')].some(r => r.textContent.includes('PhânB $($sample.stamp)'))))) return 'chon don vi khong hien nguoi B';
  const heads = [...document.querySelectorAll('table.assign thead th.assign-col')];
  const colIdx = heads.findIndex(t => t.textContent.includes('Phân công UI $($sample.stamp)')) + 1;
  if (colIdx < 1) return 'khong thay cot Phan cong UI';
  if (!heads[colIdx - 1].textContent.includes('Gửi đề nghị')) return 'Admin phai co che do Gui de nghi: ' + heads[colIdx - 1].textContent;
  const q = document.getElementById('as-q');
  q.value = 'PhânB $($sample.stamp)'; q.dispatchEvent(new Event('input', { bubbles: true }));
  const row = await wait(() => { const r = [...document.querySelectorAll('table.assign tbody tr:not(.assign-group)')]; return r.length === 1 ? r[0] : null; });
  if (!row) return 'tim ten khong ra dung 1 nguoi';
  const cell = row.children[colIdx].querySelector('button.assign-cell');
  cell.click();
  const bar = document.querySelector('.assign-bar');
  const apply = [...bar.querySelectorAll('button')].find(b => b.textContent === 'Gửi đề nghị');
  if (!apply) return 'nut Gui de nghi khong hien: ' + bar.textContent;
  apply.click();
  if (!(await wait(() => document.querySelector('.modal-foot')))) return 'khong mo hop thoai xac nhan';
  document.querySelector('.modal-foot .btn:last-child').click();
  const pending = await wait(() => document.querySelector('table.assign tbody tr:not(.assign-group) button.assign-cell.is-pending'));
  if (!pending) return 'o khong chuyen sang Cho duyet: ' + (document.querySelector('.toast')?.textContent || '');
  const reqs = await (await fetch('/api/v1/requests?status=PENDING&size=100')).json();
  const joined = await (await fetch('/api/v1/classes/$($sample.assignClassId)/participants')).json();
  if (joined.some(p => p.user.id === $($sample.personB))) return 'Admin khong duoc them thang vao noi dung';
  return reqs.items.some(r => r.target.id === $($sample.personB) && r.type === 'ADD') ? 'OK' : 'khong co de nghi cho nguoi B';
})()
"@
    [void](Open-Url "$BaseUrl/index.html#/classes/$cid")
    Invoke-Flow 'admin-de-nghi-go-qua-giao-dien' @"
(async () => { $waitJs
  const row = await wait(() => [...document.querySelectorAll('table.data tbody tr')].find(tr => [...tr.querySelectorAll('button')].some(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Đề nghị gỡ')));
  if (!row) return 'Admin khong thay nguoi hoc trong nhanh hoac khong co nut De nghi go';
  if (document.querySelector('.page-actions')?.textContent.includes('Thêm người')) return 'Admin khong duoc thay nut them truc tiep';
  if (document.querySelector('.page-actions')?.textContent.includes('Chấm điểm')) return 'Admin khong duoc thay nut cham diem';
  if (![...document.querySelectorAll('table.data tbody tr')].some(tr => tr.textContent.includes('6.6') && tr.textContent.includes('Khá'))) return 'Admin khong thay diem nguoi hoc trong nhanh';
  [...row.querySelectorAll('button')].find(x => (x.textContent.trim() || x.getAttribute('aria-label')) === 'Đề nghị gỡ').click();
  const ok1 = await wait(() => document.querySelector('.modal-foot'));
  if (!ok1) return 'khong mo hop thoai xac nhan';
  document.querySelector('.modal-foot .btn:last-child').click();
  const ok = await wait(() => /#\/requests\/\d+$/.test(location.hash) && document.querySelector('h1')?.textContent === 'Đề nghị gỡ người học'
    && document.querySelector('.page-sub .badge')?.textContent === 'Đang chờ');
  return ok ? 'OK' : 'khong sang trang de nghi vua tao: ' + location.hash;
})()
"@
}
finally {
    try { [void](Invoke-Cdp 'Browser.close') } catch { }
    Start-Sleep -Milliseconds 500
    if (-not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item $profileDir -Recurse -Force -ErrorAction SilentlyContinue
}

$results | Format-Table -AutoSize -Wrap | Out-String -Width 220 | Write-Host
$report = Join-Path $OutDir 'report.txt'
$results | Format-Table -AutoSize -Wrap | Out-String -Width 400 | Set-Content $report -Encoding UTF8
$fail = @($results | Where-Object { $_.KetQua -eq 'LOI' }).Count
Write-Host ("Tong: {0} lan kiem tra, {1} loi. Anh chup va bao cao: {2}" -f $results.Count, $fail, $OutDir)
if ($script:ignoredLogs -gt 0) {
    Write-Host ("Da bo qua {0} loi console co ten host trong -IgnoreHosts (phan mem diet virus chen vao trang)." -f $script:ignoredLogs) -ForegroundColor Yellow
}
if ($script:ignored -gt 0) {
    Write-Host ("Da bo qua {0} request toi host trong -IgnoreHosts ({1}), khong phai do ung dung goi." -f $script:ignored, ($IgnoreHosts -join ', ')) -ForegroundColor Yellow
}
if ($fail -gt 0) { exit 1 }
