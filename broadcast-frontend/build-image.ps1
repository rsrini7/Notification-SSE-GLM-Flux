Remove-Item -Path "dist\*" -Recurse -Force -ErrorAction SilentlyContinue
Write-Output "npm run build"
npm run build
Write-Output "docker build --no-cache -t broadcast-nginx:1.0.5 ."
docker build --no-cache -t broadcast-nginx:1.0.5 .