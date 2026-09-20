$ErrorActionPreference = "Stop"

$javaHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { "E:\Dev\Android\Android Studio\jbr" }
if (-not (Test-Path -LiteralPath $javaHome)) {
    throw "找不到 JDK（JBR）：$javaHome。请设置 JAVA_HOME 环境变量后重试，不要重复下载 Android 工具。"
}
$env:JAVA_HOME = $javaHome

& .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Push-Location backend
try {
    npm run check
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    npm test
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    # npmmirror 不实现 npm audit API；审计步骤显式使用 npm 官方安全端点。
    npm audit --omit=dev --audit-level=high --registry=https://registry.npmjs.org
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}
