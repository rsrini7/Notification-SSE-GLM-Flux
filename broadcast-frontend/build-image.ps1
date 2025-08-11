Write-Output "npm run build"
npm run build
Write-Output "docker build --no-cache -t broadcast-nginx:1.0.4 ."
docker build --no-cache -t broadcast-nginx:1.0.4 .