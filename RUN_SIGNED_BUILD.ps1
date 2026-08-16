$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$pwLine = Select-String -Path (Join-Path $root 'signing\KEEP_SAFE_AR.txt') -Pattern 'password:\s*(.+)$'
$env:YARMOUK_SIGNING_PASSWORD = $pwLine.Matches[0].Groups[1].Value.Trim()
& (Join-Path $root 'BUILD_ANDROID.ps1')
