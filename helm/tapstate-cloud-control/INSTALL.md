# Helm install prerequisites for Global Control Plane

# 1. Create namespace
kubectl create namespace tapstate-cloud-control

# 2. Create k3s Secrets (replace values with actual secrets from admin)
# ---- MongoDB Atlas URI (read-only user) ----
kubectl create secret generic tapstate-cloud-control-mongo-uri \
  -n tapstate-cloud-control \
  --from-literal=mongo-uri='mongodb+srv://cloud-control-user:password@tapstate-cloud-control.xxxxx.mongodb.net/tapstate_control?authSource=admin&tls=true'

# ---- JWKS Signing Key (PEM format) ----
kubectl create secret generic tapstate-cloud-control-jwks-signing-key \
  -n tapstate-cloud-control \
  --from-literal=signing-key="-----BEGIN PRIVATE KEY-----
...
-----END PRIVATE KEY-----"

# ---- AWS Credentials ----
kubectl create secret generic tapstate-cloud-control-aws-credentials \
  -n tapstate-cloud-control \
  --from-literal=access-key-id='AKIA...' \
  --from-literal=secret-access-key='...'

# ---- Atlas Admin API Key ----
kubectl create secret generic tapstate-cloud-control-atlas-admin-key \
  -n tapstate-cloud-control \
  --from-literal=api-key='...' \
  --from-literal=api-key-secret='...'

# ---- Shared sample database password ----
# Keep this file outside the repository with owner-only permissions.
kubectl create secret generic tapstate-cloud-control-sample-sources \
  -n tapstate-cloud-control \
  --from-file=password=/secure/path/sample-database-password

# This Secret is optional: without it, Cluster Consoles hide the sample-source entry.
# After creating or rotating it, restart the Control Plane so its environment refreshes.
kubectl rollout restart deployment/tapstate-cloud-control -n tapstate-cloud-control

# 3. Helm install (local k3s dev)
helm install tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-local.yaml \
  -n tapstate-cloud-control \
  --namespace-create

# 3b. Helm install (AWS EC2 k3s preview)
helm install tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-aws.yaml \
  -n tapstate-cloud-control

# 4. Verify
kubectl get pods -n tapstate-cloud-control
kubectl get svc -n tapstate-cloud-control
curl http://localhost:30080/health

# 5. Upgrade
helm upgrade tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-aws.yaml \
  -n tapstate-cloud-control
