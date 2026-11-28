[CmdletBinding()]
param(
    [ValidateSet('help', 'all', 'build', 'rust', 'plugin', 'authme-bridge', 'run', 'fmt', 'fmt-check', 'test', 'clean')]
    [string]$Target = 'all',
    [string[]]$ProgramArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

function Invoke-Checked {
    param([Parameter(Mandatory = $true)][string]$Command, [string[]]$Arguments = @())
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed with exit code $LASTEXITCODE - $Command $($Arguments -join ' ')"
    }
}

function Get-GradleCommand {
    $command = Get-Command gradle.bat -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        $command = Get-Command gradle -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    }
    if (-not $command) { throw 'Gradle was not found on PATH. Install Gradle and a JDK, then retry.' }
    return $command.Source
}

Push-Location $root
try {
    switch ($Target) {
        'help' {
            @'
NekoGate build targets:
  all / build       Build Rust proxy and ViaProxy tunnel plugin
  rust              Build the Rust proxy (release)
  plugin            Build and copy only the ViaProxy plugin to ./plugins/
  authme-bridge     Build optional Bukkit/Spigot bridge separately
  run               Run the Rust proxy (extra args: -ProgramArgs ...)
  fmt               Format Rust sources
  fmt-check         Check Rust formatting
  test              Run Rust tests
  clean             Clean Rust and Java build outputs
'@ | Write-Host
        }
        { $_ -in 'all', 'build' } {
            Invoke-Checked 'cargo' @('build', '--release')
            $gradle = Get-GradleCommand
            Invoke-Checked $gradle @('-p', 'viaproxy-plugin', 'clean', 'installPlugin')
        }
        'rust' { Invoke-Checked 'cargo' @('build', '--release') }
        'plugin' {
            $gradle = Get-GradleCommand
            Invoke-Checked $gradle @('-p', 'viaproxy-plugin', 'clean', 'installPlugin')
        }
        'authme-bridge' {
            $gradle = Get-GradleCommand
            Invoke-Checked $gradle @('-p', 'authme-bridge', 'clean', 'build')
        }
        'run' { Invoke-Checked 'cargo' (@('run', '--release', '--') + $ProgramArgs) }
        'fmt' { Invoke-Checked 'cargo' @('fmt') }
        'fmt-check' { Invoke-Checked 'cargo' @('fmt', '--check') }
        'test' { Invoke-Checked 'cargo' @('test') }
        'clean' {
            Invoke-Checked 'cargo' @('clean')
            $gradle = Get-GradleCommand
            Invoke-Checked $gradle @('-p', 'viaproxy-plugin', 'clean')
            Invoke-Checked $gradle @('-p', 'authme-bridge', 'clean')
        }
    }
}
finally { Pop-Location }
