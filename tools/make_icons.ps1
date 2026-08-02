# Generates every app-icon asset for both clients from one square source PNG.
#
# Uses System.Drawing (built into Windows) rather than ImageMagick/Pillow so
# it needs nothing installed. Aspect ratio is preserved throughout: the source
# is cropped to a centred SQUARE around the badge, then only ever scaled
# square -> square.
#
#   .\make_icons.ps1 -Source <path-to-1024px-png>

param(
    [Parameter(Mandatory = $true)][string]$Source,
    # Fraction of the 108dp adaptive canvas the badge occupies.
    # Android's documented "safe zone" is 0.66, but that guidance assumes a
    # small glyph floating on a background - here the artwork IS a finished
    # rounded-square icon, and at 0.66 One UI (which reveals more of the
    # canvas than the safe zone) rendered it visibly smaller than neighbouring
    # icons, ringed by background colour. Full-bleed makes it read like every
    # other icon on the home screen.
    [double]$ForegroundFill = 1.0
)

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

$repo        = Split-Path -Parent $PSScriptRoot
$androidRes  = Join-Path $repo "application_android\app\src\main\res"
$winRes      = Join-Path $repo "windows_app\resources\icons"

$src = [System.Drawing.Image]::FromFile((Resolve-Path $Source))
Write-Host "source: $($src.Width)x$($src.Height)"

# The artwork sits inside a dark vignette with empty margins. Crop to a
# centred square that frames the badge (including its gold side tabs) so the
# logo is as large as possible - at 48px a full-frame version would be mostly
# background and unreadable.
$side = [int]([Math]::Min($src.Width, $src.Height) * 0.700)
$cx   = [int]($src.Width / 2)
$cy   = [int]($src.Height * 0.491)   # badge sits slightly above centre
$cropX = [int]($cx - $side / 2)
$cropY = [int]($cy - $side / 2)
if ($cropX -lt 0) { $cropX = 0 }
if ($cropY -lt 0) { $cropY = 0 }
if ($cropX + $side -gt $src.Width)  { $cropX = $src.Width  - $side }
if ($cropY + $side -gt $src.Height) { $cropY = $src.Height - $side }

$badge = New-Object System.Drawing.Bitmap($side, $side)
$g = [System.Drawing.Graphics]::FromImage($badge)
$g.DrawImage($src, (New-Object System.Drawing.Rectangle(0, 0, $side, $side)),
             (New-Object System.Drawing.Rectangle($cropX, $cropY, $side, $side)),
             [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$src.Dispose()
Write-Host "cropped badge: ${side}x${side} from ($cropX,$cropY)"

# Square -> square, high quality. Optional padding leaves transparent margin,
# which the adaptive-icon foreground needs so the launcher's mask can't clip
# the badge.
function New-Scaled {
    param([System.Drawing.Bitmap]$Image, [int]$Size, [double]$Fill = 1.0)

    $out = New-Object System.Drawing.Bitmap($Size, $Size)
    $gr  = [System.Drawing.Graphics]::FromImage($out)
    $gr.InterpolationMode  = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $gr.SmoothingMode      = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $gr.PixelOffsetMode    = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $gr.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $gr.Clear([System.Drawing.Color]::Transparent)

    $inner  = [int]($Size * $Fill)
    $offset = [int](($Size - $inner) / 2)
    $gr.DrawImage($Image, $offset, $offset, $inner, $inner)
    $gr.Dispose()
    return $out
}

function Save-Png {
    param([System.Drawing.Bitmap]$Image, [string]$Path)
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
    $Image.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
}

# ==================== Android ====================
# Legacy launcher icons (pre-API-26, and the fallback everywhere else).
$launcherSizes = @{ "mdpi" = 48; "hdpi" = 72; "xhdpi" = 96; "xxhdpi" = 144; "xxxhdpi" = 192 }
foreach ($density in $launcherSizes.Keys) {
    $px  = $launcherSizes[$density]
    $bmp = New-Scaled -Image $badge -Size $px
    Save-Png $bmp (Join-Path $androidRes "mipmap-$density\ic_launcher.png")
    # Round variant: same art. The badge is already a rounded square, so a
    # separately-masked circular version would just crop the gold side tabs.
    Save-Png $bmp (Join-Path $androidRes "mipmap-$density\ic_launcher_round.png")
    $bmp.Dispose()
    Write-Host "android mipmap-$density : ${px}px"
}

# Adaptive-icon foreground (API 26+). The canvas is 108dp but launchers mask
# it to a circle/squircle and may parallax it, so only the centre ~66% is
# guaranteed visible - the badge is drawn at that fraction to stay uncropped.
$fgSizes = @{ "mdpi" = 108; "hdpi" = 162; "xhdpi" = 216; "xxhdpi" = 324; "xxxhdpi" = 432 }
foreach ($density in $fgSizes.Keys) {
    $px  = $fgSizes[$density]
    $bmp = New-Scaled -Image $badge -Size $px -Fill $ForegroundFill
    Save-Png $bmp (Join-Path $androidRes "mipmap-$density\ic_launcher_foreground.png")
    $bmp.Dispose()
}
Write-Host "android adaptive foregrounds written"

# ==================== Windows ====================
# .ico with PNG-compressed entries (supported since Vista). Explorer picks
# whichever size it needs, so ship the full ladder.
$icoSizes = @(16, 32, 48, 64, 128, 256)
$pngBlobs = @()
foreach ($px in $icoSizes) {
    $bmp = New-Scaled -Image $badge -Size $px
    $ms  = New-Object System.IO.MemoryStream
    $bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
    $pngBlobs += ,($ms.ToArray())
    $ms.Dispose(); $bmp.Dispose()
}

if (-not (Test-Path $winRes)) { New-Item -ItemType Directory -Force -Path $winRes | Out-Null }
$icoPath = Join-Path $winRes "app_icon.ico"
$fs = [System.IO.File]::Create($icoPath)
$bw = New-Object System.IO.BinaryWriter($fs)

$bw.Write([UInt16]0)                    # reserved
$bw.Write([UInt16]1)                    # type: 1 = icon
$bw.Write([UInt16]$icoSizes.Count)

# Directory entries are 16 bytes each and precede all image data.
$offset = 6 + (16 * $icoSizes.Count)
for ($i = 0; $i -lt $icoSizes.Count; $i++) {
    $px = $icoSizes[$i]
    # 256 is encoded as 0 in a single byte.
    $dim = if ($px -ge 256) { 0 } else { $px }
    $bw.Write([Byte]$dim)               # width
    $bw.Write([Byte]$dim)               # height
    $bw.Write([Byte]0)                  # palette size (0 = truecolour)
    $bw.Write([Byte]0)                  # reserved
    $bw.Write([UInt16]1)                # colour planes
    $bw.Write([UInt16]32)               # bits per pixel
    $bw.Write([UInt32]$pngBlobs[$i].Length)
    $bw.Write([UInt32]$offset)
    $offset += $pngBlobs[$i].Length
}
foreach ($blob in $pngBlobs) { $bw.Write($blob) }
$bw.Flush(); $bw.Dispose(); $fs.Dispose()
Write-Host "windows ico: $icoPath ($((Get-Item $icoPath).Length) bytes, $($icoSizes.Count) sizes)"

# Runtime window/taskbar icon, loaded through the Qt resource system.
$appPng = New-Scaled -Image $badge -Size 256
Save-Png $appPng (Join-Path $winRes "app_icon.png")
$appPng.Dispose()
Write-Host "windows png: app_icon.png (256px)"

$badge.Dispose()
Write-Host "done."
