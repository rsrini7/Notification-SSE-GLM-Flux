# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command
mvn clean package

# $env:GEODE_LOCATOR_HOST = "localhost"
# $env:GEODE_LOCATOR_PORT = "10334"

# Run the application
# Note: In PowerShell, quotes around the -D arguments are not needed.
Write-Host "Starting admin-service..."
java "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg,admin-only" -jar target/broadcast-admin-service-1.0.0.jar