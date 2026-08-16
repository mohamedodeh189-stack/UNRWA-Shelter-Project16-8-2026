$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$SdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$PreferredBuildTools = Join-Path $SdkRoot 'build-tools\36.0.0'
$BuildTools = if (Test-Path -LiteralPath $PreferredBuildTools) { $PreferredBuildTools } else { (Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName }
$PreferredPlatform = Join-Path $SdkRoot 'platforms\android-37.0\android.jar'
$PlatformSource = if (Test-Path -LiteralPath $PreferredPlatform) { $PreferredPlatform } else { (Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'platforms') -Filter 'android.jar' -File -Recurse | Sort-Object FullName -Descending | Select-Object -First 1).FullName }
$JbrHome = if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $env:JAVA_HOME } else { 'C:\Program Files\Android\Android Studio\jbr' }
$JbrBin = Join-Path $JbrHome 'bin'
$BuildDir = Join-Path $ProjectRoot 'build_check'
if (Test-Path -LiteralPath $BuildDir) { Remove-Item -LiteralPath $BuildDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $BuildDir | Out-Null
$Platform = Join-Path $BuildDir 'android.jar'
Copy-Item -LiteralPath $PlatformSource -Destination $Platform -Force
$CompiledRes = Join-Path $BuildDir 'compiled-res.zip'
$GeneratedDir = Join-Path $BuildDir 'generated'
$ClassesDir = Join-Path $BuildDir 'classes'
New-Item -ItemType Directory -Force -Path $GeneratedDir, $ClassesDir | Out-Null
Write-Host 'AAPT2 compile...'
& (Join-Path $BuildTools 'aapt2.exe') compile --dir (Join-Path $ProjectRoot 'app\src\main\res') -o $CompiledRes
if ($LASTEXITCODE -ne 0) { throw 'Resource compilation failed.' }
$UnsignedApk = Join-Path $BuildDir 'stub.apk'
& (Join-Path $BuildTools 'aapt2.exe') link -o $UnsignedApk -I $Platform --manifest (Join-Path $ProjectRoot 'app\src\main\AndroidManifest.xml') -A (Join-Path $ProjectRoot 'app\src\main\assets') --java $GeneratedDir --auto-add-overlay --min-sdk-version 24 --target-sdk-version 36 --version-code 1901 --version-name '19.1' $CompiledRes
if ($LASTEXITCODE -ne 0) { throw 'Resource linking failed.' }
$Sources = @()
$Sources += Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\src\main\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$Sources += Get-ChildItem -LiteralPath $GeneratedDir -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$SourceList = Join-Path $BuildDir 'sources.txt'
$QuotedSources = $Sources | ForEach-Object { '"' + ($_.Replace('\', '/')) + '"' }
[IO.File]::WriteAllLines($SourceList, $QuotedSources, [Text.UTF8Encoding]::new($false))
Write-Host 'JAVAC...'
& (Join-Path $JbrBin 'javac.exe') -encoding UTF-8 -source 17 -target 17 -classpath $Platform -d $ClassesDir "@$SourceList"
if ($LASTEXITCODE -ne 0) { throw 'JAVAC FAILED' }
Write-Host 'COMPILE_CHECK_OK'
