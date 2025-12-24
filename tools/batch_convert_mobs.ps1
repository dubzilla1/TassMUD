$areDir = 'src\main\resources\MERC\area'
$outBase = 'src\main\resources\data\MERC'

if (-not (Test-Path $areDir)) {
    Write-Error "Area directory not found: $areDir"
    exit 1
}

Get-ChildItem -Path $areDir -Filter '*.are' | ForEach-Object {
    $f = $_.FullName
    $name = $_.BaseName
    $outdir = Join-Path $outBase $name
    New-Item -ItemType Directory -Force -Path $outdir | Out-Null
    $out = Join-Path $outdir 'mobiles.yaml'
    Write-Host "Converting $f -> $out"
    python tools\merc_mob_converter.py $f $out
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "Converter returned exit code $LASTEXITCODE for $f"
    }
}
Write-Host "Done converting all .are files."