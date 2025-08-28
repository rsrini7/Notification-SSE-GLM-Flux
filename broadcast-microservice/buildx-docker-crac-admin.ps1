Write-Output "if ERROR: failed to build: failed to solve: granting entitlement security.insecure is not allowed by build daemon configuration"
Write-Output "then Run : docker-create-crac-builder.ps1"
Write-Output "%%%****%%%% `n"
# $env:DOCKER_BUILDKIT=1
docker buildx build --progress=plain --allow security.insecure -f Dockerfile.crac.admin -t broadcast-admin-service:1.0.0-crac-final --load .