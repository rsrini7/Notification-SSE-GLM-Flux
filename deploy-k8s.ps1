kubectl apply -k k8s/overlays/development

Write-Host "`n>>> Pausing for 5 seconds to allow pods to initialize..."
Start-Sleep -Seconds 5

Write-Host "`n>>> Checking pod status..."
kubectl get pods -n broadcast-system --watch
