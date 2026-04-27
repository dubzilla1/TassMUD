param(
    [switch]$NoBuild,
    [switch]$Clean,
    [int]$Port = 4003,
    [switch]$Debug,
    [int]$DebugPort = 5005
)

try {

# Ensure JAVA_HOME points to a JDK that supports the project's release version (25).
# VS Code terminals and fresh shells may inherit a stale JAVA_HOME pointing to an older JDK.
if ($env:JAVA_HOME) {
    $javacExe = Join-Path $env:JAVA_HOME "bin\javac.exe"
    if (Test-Path $javacExe) {
        $verLine = & $javacExe -version 2>&1 | Select-Object -First 1
        if ($verLine -match '(\d+)' -and [int]$Matches[1] -lt 25) {
            Write-Host "Current JAVA_HOME points to JDK $($Matches[1]); need 25+. Auto-detecting..."
            $env:JAVA_HOME = $null
        }
    }
}
if (-not $env:JAVA_HOME) {
    # Try to locate a JDK 25+ installation
    $searchRoots = @("C:\Program Files\Microsoft","C:\Program Files\Eclipse Adoptium","C:\Program Files\Java","$env:USERPROFILE\.jdk")
    $jdk25 = Get-ChildItem $searchRoots -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match 'jdk-2[5-9]' -or $_.Name -match 'jdk-[3-9]\d' } |
        Sort-Object Name -Descending | Select-Object -First 1
    if ($jdk25 -and (Test-Path (Join-Path $jdk25.FullName "bin\javac.exe"))) {
        $env:JAVA_HOME = $jdk25.FullName
        Write-Host "Auto-detected JAVA_HOME: $($env:JAVA_HOME)"
    }
}

# Also check for --nobuild and --clean in $args for unix-style flags
if ($args -contains "--nobuild") {
    $NoBuild = $true
}
if ($args -contains "--clean") {
    $Clean = $true
}
if ($args -contains "--debug") {
    $Debug = $true
}

# 1) Stop existing TassMUD java processes (match jar name, command-line or port)
Write-Host "Stopping any running TassMUD processes (matching jar or port)..."
$jarName = "tass-mud-1.0.0-shaded.jar"
$matched = @()
 # default server port (used for port checks)
 $port = $Port

# Try to find java processes by inspecting command lines (requires privileges)
try {
    $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'java' -or $_.Name -match 'javaw' }
    foreach ($p in $procs) {
        if ($p.CommandLine -and ($p.CommandLine -like "*$jarName*" -or $p.CommandLine -like "*tass-mud*")) {
            $matched += $p
        }
    }
} catch {
    Write-Warning ("Could not inspect process command lines: {0}" -f $_)
}

# Fallback: if none matched, try to detect a process listening on the default port
if ($matched.Count -eq 0) {
    $port = $Port
    try {
        $listeners = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
        if ($listeners) {
            foreach ($l in $listeners) {
                $p = Get-Process -Id $l.OwningProcess -ErrorAction SilentlyContinue
                if ($p) { $matched += $p }
            }
        }
    } catch {
        # Get-NetTCPConnection may not be available on older systems; ignore
    }
}

if ($matched.Count -gt 0) {
    foreach ($p in $matched) {
        $targetPid = $null
        if ($p.PSObject.Properties['ProcessId']) { $targetPid = $p.ProcessId }
        elseif ($p.PSObject.Properties['Id']) { $targetPid = $p.Id }
        $pname = if ($p.Name) { $p.Name } else { $p.CommandLine }
        if ($targetPid) {
            try {
                Write-Host "  Stopping PID $targetPid ($pname)"
                Stop-Process -Id $targetPid -Force -ErrorAction Stop
            } catch {
                Write-Warning ("  Failed to stop PID {0}: {1}" -f $targetPid, $_)
            }
        }
    }
} else {
    Write-Host "  No matching TassMUD java processes found; skipping kill step."
}

# Small pause to let processes exit
Start-Sleep -Seconds 1

# Wait for the port to be free (avoid race where previous JVM hasn't fully released socket)
$maxWait = 10
$waited = 0
Write-Host "Waiting up to $maxWait seconds for port $port to be free..."
while ($waited -lt $maxWait) {
    $busy = $false
    try {
        $listeners = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
        if ($listeners) { $busy = $true }
    } catch {
        # Fallback to parsing netstat output
        try {
            $lines = netstat -ano | Select-String ":$port"
            if ($lines -and $lines.Count -gt 0) { $busy = $true }
        } catch {
            # Ignore parsing errors
        }
    }
    if (-not $busy) { break }
    Start-Sleep -Seconds 1
    $waited++
}
if ($busy) {
    Write-Warning "Port $port still in use after $maxWait seconds; aborting start to avoid bind error."
    exit 1
}

