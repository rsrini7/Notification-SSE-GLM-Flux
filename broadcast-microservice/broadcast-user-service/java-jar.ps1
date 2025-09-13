# Remove logs, -Recurse and -Force handle subdirectories and locked files.
# -ErrorAction SilentlyContinue prevents errors if the logs directory doesn't exist.
Remove-Item -Path "logs\*" -Recurse -Force -ErrorAction SilentlyContinue

# Run the Maven build command
mvn clean package

# Set OpenTelemetry Environment Variables for the agent
$env:OTEL_SERVICE_NAME = "broadcast-user-service"
$env:OTEL_EXPORTER_OTLP_ENDPOINT = "http://localhost:4318"

# Generate a random suffix to create a dynamic pod name for testing cleanup
$randomSuffix = -join ((65..90) + (97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })
$env:POD_NAME = "broadcast-user-service-$randomSuffix"
$env:CLUSTER_NAME = "cluster-a"
# Run the application
Write-Host "Starting user-service with DYNAMIC pod name: $($env:POD_NAME) and cluster.name=$($env:CLUSTER_NAME)..."
java -javaagent:../../opentelemetry-javaagent.jar "-Duser.timezone=UTC" "-Dspring.profiles.active=dev-pg" "-Dpod.name=$($env:POD_NAME)" "-Dcluster.name=$($env:CLUSTER_NAME)" -jar target/broadcast-user-service-1.0.0.jar