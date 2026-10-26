<#
  一次打出两个正式签名的 release 包（发布版 + 自用版），拷到固定目录并逐个自检。

  测试包（默认）：不升版本号，给自己装机看效果。
      .\scripts\build-apks.ps1
    → dist\preview\jidianshangke-<版本>-<提交>.apk、jidianshangke-self-<版本>-<提交>.apk
      每次先清空 dist\preview\ 里的旧 APK，目录里永远只有最新一次的两个包；
      工作区有未提交改动时提交号后面带 -dirty。

  正式包：发版流程的「构建 + 拷贝」一步（升版本号、改 release.json 见 backend\public\app\README.md）。
      .\scripts\build-apks.ps1 -Release
    → backend\public\app\jidianshangke-<版本>.apk、jidianshangke-self-<版本>.apk
      要求：工作区干净（先提交升版本号）、versionCode 大于两份 release.json 里的、目标文件不存在。

  每个包都检查：versionCode / versionName 与 app\build.gradle.kts 一致、签名有效、
  包内只有自己那个 API 域名（防止两个包串了）。先跑一遍 JVM 单测，-SkipTests 可跳过。
  需要 PowerShell 7（pwsh）：脚本是无 BOM 的 UTF-8，Windows PowerShell 5.1 会把中文读乱。
#>
param(
    [switch]$Release,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "E:\Dev\Android\Android Studio\jbr" }
if (-not (Test-Path -LiteralPath $javaHome)) {
    throw "找不到 JDK（JBR）：$javaHome。请设置 JAVA_HOME 环境变量后重试，不要重复下载 Android 工具。"
}
$env:JAVA_HOME = $javaHome

# SDK 路径以 local.properties 为准（见 LOCAL_ENV.md），build-tools 取已装的最高版本
$sdkLine = Get-Content local.properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
if (-not $sdkLine) { throw "local.properties 里没有 sdk.dir，照 LOCAL_ENV.md 配好再来" }
$sdk = ($sdkLine -replace '^sdk\.dir=', '') -replace '\\:', ':' -replace '\\\\', '\'
$buildTools = Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object { [version]$_.Name } | Select-Object -Last 1
if (-not $buildTools) { throw "在 $sdk\build-tools 下找不到 build-tools" }
$aapt = Join-Path $buildTools.FullName "aapt.exe"
$apksigner = Join-Path $buildTools.FullName "apksigner.bat"

$gradleFile = Get-Content app\build.gradle.kts -Raw
$versionCode = [int]([regex]::Match($gradleFile, 'versionCode\s*=\s*(\d+)').Groups[1].Value)
$versionName = [regex]::Match($gradleFile, 'versionName\s*=\s*"([^"]+)"').Groups[1].Value
if (-not $versionCode -or -not $versionName) { throw "没能从 app\build.gradle.kts 读出 versionCode / versionName" }

# 两个版本只差内置 API 域名，见 CLAUDE.md「编译发版规则」
$variants = @(
    @{ Label = "发布版"; Api = "https://kbapi.sixw.de";     Prefix = "jidianshangke";      Json = "backend\public\app\release.json" },
    @{ Label = "自用版"; Api = "https://zsjapi.hexbai.cn"; Prefix = "jidianshangke-self"; Json = "backend\public\app\release-self.json" }
)
$hosts = $variants | ForEach-Object { ([uri]$_.Api).Host }

$dirty = [bool](git status --porcelain)
if ($Release) {
    if ($dirty) { throw "正式包必须从已提交的代码构建：先提交（包括升版本号），工作区干净后再打" }
    foreach ($v in $variants) {
        if (Test-Path $v.Json) {
            $online = (Get-Content $v.Json -Raw | ConvertFrom-Json).versionCode
            if ($versionCode -le $online) {
                throw "versionCode $versionCode 不大于 $($v.Json) 里的 $online：先在 app\build.gradle.kts 升版本号"
            }
        }
    }
    $outDir = "backend\public\app"
    $names = $variants | ForEach-Object { "$($_.Prefix)-$versionName.apk" }
    foreach ($n in $names) {
        if (Test-Path (Join-Path $outDir $n)) { throw "$outDir\$n 已存在：这个版本号打过了，先升版本号" }
    }
} else {
    $sha = (git rev-parse --short HEAD).Trim()
    $tag = if ($dirty) { "$sha-dirty" } else { $sha }
    $outDir = "dist\preview"
    New-Item -ItemType Directory -Force $outDir | Out-Null
    Get-ChildItem $outDir -Filter *.apk | Remove-Item -Force
    $names = $variants | ForEach-Object { "$($_.Prefix)-$versionName-$tag.apk" }
}

if (-not $SkipTests) {
    & .\gradlew.bat :app:testDebugUnitTest --offline -q
    if ($LASTEXITCODE -ne 0) { throw "单测没过，不打包" }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
function Get-DexText([string]$apk) {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk)
    try {
        $builder = [System.Text.StringBuilder]::new()
        foreach ($entry in $zip.Entries | Where-Object { $_.FullName -like "*.dex" }) {
            $stream = $entry.Open()
            $memory = [System.IO.MemoryStream]::new()
            $stream.CopyTo($memory)
            $stream.Dispose()
            [void]$builder.Append([System.Text.Encoding]::ASCII.GetString($memory.ToArray()))
        }
        return $builder.ToString()
    } finally {
        $zip.Dispose()
    }
}

$results = @()
for ($i = 0; $i -lt $variants.Count; $i++) {
    $v = $variants[$i]
    $target = Join-Path $outDir $names[$i]
    Write-Host "== 构建$($v.Label)（$($v.Api)）"
    & .\gradlew.bat clean :app:assembleRelease --offline -q "-PapiBaseUrl=$($v.Api)"
    if ($LASTEXITCODE -ne 0) { throw "$($v.Label) 构建失败" }
    Copy-Item app\build\outputs\apk\release\app-release.apk $target -Force

    $badging = & $aapt dump badging $target | Select-String "^package:" | Select-Object -First 1
    if ("$badging" -notmatch "versionCode='$versionCode'" -or "$badging" -notmatch "versionName='$([regex]::Escape($versionName))'") {
        throw "$target 版本号不对：$badging"
    }
    & $apksigner verify $target
    if ($LASTEXITCODE -ne 0) { throw "$target 签名校验失败" }
    $dex = Get-DexText $target
    $own = ([uri]$v.Api).Host
    if (-not $dex.Contains($own)) { throw "$target 里没有找到 $own" }
    foreach ($other in $hosts | Where-Object { $_ -ne $own }) {
        if ($dex.Contains($other)) { throw "$target 里混进了另一个版本的域名 $other" }
    }
    $results += [pscustomobject]@{
        版本 = $v.Label
        文件 = $target
        大小KB = [int][math]::Round((Get-Item $target).Length / 1KB)
        域名 = $own
    }
}

Write-Host ""
Write-Host "versionCode $versionCode · versionName $versionName · 签名、版本号、域名自检均通过"
$results | Format-Table -AutoSize
if ($Release) {
    Write-Host "下一步：改两份 release.json（fileName 分别指向上面两个文件），见 backend\public\app\README.md"
}
