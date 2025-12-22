param(
    [string]$Destination = "tassmud-merc"
)

$src = (Get-Location).Path
$destPath = Join-Path $src $Destination
if (Test-Path $destPath) {
    Write-Host "Destination exists; removing: $destPath"
    Remove-Item $destPath -Recurse -Force
}

$exclude = @("target","logs","node_modules",".git",".idea",".vs","$Destination")

Write-Host "Creating fork at: $destPath"
New-Item -ItemType Directory -Path $destPath -Force | Out-Null

# Build robocopy exclude options (/XD for directories)
$xd = $exclude | ForEach-Object { "/XD `"$($_)`"" } | Out-String
$xd = $xd -replace "\r?\n"," "

$cmd = "robocopy `"$src`" `"$destPath`" /E /COPY:DAT /R:2 /W:1 $xd /NFL /NDL"
Write-Host "Running: $cmd"
Invoke-Expression $cmd

Write-Host "Fork created at: $destPath"
