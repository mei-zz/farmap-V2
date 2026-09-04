Add-Type -AssemblyName System.Drawing

$bitmap = New-Object System.Drawing.Bitmap 768, 512
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias

$sky = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Rectangle 0, 0, 768, 340),
    ([System.Drawing.Color]::FromArgb(125, 196, 245)),
    ([System.Drawing.Color]::FromArgb(232, 246, 255)),
    90
)
$graphics.FillRectangle($sky, 0, 0, 768, 340)
$graphics.FillRectangle((New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(94, 164, 70))), 0, 340, 768, 172)

$trunk = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(105, 65, 38))
$graphics.FillPolygon($trunk, @(
    (New-Object System.Drawing.Point 340, 420),
    (New-Object System.Drawing.Point 425, 420),
    (New-Object System.Drawing.Point 407, 220),
    (New-Object System.Drawing.Point 363, 220)
))

$leafBrushes = @(
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(42, 122, 55))),
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(61, 150, 66))),
    (New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(83, 169, 75)))
)
$canopy = @(
    @(235, 135, 230, 190, 0), @(345, 95, 230, 200, 1), @(455, 135, 190, 185, 0),
    @(280, 210, 220, 170, 2), @(410, 205, 220, 175, 1), @(180, 220, 180, 145, 1)
)
foreach ($part in $canopy) {
    $graphics.FillEllipse($leafBrushes[$part[4]], $part[0], $part[1], $part[2], $part[3])
}

$apple = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(205, 43, 40))
$highlight = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(248, 116, 92))
$apples = @(@(285,205), @(365,160), @(455,190), @(520,245), @(325,275), @(430,285), @(245,270), @(495,145))
foreach ($point in $apples) {
    $graphics.FillEllipse($apple, $point[0], $point[1], 25, 25)
    $graphics.FillEllipse($highlight, ($point[0] + 5), ($point[1] + 4), 7, 6)
}

$graphics.Dispose()
$sky.Dispose()
$trunk.Dispose()
$apple.Dispose()
$highlight.Dispose()
foreach ($brush in $leafBrushes) { $brush.Dispose() }

$bitmap.Save("D:\farmap\.tools\Apple_Tree.jpg", [System.Drawing.Imaging.ImageFormat]::Jpeg)
$bitmap.Dispose()
