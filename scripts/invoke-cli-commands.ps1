param(
    [string[]]$Commands = @("help", "exit"),
    [string]$ProjectRoot = (Split-Path -Parent $PSScriptRoot),
    [string]$StdoutPath = "",
    [string]$StderrPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.Encoding]::UTF8
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

function Get-CliBatPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory
    )

    return Join-Path $WorkingDirectory "build\install\ai_advent_day_21\bin\local-document-indexer.bat"
}

function Invoke-InteractiveCliCommands {
    param(
        [Parameter(Mandatory = $true)]
        [string]$WorkingDirectory,

        [Parameter(Mandatory = $true)]
        [string[]]$InputCommands,

        [Parameter(Mandatory = $true)]
        [string]$BatPath
    )

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = "cmd.exe"
    $psi.Arguments = "/c chcp 65001>nul && `"$BatPath`""
    $psi.WorkingDirectory = $WorkingDirectory
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.Encoding]::UTF8
    $psi.StandardErrorEncoding = [System.Text.Encoding]::UTF8

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $psi

    try {
        $process.Start() | Out-Null

        $scenarioContent = (($InputCommands | ForEach-Object { [string]$_ }) -join "`n")
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        $stdinBytes = $utf8NoBom.GetBytes("$scenarioContent`n")
        $process.StandardInput.BaseStream.Write($stdinBytes, 0, $stdinBytes.Length)
        $process.StandardInput.BaseStream.Flush()
        $process.StandardInput.Close()

        $stdout = $process.StandardOutput.ReadToEnd()
        $stderr = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        return [PSCustomObject]@{
            ExitCode = $process.ExitCode
            Stdout = $stdout
            Stderr = $stderr
        }
    } finally {
        $process.Dispose()
    }
}

Set-Location $ProjectRoot

$cliBatPath = Get-CliBatPath -WorkingDirectory $ProjectRoot
if (-not (Test-Path -LiteralPath $cliBatPath)) {
    throw "Built CLI launcher not found: $cliBatPath. Run .\gradlew.bat build and .\gradlew.bat installDist first."
}

$result = Invoke-InteractiveCliCommands `
    -WorkingDirectory $ProjectRoot `
    -InputCommands $Commands `
    -BatPath $cliBatPath

if ($StdoutPath) {
    [System.IO.File]::WriteAllText($StdoutPath, $result.Stdout, [System.Text.Encoding]::UTF8)
}

if ($StderrPath) {
    [System.IO.File]::WriteAllText($StderrPath, $result.Stderr, [System.Text.Encoding]::UTF8)
}

if ($result.Stdout) {
    [Console]::Out.Write($result.Stdout)
}

if ($result.Stderr) {
    [Console]::Out.Write($result.Stderr)
}

exit $result.ExitCode
