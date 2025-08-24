<#
.SYNOPSIS
    Builds the Java project, copies the necessary JAR file, and builds the
    custom Geode Docker image for local Kubernetes deployment.
.DESCRIPTION
    This script automates the following steps:
    1. Runs 'mvn clean install' from the 'broadcast-microservice' directory.
    2. Copies the resulting 'broadcast-geode-shared-1.0.0.jar' to the 'geode-scripts' directory.
    3. Builds the custom Geode Docker image tagged as 'custom-geode:1.0'.
#>

# Exit the script immediately if any command fails
$ErrorActionPreference = "Stop"

# --- Script Logic ---
Write-Host ">>> Step 1: Building the Java microservices..." -ForegroundColor Green
Push-Location -Path "broadcast-microservice"
try {
    mvn clean install
}
finally {
    Pop-Location
}
Write-Host ">>> Java build complete." -ForegroundColor Green


Write-Host "`n>>> Step 2: Copying shared JAR to geode-scripts..." -ForegroundColor Green
$sourceJar = "broadcast-microservice/broadcast-geode-shared/target/broadcast-geode-shared-1.0.0.jar"
$destinationJar = "geode-scripts/broadcast-geode-shared-1.0.0.jar"

# Check if the source JAR exists before copying
if (-not (Test-Path $sourceJar)) {
    Write-Host "ERROR: Source JAR not found at '$sourceJar'. The Maven build may have failed." -ForegroundColor Red
    exit 1
}

Copy-Item -Path $sourceJar -Destination $destinationJar -Force
Write-Host ">>> JAR copied successfully." -ForegroundColor Green


Write-Host "`n>>> Step 3: Building the custom Geode Docker image..." -ForegroundColor Green
$imageName = "custom-geode:1.0"
Push-Location -Path "geode-scripts"
try {
    docker build -t $imageName .
}
finally {
    Pop-Location
}
Write-Host ">>> Docker image build complete." -ForegroundColor Green


Write-Host "`n✅ Build process finished." -ForegroundColor Green
Write-Host "The Docker image '$imageName' is now available locally."
Write-Host "Ensure your Kubernetes deployments are using this image name and 'imagePullPolicy: IfNotPresent'."