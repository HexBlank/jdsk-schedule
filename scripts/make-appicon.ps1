# 生成应用图标：白底海报图 + Android 启动器图标（自适应前景 PNG + 旧版方形/圆形 PNG）
# 用法:   powershell -ExecutionPolicy Bypass -File scripts\make-appicon.ps1
# 输入:   design\appicon-src.png（透明底方形原图，建议 >=1024）
# 输出:   design\几点上课-白底1024.png（白底海报图）
#         app\src\main\res\mipmap-*\ic_launcher_foreground.png（自适应图标前景，5 个密度）
#         app\src\main\res\mipmap-*\ic_launcher.png / ic_launcher_round.png（API 24/25 旧版兜底）
# 说明:   自适应图标背景色为白色（colors.xml 的 ic_launcher_background）。
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $PSScriptRoot
$srcPath = Join-Path $root 'design\appicon-src.png'
$outDir = Join-Path $root 'design'

if (-not (Test-Path $srcPath)) { throw "找不到源图: $srcPath" }
$src = [System.Drawing.Image]::FromFile($srcPath)

function New-Canvas([int]$size) {
    $bmp = New-Object System.Drawing.Bitmap -ArgumentList $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.PixelOffsetMode = 'HighQuality'
    $g.CompositingQuality = 'HighQuality'
    return ,@($bmp, $g)
}

function Draw-Logo($g, [int]$size, [double]$scale) {
    # logo 等比居中缩放；scale=1.0 时保留源图自带留白
    $d = [int][Math]::Round($size * $scale)
    $off = [int](($size - $d) / 2.0)
    $g.DrawImage($src, $off, $off, $d, $d)
}

function Save-Png($bmp, [string]$path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host ("  -> " + $path)
}

try {
    Write-Host "源图: $($src.Width)x$($src.Height) $srcPath"

    # 1) 白底海报图（1024x1024，缩至 90% 增加四周留白）
    $pair = New-Canvas 1024
    $pair[1].Clear([System.Drawing.Color]::White)
    Draw-Logo $pair[1] 1024 0.90
    $pair[1].Dispose()
    Save-Png $pair[0] (Join-Path $outDir '几点上课-白底1024.png')

    # 2) 自适应图标前景：画布 108dp，logo 占 72%（内容约 52%），
    #    给国产启动器“放大填满圆角矩形”的渲染方式留出舒服的空白
    $adaptive = @{ 'mdpi' = 108; 'hdpi' = 162; 'xhdpi' = 216; 'xxhdpi' = 324; 'xxxhdpi' = 432 }
    foreach ($k in $adaptive.Keys) {
        $size = $adaptive[$k]
        $dir = Join-Path $root ("app\src\main\res\mipmap-" + $k)
        New-Item -ItemType Directory -Force $dir | Out-Null
        $pair = New-Canvas $size
        Draw-Logo $pair[1] $size 0.72
        $pair[1].Dispose()
        Save-Png $pair[0] (Join-Path $dir 'ic_launcher_foreground.png')
    }

    # 3) 旧版（API 24/25，minSdk=24）：白色圆角矩形 + 居中 logo
    # 4) 旧版圆形：白色正圆 + 居中 logo
    $legacy = @{ 'mdpi' = 48; 'hdpi' = 72; 'xhdpi' = 96; 'xxhdpi' = 144; 'xxxhdpi' = 192 }
    foreach ($k in $legacy.Keys) {
        $size = $legacy[$k]
        $dir = Join-Path $root ("app\src\main\res\mipmap-" + $k)
        New-Item -ItemType Directory -Force $dir | Out-Null

        $pair = New-Canvas $size
        $r = $size * 0.22
        $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
        $gp.AddArc(0, 0, 2 * $r, 2 * $r, 180, 90)
        $gp.AddArc($size - 2 * $r, 0, 2 * $r, 2 * $r, 270, 90)
        $gp.AddArc($size - 2 * $r, $size - 2 * $r, 2 * $r, 2 * $r, 0, 90)
        $gp.AddArc(0, $size - 2 * $r, 2 * $r, 2 * $r, 90, 90)
        $gp.CloseFigure()
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
        $pair[1].FillPath($brush, $gp)
        $gp.Dispose(); $brush.Dispose()
        Draw-Logo $pair[1] $size 0.82
        $pair[1].Dispose()
        Save-Png $pair[0] (Join-Path $dir 'ic_launcher.png')

        $pair = New-Canvas $size
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
        $pair[1].FillEllipse($brush, 0, 0, $size, $size)
        $brush.Dispose()
        Draw-Logo $pair[1] $size 0.78
        $pair[1].Dispose()
        Save-Png $pair[0] (Join-Path $dir 'ic_launcher_round.png')
    }
}
finally {
    $src.Dispose()
}
Write-Host 'DONE'