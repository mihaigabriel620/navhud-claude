# ---------------------------------------------------------------------------
#  navhud-setup.ps1 -- put the repo right, build it, prove it, commit it.
#
#  Put this file in the repo root (next to android/ and arduino/) and run:
#
#      powershell -ExecutionPolicy Bypass -File .\navhud-setup.ps1
#
#  The -ExecutionPolicy Bypass is not optional: Windows refuses to run .ps1
#  files by default, and the error it gives you looks like the script is broken
#  rather than blocked.
#
#  It is safe to run more than once. Nothing is deleted that git does not
#  already have, and every step reports PASS or FAIL at the end instead of
#  stopping at the first problem.
# ---------------------------------------------------------------------------

$ErrorActionPreference = 'Continue'
$ProgressPreference    = 'SilentlyContinue'

$root = if ($PSScriptRoot) { $PSScriptRoot } else { (Get-Location).Path }
Set-Location $root

$results = [ordered]@{}
function Step($name, $ok, $note = '') { $results[$name] = @($ok, $note) }
function Say($msg, $colour = 'Gray') { Write-Host $msg -ForegroundColor $colour }

Say ""
Say "NavHUD setup  --  $root" Cyan
Say ("=" * 70) DarkGray

# ---- 1. is this actually the repo -----------------------------------------
if (-not (Test-Path "$root\android\app\build.gradle.kts")) {
    Say "This is not the NavHUD repo root." Red
    Say "Put this script next to the android\ and arduino\ folders and run it there." Yellow
    exit 1
}
Step "repo root found" $true

# ---- 2. tools --------------------------------------------------------------
$javaOk = $false
try {
    $jv = (& java -version 2>&1 | Select-Object -First 1) -join ''
    if ($jv -match '"(\d+)') { $javaOk = [int]$Matches[1] -ge 17 }
    Step "Java 17 or newer" $javaOk $jv
} catch { Step "Java 17 or newer" $false "java not found" }

$gitOk = $null -ne (Get-Command git -ErrorAction SilentlyContinue)
Step "git installed" $gitOk

$acli = Get-Command arduino-cli -ErrorAction SilentlyContinue
Step "arduino-cli installed" ($null -ne $acli) $(if ($acli) { "" } else { "optional - only needed to flash the board" })

