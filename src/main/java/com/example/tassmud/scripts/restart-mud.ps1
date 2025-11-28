param(
    [switch]$NoBuild
)

# Also check for --nobuild in $args for unix-style flag
if ($args -contains "--nobuild") {
    $NoBuild = $true
}

# 1) Kill any existing java processes (best effort)
Write-Host "Stopping any running Java processes..."
Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
    try {
        Write-Host "  Killing PID $($_.Id) ($($_.ProcessName))"
        Stop-Process -Id $_.Id -Force
    } catch {
        Write-Warning "  Failed to kill PID $($_.Id): $_"
    }
}

# Small pause to let processes exit
Start-Sleep -Seconds 1

# 2) Optionally build
if (-not $NoBuild) {
    Write-Host "Running Maven build (skip tests)..."
    mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Maven build failed (exit code $LASTEXITCODE). Aborting."
        exit $LASTEXITCODE
    }
} else {
    Write-Host "Skipping build because --nobuild was specified."
}

# 3) Start the server
Write-Host "Starting TassMUD server..."
$jarPath = "target\tass-mud-0.1.0-shaded.jar"
if (-not (Test-Path $jarPath)) {
    Write-Error "Jar not found at $jarPath. Did the build succeed?"
    exit 1
}

# Start in a new window so it keeps running
Start-Process -FilePath "java" -ArgumentList "-jar `"$jarPath`"" -WorkingDirectory (Get-Location)

Write-Host "TassMUD server started."