<#
.SYNOPSIS
Puts "loadout" on your PATH.

.DESCRIPTION
Adds the folder holding the Loadout shim to the *user* PATH, so "loadout" works in
any new terminal without a full java -jar invocation.

Only the user PATH is read and rewritten -- never $env:Path. That distinction matters:
$env:Path is the machine PATH and the user PATH already joined together, so writing it
back into the user scope silently copies every machine entry into your account. The
usual symptom is a PATH that doubles in length on each run until things stop resolving.
#>
[CmdletBinding()]
param(
	# Defaults to wherever this script is, which is correct for both a built
	# distribution and a source checkout's bin folder.
	[string] $Directory = $PSScriptRoot
)

$ErrorActionPreference = 'Stop'

$resolved = (Resolve-Path -LiteralPath $Directory).Path.TrimEnd('\')

$shim = Join-Path $resolved 'loadout.cmd'
if (-not (Test-Path -LiteralPath $shim)) {
	throw "No loadout.cmd in $resolved. Build it first with: .\gradlew dist"
}

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
if ($null -eq $userPath) { $userPath = '' }

$entries = $userPath.Split(';') | Where-Object { $_ -ne '' }
if ($entries -contains $resolved) {
	Write-Host "Already on your PATH: $resolved"
} else {
	$updated = (@($entries) + $resolved) -join ';'
	[Environment]::SetEnvironmentVariable('Path', $updated, 'User')
	Write-Host "Added to your PATH: $resolved"
}

# The change lands in the registry and is picked up by processes started afterwards,
# so this session needs it applied by hand to avoid "open a new terminal" confusion.
if (-not ($env:Path.Split(';') -contains $resolved)) {
	$env:Path = "$env:Path;$resolved"
}

Write-Host ''
Write-Host 'Ready. Try:' -ForegroundColor Green
Write-Host '    loadout sources'
Write-Host ''
Write-Host 'Other terminals need to be reopened before they see it.'
