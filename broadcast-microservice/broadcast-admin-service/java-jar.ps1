# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command
mvn clean package

# Set environment variables for the current PowerShell session
# $env:POD_NAME = "admin-local-0"
# $env:CLUSTER_NAME = "cluster-a"

# Run the application
# Note: In PowerShell, quotes around the -D arguments are not needed.
Write-Host "Starting admin-service..."
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-admin-service-1.0.0.jar