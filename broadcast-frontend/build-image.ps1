Write-Output "npm run build"
npm run build
Write-Output "docker build -t broadcast-nginx:1.0.3 ."
docker build -t broadcast-nginx:1.0.3 .