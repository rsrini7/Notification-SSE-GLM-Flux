# --- MODIFICATION START ---
$clusterName = "cluster-a"
# Generate random suffixes for dynamic pod names
$userSuffix = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })
$adminSuffix = -join ((97..122) | Get-Random -Count 5 | ForEach-Object { [char]$_ })

# Set the environment variables, now prefixed with the cluster name
$env:USER_SERVICE_POD_NAME = "$clusterName-broadcast-user-service-$userSuffix"
$env:ADMIN_SERVICE_POD_NAME = "$clusterName-broadcast-admin-service-$adminSuffix"
$env:CLUSTER_NAME = $clusterName
# --- MODIFICATION END ---

Write-Host "Starting services with dynamic pod names:" -ForegroundColor Cyan
Write-Host "  User Service Pod Name: $env:USER_SERVICE_POD_NAME"
Write-Host "  Admin Service Pod Name: $env:ADMIN_SERVICE_POD_NAME"

# Run docker-compose, which will now pick up the environment variables
docker-compose --profile prod up --build --force-recreate