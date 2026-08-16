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
$BuildDir = Join-Path $ProjectRoot 'build'
$DistDir = Join-Path $ProjectRoot 'dist'
$SigningDir = Join-Path $ProjectRoot 'signing'
$Keystore = Join-Path $SigningDir 'yarmouk-field-release.jks'
$StorePassword = $env:YARMOUK_SIGNING_PASSWORD
$Alias = 'yarmouk-field'

if ([string]::IsNullOrWhiteSpace($StorePassword)) {
    $SecurePassword = Read-Host 'Release signing password (or set YARMOUK_SIGNING_PASSWORD)' -AsSecureString
    $PasswordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecurePassword)
    try { $StorePassword = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($PasswordPointer) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($PasswordPointer) }
}

foreach ($required in @(
    (Join-Path $BuildTools 'aapt2.exe'),
    (Join-Path $BuildTools 'd8.bat'),
    (Join-Path $BuildTools 'zipalign.exe'),
    (Join-Path $BuildTools 'apksigner.bat'),
    (Join-Path $JbrBin 'javac.exe'),
    (Join-Path $JbrBin 'jar.exe'),
    (Join-Path $JbrBin 'keytool.exe'),
    $PlatformSource
)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing Android build tool: $required" }
}

if (Test-Path -LiteralPath $BuildDir) {
    $resolvedBuild = (Resolve-Path -LiteralPath $BuildDir).Path
    if (-not $resolvedBuild.StartsWith($ProjectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Unsafe build path: $resolvedBuild"
    }
    Remove-Item -LiteralPath $resolvedBuild -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $BuildDir, $DistDir, $SigningDir | Out-Null
$Platform = Join-Path $BuildDir 'android.jar'
Copy-Item -LiteralPath $PlatformSource -Destination $Platform -Force
$env:JAVA_HOME = Split-Path -Parent $JbrBin
$env:Path = $JbrBin + ';' + $env:Path
$CompiledRes = Join-Path $BuildDir 'compiled-res.zip'
$GeneratedDir = Join-Path $BuildDir 'generated'
$ClassesDir = Join-Path $BuildDir 'classes'
$DexDir = Join-Path $BuildDir 'dex'
New-Item -ItemType Directory -Force -Path $GeneratedDir, $ClassesDir, $DexDir | Out-Null

& (Join-Path $BuildTools 'aapt2.exe') compile --dir (Join-Path $ProjectRoot 'app\src\main\res') -o $CompiledRes
if ($LASTEXITCODE -ne 0) { throw 'Android resource compilation failed.' }

$UnsignedApk = Join-Path $BuildDir 'yarmouk-unsigned.apk'
& (Join-Path $BuildTools 'aapt2.exe') link -o $UnsignedApk -I $Platform --manifest (Join-Path $ProjectRoot 'app\src\main\AndroidManifest.xml') -A (Join-Path $ProjectRoot 'app\src\main\assets') --java $GeneratedDir --auto-add-overlay --min-sdk-version 24 --target-sdk-version 36 --version-code 140 --version-name '1.4.0' $CompiledRes
if ($LASTEXITCODE -ne 0) { throw 'Android resource linking failed.' }

$Sources = @()
$Sources += Get-ChildItem -LiteralPath (Join-Path $ProjectRoot 'app\src\main\java') -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$Sources += Get-ChildItem -LiteralPath $GeneratedDir -Filter '*.java' -Recurse | ForEach-Object { $_.FullName }
$SourceList = Join-Path $BuildDir 'sources.txt'
$QuotedSources = $Sources | ForEach-Object { '"' + ($_.Replace('\', '/')) + '"' }
[IO.File]::WriteAllLines($SourceList, $QuotedSources, [Text.UTF8Encoding]::new($false))

$JavacArguments = @('-encoding','UTF-8','-source','17','-target','17','-classpath',$Platform,'-d',$ClassesDir,"@$SourceList")
& (Join-Path $JbrBin 'javac.exe') @JavacArguments
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed.' }

$ClassesJar = Join-Path $BuildDir 'classes.jar'
& (Join-Path $JbrBin 'jar.exe') cf $ClassesJar -C $ClassesDir .
if ($LASTEXITCODE -ne 0) { throw 'Class archive creation failed.' }
& (Join-Path $BuildTools 'd8.bat') --release --min-api 24 --lib $Platform --output $DexDir $ClassesJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }
& (Join-Path $JbrBin 'jar.exe') uf $UnsignedApk -C $DexDir classes.dex
if ($LASTEXITCODE -ne 0) { throw 'Adding classes.dex failed.' }

$AlignedApk = Join-Path $BuildDir 'yarmouk-aligned.apk'
& (Join-Path $BuildTools 'zipalign.exe') -f -p 4 $UnsignedApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }

if (-not (Test-Path -LiteralPath $Keystore)) {
    & (Join-Path $JbrBin 'keytool.exe') -genkeypair -v -keystore $Keystore -storepass $StorePassword -keypass $StorePassword -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Yarmouk Field Project, OU=Field Engineering, O=UNRWA Project Tools, L=Damascus, C=SY'
    if ($LASTEXITCODE -ne 0) { throw 'Release keystore generation failed.' }
}

$FinalApk = Join-Path $DistDir 'Yarmouk_Camp_Field_Android_v1.4.0.apk'
& (Join-Path $BuildTools 'apksigner.bat') sign --ks $Keystore --ks-key-alias $Alias --ks-pass "pass:$StorePassword" --key-pass "pass:$StorePassword" --out $FinalApk $AlignedApk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& (Join-Path $BuildTools 'apksigner.bat') verify --verbose --print-certs $FinalApk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $BuildTools 'aapt2.exe') dump badging $FinalApk
if ($LASTEXITCODE -ne 0) { throw 'APK package inspection failed.' }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$ApkArchive = [IO.Compression.ZipFile]::OpenRead($FinalApk)
try {
    foreach ($requiredEntry in @('classes.dex', 'assets/yarmouk_addresses.json', 'res/drawable-nodpi-v4/unrwa_logo.gif', 'res/raw/yarmouk_camp_map.pdf', 'res/raw/yarmouk_sector_maps.pdf')) {
        if ($null -eq $ApkArchive.GetEntry($requiredEntry)) { throw "Required APK entry is missing: $requiredEntry" }
    }
} finally { $ApkArchive.Dispose() }

$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $FinalApk).Hash
$Info = @"
Yarmouk Camp Field Android
Version: 1.4.0 (140)
Package: org.unrwa.yarmoukfield
Minimum Android: 7.0 (API 24)
Target SDK: 36
SHA-256: $Hash
Built: $([DateTime]::Now.ToString('yyyy-MM-dd HH:mm:ss'))
"@
[IO.File]::WriteAllText((Join-Path $DistDir 'BUILD_INFO.txt'), $Info, [Text.UTF8Encoding]::new($true))
Write-Host "APK_READY::$FinalApk"
Write-Host "SHA256::$Hash"
