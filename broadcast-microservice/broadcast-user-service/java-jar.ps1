# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command (this command is the same)
mvn clean package

# Set environment variables for the current PowerShell session
$env:POD_NAME = "broadcast-user-service-0"
$env:CLUSTER_NAME = "cluster-a"

# Run the application
# The fix is to pass pod.name and cluster.name as -D system properties
Write-Host "Starting user-service with pod.name=$($env:POD_NAME) and cluster.name=$($env:CLUSTER_NAME)..."
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg" "-Dpod.name=$($env:POD_NAME)" "-Dcluster.name=$($env:CLUSTER_NAME)" -jar target/broadcast-user-service-1.0.0.jar