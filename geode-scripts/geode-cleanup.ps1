<#
.SYNOPSIS
    Clears all data from all application-specific regions in the running Apache Geode cluster.
.DESCRIPTION
    This script connects to the Geode locator and clears all data from the application regions.
    It uses 'remove --all' for replicated regions. For partitioned regions, it queries for all
    keys and removes them one by one, as '--all' is not supported.
#>

# --- Configuration ---
$DockerImage = "custom-geode:1.0"
$LocatorAddress = "localhost[10334]"

# List of all replicated regions to be cleared
$replicatedRegions = @(
    "broadcast-content",
    "connection-heartbeat",
    "sse-user-messages",
    "sse-all-messages"
    "user-connections"
)

# List of all partitioned regions to be cleared
$partitionedRegions = @(
    "user-messages-inbox"
)

# --- Script Logic ---
Write-Host "Preparing to clear data from Geode regions..." -ForegroundColor Yellow

try {
    # --- Step 1: Clear all REPLICATED regions using 'remove --all' ---
    Write-Host "Clearing replicated regions..." -ForegroundColor Cyan
    $replicatedCommandList = New-Object System.Collections.Generic.List[string]
    $replicatedCommandList.Add("connect --locator=$LocatorAddress")
    foreach ($region in $replicatedRegions) {
        $replicatedCommandList.Add("remove --region=/$region --all")
    }
    $replicatedCommandScript = $replicatedCommandList -join "`n"
    $replicatedCommandScript | docker run -i --rm --network host $DockerImage gfsh

    # --- Step 2: Clear all PARTITIONED regions by querying for keys and removing one-by-one ---
    foreach ($region in $partitionedRegions) {
        Write-Host "Preparing to clear partitioned region: /$region" -ForegroundColor Cyan

        # a) Build and execute the query to get all keys
        $queryScript = "connect --locator=$LocatorAddress`nquery --query=`"SELECT key FROM /$region.keys key`""
        Write-Host "Executing query to fetch keys..."
        $queryResult = $queryScript | docker run -i --rm --network host $DockerImage gfsh

        # b) Parse the gfsh output to extract only the key values
        $lines = $queryResult -split "`r?`n"
        $dataStarted = $false
        $keys = foreach ($line in $lines) {
            if ($dataStarted) {
                $trimmedLine = $line.Trim()
                if (-not [string]::IsNullOrEmpty($trimmedLine)) {
                    $trimmedLine
                }
            }
            if ($line -like '------*') {
                $dataStarted = $true
            }
        }

        if ($keys.Count -eq 0) {
            Write-Host "Partitioned region '/$region' is already empty. Nothing to remove." -ForegroundColor Green
            continue
        }

        Write-Host "Found $($keys.Count) keys to remove from '/$region'. Building remove commands..."

        # c) Build the script with all the individual remove commands
        $removeCommandList = New-Object System.Collections.Generic.List[string]
        $removeCommandList.Add("connect --locator=$LocatorAddress")
        foreach ($key in $keys) {
            # Use single quotes around the key for safety
            $removeCommandList.Add("remove --region=/$region --key='$key'")
        }
        $removeScript = $removeCommandList -join "`n"

        # d) Execute the remove script
        Write-Host "Executing batch remove for '/$region'..."
        $removeScript | docker run -i --rm --network host $DockerImage gfsh
    }

    Write-Host "`n✅ Geode regions cleared successfully." -ForegroundColor Green
}
catch {
    Write-Host "`n❌ An error occurred while trying to clear Geode regions." -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
}