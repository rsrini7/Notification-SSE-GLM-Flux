# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command
mvn clean package

try {
    Write-Host "Starting admin-service... Press Ctrl+C to stop the service and trigger Geode cleanup." -ForegroundColor Green
    # Run the application in the foreground. The script will wait here until the process exits.
    java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg,admin-only" -jar target/broadcast-admin-service-1.0.0.jar
}
catch [System.Management.Automation.PipelineStoppedException] {
    # This block specifically catches the Ctrl+C interrupt.
    Write-Host "`nCtrl+C detected. Proceeding with cleanup..." -ForegroundColor Yellow
}
catch {
    # This block catches any other terminating errors from the java process.
    Write-Host "`nJava process terminated with an error. Proceeding with cleanup..." -ForegroundColor Red
    Write-Host "ERROR: $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    # This block ALWAYS runs, whether the script is stopped with Ctrl+C or exits normally.
    Write-Host "`nShutdown detected. Executing Geode cleanup script..." -ForegroundColor Yellow
    
    # Construct the path to the cleanup script relative to this script's location
    $cleanupScriptPath = Join-Path $PSScriptRoot "../../geode-scripts/geode-cleanup.ps1"
    
    if (Test-Path $cleanupScriptPath) {
        # Use the call operator (&) to execute the script
        & $cleanupScriptPath
    } else {
        Write-Host "WARNING: Cleanup script not found at '$cleanupScriptPath'. Skipping cleanup." -ForegroundColor Red
    }

    Write-Host "`nCleanup process finished. Exiting." -ForegroundColor Green
}