# ---- 3. the SDK and local.properties ---------------------------------------
$sdk = $null
foreach ($c in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "C:\Android",
                 "$env:LOCALAPPDATA\Android\Sdk")) {
    if ($c -and (Test-Path "$c\platforms")) { $sdk = $c; break }
}
if ($sdk) {
    $sdkFwd = $sdk -replace '\\','/'
    "sdk.dir=$sdkFwd" | Out-File -Encoding ascii "$root\android\local.properties"
    $has34 = Test-Path "$sdk\platforms\android-34"
    Step "Android SDK" $true $sdk
    Step "API 34 installed" $has34 $(if ($has34) { "" } else { "run: $sdk\bin\sdkmanager.bat --sdk_root=$sdk `"platforms;android-34`" `"build-tools;34.0.0`"" })
} else {
    Step "Android SDK" $false "not found - looked in ANDROID_HOME, C:\Android, LOCALAPPDATA"
    Step "API 34 installed" $false "no SDK"
}

# ---- 4. replace the stale firmware -----------------------------------------
# The source zip carried an arduino/ folder from before the firmware rewrite:
# hud_imu.h, hud_mag.h, hud_probe.h and friends, none of which exist any more.
$stale = Test-Path "$root\arduino\NavHud\hud_probe.h"
$fw = Get-ChildItem -Path "$env:USERPROFILE\Downloads","$env:USERPROFILE\Desktop",$root `
        -Filter "NavHud-firmware-2.7.zip" -File -ErrorAction SilentlyContinue |
      Select-Object -First 1

if (-not $stale) {
    Step "firmware is current" $true "already 2.7"
} elseif (-not $fw) {
    Step "firmware is current" $false "NavHud-firmware-2.7.zip not found in Downloads, Desktop or the repo"
} else {
    try {
        $tmp = Join-Path $env:TEMP "navhud-fw27"
        if (Test-Path $tmp) { Remove-Item -Recurse -Force $tmp }
        Expand-Archive -Path $fw.FullName -DestinationPath $tmp -Force

        Remove-Item -Recurse -Force "$root\arduino\NavHud"
        Copy-Item -Recurse "$tmp\NavHud" "$root\arduino\NavHud"
        Copy-Item -Force "$tmp\User_Setup.h" "$root\arduino\config\User_Setup.h"
        if (Test-Path "$tmp\CHANGELOG.md") { Copy-Item -Force "$tmp\CHANGELOG.md" "$root\CHANGELOG.md" }
        if (Test-Path "$tmp\wiring.html")  { Copy-Item -Force "$tmp\wiring.html"  "$root\wiring.html" }
        # the pre-2.5 TFT_eSPI config: it still has TFT_CS 15, which is the
        # MCP2515's chip select now. Following it wires the two onto each other.
        Remove-Item -Force "$root\arduino\config\User_Setup_ESP8266.h" -ErrorAction SilentlyContinue

        $good = (Test-Path "$root\arduino\NavHud\hud_pins.h") -and
                (Test-Path "$root\arduino\NavHud\hud_compass.h") -and
                -not (Test-Path "$root\arduino\NavHud\hud_probe.h")
        Step "firmware replaced with 2.7" $good $(if ($good) { "" } else { "the copy did not look right" })
    } catch { Step "firmware replaced with 2.7" $false $_.Exception.Message }
}

# ---- 5. the pin map, checked rather than assumed ---------------------------
$pins = "$root\arduino\NavHud\hud_pins.h"
if (Test-Path $pins) {
    $p = Get-Content $pins -Raw
    $pinsOk = ($p -match 'PIN_CAN_CS\s+15') -and ($p -match 'PIN_TFT_CS\s+4') -and ($p -match 'PIN_BACKLIGHT\s+16')
    Step "pin map is the post-2.5 one" $pinsOk "CAN CS D8, display CS D2, backlight D0"
} else { Step "pin map is the post-2.5 one" $false "hud_pins.h missing" }

$us = "$root\arduino\config\User_Setup.h"
if (Test-Path $us) {
    $u = Get-Content $us -Raw
    Step "TFT_eSPI config matches" (($u -match 'TFT_CS\s+4') -and ($u -match 'TFT_MISO\s+-1')) "TFT_CS 4, TFT_MISO -1"
} else { Step "TFT_eSPI config matches" $false "User_Setup.h missing" }

# ---- 6. the wrapper --------------------------------------------------------
if (-not (Test-Path "$root\android\gradlew.bat")) {
    $g = Get-Command gradle -ErrorAction SilentlyContinue
    if (-not $g -and (Test-Path "C:\Gradle\gradle-8.9\bin\gradle.bat")) { $g = "C:\Gradle\gradle-8.9\bin\gradle.bat" }
    if ($g) {
        Push-Location "$root\android"
        & $(if ($g -is [string]) { $g } else { $g.Source }) wrapper --gradle-version 8.9 | Out-Null
        Pop-Location
    }
}
Step "gradle wrapper present" (Test-Path "$root\android\gradlew.bat")

# ---- 7. build and test -----------------------------------------------------
$testsOk = $false; $testNote = "skipped"
if ((Test-Path "$root\android\gradlew.bat") -and $sdk) {
    Say ""
    Say "Running the unit tests. First run downloads a lot - be patient." Yellow
    Push-Location "$root\android"
    & .\gradlew.bat --console=plain :app:testDebugUnitTest 2>&1 | Tee-Object -Variable out | Out-Null
    Pop-Location
    $testsOk = ($out -join "`n") -match 'BUILD SUCCESSFUL'
    # count them out of the XML the test task leaves behind
    $xmlDir = "$root\android\app\build\test-results\testDebugUnitTest"
    if (Test-Path $xmlDir) {
        $t = 0; $f = 0
        Get-ChildItem $xmlDir -Filter *.xml | ForEach-Object {
            # -TotalCount and -Raw are mutually exclusive; join the lines instead.
            $h = (Get-Content $_.FullName -TotalCount 5) -join "`n"
            if ($h -match 'tests="(\d+)"')    { $t += [int]$Matches[1] }
            if ($h -match 'failures="(\d+)"') { $f += [int]$Matches[1] }
            if ($h -match 'errors="(\d+)"')   { $f += [int]$Matches[1] }
        }
        $testNote = "$t tests, $f failures"
        $testsOk = $testsOk -and ($f -eq 0)
    } else { $testNote = "no results written" }
}
Step "unit tests pass" $testsOk $testNote

$apkOk = $false; $apkNote = "skipped"
if ($testsOk) {
    Say "Building the signed APK..." Yellow
    Push-Location "$root\android"
    & .\gradlew.bat --console=plain :app:assembleRelease 2>&1 | Out-Null
    Pop-Location
    $apk = "$root\android\app\build\outputs\apk\release\app-release.apk"
    $apkOk = Test-Path $apk
    if ($apkOk) { $apkNote = "{0:N1} MB  ->  $apk" -f ((Get-Item $apk).Length / 1MB) }
}
Step "signed APK built" $apkOk $apkNote

# ---- 8. the firmware compiles, if arduino-cli is here ----------------------
if ($acli) {
    $fwOut = & arduino-cli compile --fqbn esp8266:esp8266:d1_mini "$root\arduino\NavHud" 2>&1
    $fwOk = $LASTEXITCODE -eq 0
    Step "firmware compiles" $fwOk $(if ($fwOk) { "" } else { "run it by hand to see why" })
} else {
    Step "firmware compiles" $null "arduino-cli not installed - skipped"
}

# ---- 9. commit -------------------------------------------------------------
if ($gitOk) {
    if (-not (Test-Path "$root\.git")) { & git init | Out-Null }
    if (-not (Test-Path "$root\.gitignore")) {
        "build/`n.gradle/`nlocal.properties`n*.jks`n.idea/`n" |
            Out-File -Encoding ascii "$root\.gitignore"
    }
    & git add -A 2>&1 | Out-Null
    $changed = (& git status --porcelain) -ne $null
    if ($changed) {
        & git commit -m "navhud-setup: firmware 2.7, verified build" 2>&1 | Out-Null
        Step "committed" $true
    } else { Step "committed" $true "nothing to commit - already clean" }
} else { Step "committed" $false "git not installed" }

# ---- the verdict -----------------------------------------------------------
Say ""
Say ("=" * 70) DarkGray
Say "RESULT" Cyan
Say ""
$bad = 0
foreach ($k in $results.Keys) {
    $ok, $note = $results[$k]
    if ($null -eq $ok)   { $tag = "SKIP"; $c = "DarkGray" }
    elseif ($ok)         { $tag = "PASS"; $c = "Green" }
    else                 { $tag = "FAIL"; $c = "Red"; $bad++ }
    Write-Host ("  [{0}] {1,-32} {2}" -f $tag, $k, $note) -ForegroundColor $c
}
Say ""
if ($bad -eq 0) {
    Say "Everything checks out. Next: gh auth login, then" Green
    Say "  gh repo create navhud --private --source=. --push" Green
} else {
    Say "$bad problem(s) above. Paste this whole output and I will sort it." Yellow
}
Say ""