# 2) Optionally build
if (-not $NoBuild) {
    if ($Clean) {
        # Pre-delete target\classes to avoid VS Code Java Language Server file locks
        # that prevent mvn clean from deleting the directory
        if (Test-Path "target\classes") {
            $deleted = $false
            for ($retry = 1; $retry -le 5; $retry++) {
                try {
                    Remove-Item "target\classes" -Recurse -Force -ErrorAction Stop
                    $deleted = $true
                    break
                } catch {
                    Write-Host "  target\classes locked (attempt $retry/5), retrying in 2s..."
                    Start-Sleep -Seconds 2
                }
            }
            if (-not $deleted) {
                Write-Warning "Could not delete target\classes; mvn clean may fail."
            }
        }
        Write-Host "Running Maven clean build (skip tests)..."
        mvn -DskipTests clean package
    } else {
        Write-Host "Running Maven build (skip tests)..."
        mvn -DskipTests package
    }
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Maven build failed (exit code $LASTEXITCODE). Aborting."
        exit $LASTEXITCODE
    }
} else {
    Write-Host "Skipping build because --nobuild was specified."
}

# 3) Start the server
Write-Host "Starting TassMUD server..."
$jarPath = "target\tass-mud-0.1.0.jar"
if (-not (Test-Path $jarPath)) {
    Write-Error "Jar not found at $jarPath. Did the build succeed?"
    exit 1
}

# Ensure logs directory exists
$logsDir = "logs"
if (-not (Test-Path $logsDir)) {
    try { New-Item -ItemType Directory -Path $logsDir | Out-Null } catch { }
}

# Prefer java from JAVA_HOME if available
$javaExe = "java"
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME "bin\java.exe"
    if (Test-Path $candidate) { $javaExe = $candidate }
}

# Prepare debug options if requested
$debugArgs = ""
if ($Debug) {
    Write-Host "Starting JVM in debug mode (JDWP) on port $DebugPort"
    $debugArgs = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$DebugPort"
}

# Start via cmd.exe so we can redirect stdout/stderr on PowerShell 5.1
try {
    $maxAttempts = 3
    $attempt = 0
    $startedOk = $false
    while (-not $startedOk -and $attempt -lt $maxAttempts) {
        $attempt++
        Write-Host "Starting server (attempt $attempt of $maxAttempts)..."
        # Export TASSMUD_PORT for the JVM process and start it. Include debug args if requested.
        # Build the java command separately so variable expansion works cleanly.
        $javaCommand = "`"$javaExe`""
        if ($debugArgs -ne "") { $javaCommand = "$javaCommand $debugArgs" }
        $javaCommand = "$javaCommand -jar `"$jarPath`""
        $cmdArgs = "/c set TASSMUD_PORT=$port && $javaCommand > `"$logsDir\\server.out`" 2> `"$logsDir\\jvm_stderr.log`""
        Start-Process -FilePath "cmd.exe" -ArgumentList $cmdArgs -WorkingDirectory (Get-Location) -WindowStyle Hidden | Out-Null

        # Give the JVM a short moment to start and write any immediate errors
        Start-Sleep -Seconds 2

        # Inspect JVM stderr and Logback error log for bind failures
        $errText = ""
        try { $errText = Get-Content -Path (Join-Path $logsDir "jvm_stderr.log") -Raw -ErrorAction SilentlyContinue } catch {}
        try { $errText += Get-Content -Path (Join-Path $logsDir "server.err") -Raw -ErrorAction SilentlyContinue } catch {}
        if ($errText -and $errText -match "Address already in use|BindException") {
            Write-Warning "Detected bind error in logs (attempt $attempt)."
            # Dump diagnostic info to help investigate which process owns the port
            try {
                $dumpFile = Join-Path $logsDir ("port-dump-{0}.txt" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
                "==== netstat -ano ====" | Out-File -FilePath $dumpFile -Encoding utf8
                netstat -ano | Out-File -FilePath $dumpFile -Append -Encoding utf8
                "`n==== java processes (command lines) ====" | Out-File -FilePath $dumpFile -Append -Encoding utf8
                Get-CimInstance Win32_Process -Filter "Name LIKE '%java%' OR Name LIKE '%javaw%'" | Select-Object ProcessId,CommandLine | Out-File -FilePath $dumpFile -Append -Encoding utf8
                Write-Host "Wrote diagnostic dump to $dumpFile"
            } catch {
                Write-Warning ("Failed to write diagnostic dump: {0}" -f $_)
            }
            # Try to clean up any leftover processes that might be holding the port
            try {
                $procs = Get-CimInstance Win32_Process -ErrorAction SilentlyContinue | Where-Object { $_.Name -match 'java' -or $_.Name -match 'javaw' }
                foreach ($p in $procs) {
                    if ($p.CommandLine -and ($p.CommandLine -like "*$jarName*" -or $p.CommandLine -like "*tass-mud*")) {
                        $pidToKill = $p.ProcessId
                        try { Stop-Process -Id $pidToKill -Force -ErrorAction SilentlyContinue } catch {}
                    }
                }
            } catch {}

            if ($attempt -lt $maxAttempts) { Start-Sleep -Seconds (2 * $attempt) }
        } else {
            $startedOk = $true
            Write-Host "TassMUD server started. Logs: $logsDir\\server.log (INFO+), server.err (WARN+), spawn.log"
        }
    }
    if (-not $startedOk) {
        Write-Warning "Failed to start server after $maxAttempts attempts; inspect $logsDir\\server.err and $logsDir\\jvm_stderr.log for details."
        exit 1
    }
} catch {
    Write-Warning ("Failed to start server process: {0}" -f $_)
}

} catch {
    Write-Warning ("Restart script failed: {0}" -f $_)
    exit 1
}