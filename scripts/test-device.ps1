param(
    [string]$Serial,
    [string]$TestClass,
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdkRoot 'platform-tools\adb.exe'

function Invoke-AdbChecked {
    param([string[]]$AdbArguments)
    & $adb -s $Serial @AdbArguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($AdbArguments -join ' ')" }
}

if (-not $Serial) {
    $devices = @(& $adb devices | Select-String '^([^\s]+)\s+device$' | ForEach-Object { $_.Matches[0].Groups[1].Value })
    if ($devices.Count -ne 1) { throw 'Connect one unlocked phone, or supply -Serial.' }
    $Serial = $devices[0]
}

Push-Location $projectRoot
try {
    if (-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
        if ($LASTEXITCODE -ne 0) { throw 'The test build failed.' }
    }

    # Install directly: connectedAndroidTest's cleanup can uninstall an existing launcher on failure.
    Invoke-AdbChecked -AdbArguments @('install', '-r', 'app/build/outputs/apk/debug/app-debug.apk')
    Invoke-AdbChecked -AdbArguments @('install', '-r', 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')

    $originalStayOn = (& $adb -s $Serial shell settings get global stay_on_while_plugged_in).Trim()
    try {
        Invoke-AdbChecked -AdbArguments @('shell', 'svc', 'power', 'stayon', 'usb')
        Invoke-AdbChecked -AdbArguments @('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        $testArguments = @('shell', 'am', 'instrument', '-w', '-r')
        if ($TestClass) { $testArguments += @('-e', 'class', $TestClass) }
        $testArguments += 'com.gustav.mlauncher.test/androidx.test.runner.AndroidJUnitRunner'
        $testOutput = @(Invoke-AdbChecked -AdbArguments $testArguments)
        $testOutput
        if (($testOutput -join "`n") -notmatch 'OK \(\d+ tests?\)') {
            throw 'Device tests failed or did not finish. The launcher remains installed.'
        }
    } finally {
        if ($originalStayOn -match '^\d+$') {
            Invoke-AdbChecked -AdbArguments @('shell', 'settings', 'put', 'global', 'stay_on_while_plugged_in', $originalStayOn)
        }
        Invoke-AdbChecked -AdbArguments @('shell', 'am', 'start', '-n', 'com.gustav.mlauncher/.MainActivity',
            '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    }
} finally {
    Pop-Location
}
