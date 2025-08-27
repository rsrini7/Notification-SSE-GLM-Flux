# --- Configuration ---
$ServiceName = "broadcast-admin-service"
$BaseImageTag = "1.0.0-crac-base"
$FinalImageTag = "1.0.0-crac-final"
$CheckpointContainerName = "${ServiceName}-checkpoint-container"

# --- Phase 1: Build the CRaC-enabled Base Image ---
Write-Host ">>> Phase 1: Building CRaC-enabled base image..." -ForegroundColor Green

# Use environment variables to tell the Paketo Buildpack to use a CRaC JDK.
# We set these for the current PowerShell session scope.
$env:BP_JVM_TYPE = "CRaC"
$env:BP_SPRING_AOT_ENABLED = "false"

# Build the image using Maven.
mvn spring-boot:build-image "-Dspring-boot.build-image.imageName=${ServiceName}:${BaseImageTag}"

# Check if the last command was successful
if ($LASTEXITCODE -ne 0) {
  Write-Host ">>> ERROR: Failed to build the base image." -ForegroundColor Red
  exit 1
}

# --- Phase 2: Run, Warm Up, and Checkpoint ---
Write-Host ">>> Phase 2: Starting container to create checkpoint..." -ForegroundColor Cyan

# Start the container in the background.
docker run -d --rm `
  --name $CheckpointContainerName `
  --privileged `
  -p 8081:8081 `
  "${ServiceName}:${BaseImageTag}"

Write-Host ">>> Waiting for the application to be ready (approx. 30 seconds)..." -ForegroundColor Cyan
Start-Sleep -Seconds 30 # In a real script, use a health check loop here

# Trigger the checkpoint and commit the state to a new image.
Write-Host ">>> Triggering checkpoint and creating the final image..." -ForegroundColor Cyan
docker commit --change='CMD ["/cnb/process/restore"]' $CheckpointContainerName "${ServiceName}:${FinalImageTag}"

if ($LASTEXITCODE -ne 0) {
  Write-Host ">>> ERROR: Failed to commit the checkpointed image." -ForegroundColor Red
  docker stop $CheckpointContainerName
  exit 1
}

# --- Cleanup ---
Write-Host ">>> Cleaning up the temporary container..." -ForegroundColor Cyan
docker stop $CheckpointContainerName

Write-Host ">>> ✅ Success! Your fast-starting image is ready: ${ServiceName}:${FinalImageTag}" -ForegroundColor Green
Write-Host ">>> Run it with: docker run -p 8081:8081 ${ServiceName}:${FinalImageTag}"