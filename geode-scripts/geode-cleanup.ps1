<#
.SYNOPSIS
    Clears all data from all application-specific regions in the running Apache Geode cluster.
.DESCRIPTION
    This script connects to the Geode locator via Docker and executes a 'remove --all' 
    command for each region defined in the application. It's designed to be used in 
    a development environment to reset the cache state.
.EXAMPLE
    .\geode-cleanup.ps1
#>

# --- Configuration ---
$DockerImage = "custom-geode:1.0"
$LocatorAddress = "localhost[10334]"

# UPDATED: List of all regions to be cleared
$regions = @(
    "active-group-broadcasts",
    "broadcast-content",
    "connection-metadata",
    "pending-events",
    "cluster-pod-connections",
    "cluster-pod-heartbeats",
    "sse-messages",
    "user-connections",
    "user-messages"
)

# --- Script Logic ---
Write-Host "Preparing to clear all data from $($regions.Count) Geode regions using the 'remove --all' command..." -ForegroundColor Yellow

# Start with the connect command
$commandList = New-Object System.Collections.Generic.List[string]
$commandList.Add("connect --locator=$LocatorAddress")

# Add a 'remove --all' command for each region
foreach ($region in $regions) {
    $commandList.Add("remove --region=/$region --all")
}

# Join all commands into a single string
$commandScript = $commandList -join "`n"

Write-Host "`nGenerated gfsh script to be executed:"
Write-Host $commandScript -ForegroundColor DarkCyan
Write-Host "`nExecuting cleanup script via Docker..." -ForegroundColor Yellow

try {
    # Pipe the script into the gfsh process running inside the Docker container.
    $commandScript | docker run -i --rm --network host $DockerImage gfsh

    Write-Host "`n✅ Geode regions cleared successfully." -ForegroundColor Green
}
catch {
    Write-Host "`n❌ An error occurred while trying to clear Geode regions." -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
}