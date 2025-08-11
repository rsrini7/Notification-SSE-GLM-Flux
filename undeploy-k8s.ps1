kubectl delete -k k8s/overlays/development

Write-Host "`n>>> Pausing to allow pods to delete..."
Read-Host

Write-Host "`n>>> Checking pod status..."
kubectl get pods -n broadcast-system --watch