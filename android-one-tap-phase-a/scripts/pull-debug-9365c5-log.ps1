#Requires -Version 5.1
<#
.SYNOPSIS
  Kéo file NDJSON debug phiên Cursor (9365c5) từ thiết bị Android qua adb.

.NOTES
  App ghi song song vào scoped external files và internal filesDir (xem DebugNdjsonLog.kt).

.PARAMETER OutPath
  File đích (mặc định: <repoRoot>\debug-9365c5.log)

.PARAMETER Package
  Application ID (mặc định: vn.phapsumeo.onetap)
#>

param(
    [string] $OutPath = "",
    [string] $Package = "vn.phapsumeo.onetap"
)

$ErrorActionPreference = "Stop"

$adb = Get-Command adb -ErrorAction SilentlyContinue
if (-not $adb) {
    throw "Không tìm thấy adb trong PATH (Android SDK platform-tools)."
}

$adbExe = $adb.Source
$lines = & $adbExe devices
$ready = $lines | Where-Object { $_ -match "`tdevice$" }
if (-not $ready) {
    throw "Không có thiết bị adb trạng thái 'device'. Bật USB debugging."
}

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($OutPath)) {
    $OutPath = Join-Path $repoRoot "debug-9365c5.log"
}

$remoteScoped = "/sdcard/Android/data/$Package/files/debug-9365c5.log"

Write-Host "adb: $adbExe"
Write-Host "remote: $remoteScoped"
Write-Host "out:    $OutPath"

if (Test-Path -LiteralPath $OutPath) {
    Remove-Item -LiteralPath $OutPath -Force
}

# 1) Pull scoped storage (ổn định nhất khi máy cho phép)
$p = Start-Process -FilePath $adbExe -ArgumentList @("pull", $remoteScoped, $OutPath) -Wait -NoNewWindow -PassThru
if ($p.ExitCode -eq 0 -and (Test-Path -LiteralPath $OutPath) -and ((Get-Item -LiteralPath $OutPath).Length -gt 0)) {
    Write-Host "OK — adb pull (scoped)."
    exit 0
}

# 2) Debuggable APK: internal filesDir
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $adbExe
$psi.Arguments = "exec-out run-as $Package cat files/debug-9365c5.log"
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$psi.UseShellExecute = $false
$psi.CreateNoWindow = $true
$proc = [System.Diagnostics.Process]::Start($psi)
$stdout = $proc.StandardOutput.ReadToEnd()
$stderr = $proc.StandardError.ReadToEnd()
$proc.WaitForExit()

if ($proc.ExitCode -eq 0 -and $stdout.Length -gt 0) {
    # UTF-8 text NDJSON
    [System.IO.File]::WriteAllText($OutPath, $stdout, [System.Text.UTF8Encoding]::new($false))
    Write-Host "OK — run-as cat (internal files)."
    exit 0
}

Write-Host "stderr run-as: $stderr"
throw @"
Không lấy được debug-9365c5.log.
- Chạy bubble/chụp một lần để có log.
- Package: $Package
- Thử kiểm tra: adb shell ""ls -la $remoteScoped""
"@
