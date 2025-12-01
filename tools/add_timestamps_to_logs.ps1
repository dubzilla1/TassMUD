param(
    [string]$WorkspaceRoot = "${PSScriptRoot}\..",
    [string[]]$Files = @("logs\server.out", "logs\server.err")
)

function Add-TimestampsToFile {
    param(
        [string]$Path
    )

    if (-not (Test-Path $Path)) {
        Write-Host "File not found: $Path" -ForegroundColor Yellow
        return
    }

    $temp = "$Path.tmp"
    try {
        Get-Content -Path $Path -Raw -ErrorAction Stop | 
            ForEach-Object {
                # Split into lines while preserving empties
                $_ -split "\r?\n" | ForEach-Object {
                    if ($_ -eq '') { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') " } else { "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') $_" }
                }
            } | Set-Content -Path $temp -Encoding UTF8 -Force

        # Replace original file
        Move-Item -Path $temp -Destination $Path -Force
        Write-Host "Stamped: $Path"
    } catch {
        Write-Host "Failed to timestamp $Path: $_" -ForegroundColor Red
        if (Test-Path $temp) { Remove-Item $temp -Force }
    }
}

Push-Location $WorkspaceRoot
try {
    foreach ($f in $Files) {
        $full = Join-Path -Path (Get-Location) -ChildPath $f
        Add-TimestampsToFile -Path $full
    }
} finally {
    Pop-Location
}

Write-Host "Done." -ForegroundColor Green
