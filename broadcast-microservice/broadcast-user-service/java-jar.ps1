# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command (this command is the same)
mvn clean package

# Set environment variables for the current PowerShell session
$env:POD_NAME = "docker-pod-0"
$env:CLUSTER_NAME = "local"

# Run the application
# Note: In PowerShell, quotes around the -D arguments are not needed.
Write-Host "Starting user-service with POD_NAME=$($env:POD_NAME) and CLUSTER_NAME=$($env:CLUSTER_NAME)..."
java "-Duser.timezone=Asia/Kolkata" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-user-service-1.0.0.jar