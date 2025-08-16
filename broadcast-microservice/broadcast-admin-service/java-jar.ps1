# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command
mvn clean package

# Run the application
# Note: In PowerShell, quotes around the -D arguments are not needed.
Write-Host "Starting admin-service..."
java "-Duser.timezone=Asia/Kolkata" "-Dspring.profiles.active=dev-pg" -jar target/broadcast-admin-service-1.0.0.jar