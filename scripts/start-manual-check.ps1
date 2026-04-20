param(
    [switch]$Headless,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Get-CliBatPath {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)
    Join-Path $ProjectRoot "build\install\ai_advent_day_25\bin\local-document-indexer.bat"
}

function Assert-LauncherExists {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Description
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Description not found: $Path. Run .\gradlew.bat build and .\gradlew.bat installDist first."
    }
}

function New-Utf8CmdArgumentList {
    param(
        [Parameter(Mandatory = $true)][string]$ProjectRoot,
        [Parameter(Mandatory = $true)][string]$BatPath
    )

    return @(
        "/k",
        "chcp 65001>nul && cd /d `"$ProjectRoot`" && call `"$BatPath`""
    )
}

$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot

if ($SkipBuild) {
    Write-Output "Skipping build step and reusing existing artifacts..."
} else {
    Write-Output "Building project and direct launcher..."
    & .\gradlew.bat build installDist
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed with exit code $LASTEXITCODE."
    }
}

$cliBatPath = Get-CliBatPath -ProjectRoot $projectRoot
Assert-LauncherExists -Path $cliBatPath -Description "Built CLI launcher"

if ($Headless) {
    $tmpDir = Join-Path $projectRoot "build\tmp\manual-check"
    New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null

    $stdoutPath = Join-Path $tmpDir "cli.out"
    $stderrPath = Join-Path $tmpDir "cli.err"

    @($stdoutPath, $stderrPath) | ForEach-Object {
        if (Test-Path $_) {
            Remove-Item $_ -Force -ErrorAction SilentlyContinue
        }
    }

    $null = & powershell -ExecutionPolicy Bypass -File (Join-Path $projectRoot "scripts\invoke-cli-commands.ps1") `
        -ProjectRoot $projectRoot `
        -Commands @("help", "exit") `
        -StdoutPath $stdoutPath `
        -StderrPath $stderrPath

    if (Test-Path $stdoutPath) {
        [Console]::Out.Write((Get-Content $stdoutPath -Raw))
    }

    if (Test-Path $stderrPath) {
        [Console]::Out.Write((Get-Content $stderrPath -Raw))
    }

    return
}

$cliArgumentList = New-Utf8CmdArgumentList -ProjectRoot $projectRoot -BatPath $cliBatPath

Write-Output "Opening CLI window..."
$cliWindow = Start-Process cmd.exe `
    -ArgumentList $cliArgumentList `
    -WorkingDirectory $projectRoot `
    -PassThru

Write-Output "Manual check environment is ready."
Write-Output "CLI window PID: $($cliWindow.Id)"
Write-Output "Launcher: $cliBatPath"
