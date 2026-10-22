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

# The configured AWS identity must also allow the Cloud Control Plane to manage
# per-Cluster Elastic IPs: ec2:AllocateAddress, ec2:AssociateAddress,
# ec2:DescribeAddresses, ec2:DisassociateAddress, ec2:ReleaseAddress, and
# ec2:CreateTags. Keep the existing CloudFormation stack and EC2 permissions.

# ---- GoDaddy DNS API key (required only when config.iacMode=real) ----
# A gd_pat_ Personal Access Token works as Bearer with an empty api-secret;
# a classic sso-key needs both values.
kubectl create secret generic tapstate-cloud-control-dns \
  -n tapstate-cloud-control \
  --from-literal=api-key='gd_pat_...' \
  --from-literal=api-secret=''

# ---- IaC SSH-key encryption master key (required only when config.iacMode=real) ----
# Base64-encoded 32 random bytes. A blank value blocks Cluster provisioning.
kubectl create secret generic tapstate-cloud-control-iac \
  -n tapstate-cloud-control \
  --from-literal=master-key="$(head -c32 /dev/urandom | base64)"

# ---- Atlas Admin API credentials (real-mode MDB allocation) ----
# These are NOT read from the environment. The allocation engine reads the
# Activated key from the atlas_configs collection and the ACTIVE shared cluster
# from mdb_clusters — seed both documents into the Control Plane database
# before enabling TAPSTATE_CLOUD_MDB_ATLAS_MOCK=false.

# ---- Optional: JWKS encryption key / fixed runtime Atlas URI ----
# tapstate-cloud-control-jwks-encryption-key (encryption-key) — only when
#   CLOUD_JWKS_ENCRYPTION_ENABLED=true
# tapstate-cloud-control-runtime-atlas-uri (atlas-uri) — legacy Clusters
#   without a linked MdbAllocation; leave absent for per-allocation URIs

# ---- Optional: Cluster version release operator token ----
# Enables the version release/offline operator endpoints. Leave absent: the
# endpoints then stay fail-closed (503).
kubectl create secret generic tapstate-cloud-control-cluster-version \
  -n tapstate-cloud-control \
  --from-literal=release-token="$(openssl rand -hex 32)"

# ---- Optional: Tawk to Feishu feedback notifications ----
# Create one Tawk webhook in each environment's existing Property. Use the
# environment's HTTPS callback URL and select Chat Start and New Ticket. Enable
# signature verification on the Feishu custom bot. Keep both env files outside
# Git with owner-only permissions; never put their values in shell history.
# tawk-webhook.env contains webhook-secret=<Tawk webhook signing secret>.
# feishu-bot.env contains webhook-url=<bot webhook URL> and
# signing-secret=<bot signing secret>.
kubectl create secret generic tapstate-cloud-control-tawk-webhook \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/tawk-webhook.env
kubectl create secret generic tapstate-cloud-control-feishu-bot \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/feishu-bot.env

# For awsdev, create the same Secrets in the tapstate namespace instead.
# The expected Tawk Property ID is selected by values-awsdev.yaml / values-awsprod.yaml.
# The Feishu URL is environment configuration, not a code constant. Each custom
# bot is bound to its group. To change the destination later, create a custom
# bot in the new group, update webhook-url and signing-secret in this
# environment's feishu-bot.env, then reapply the same Secret:
kubectl create secret generic tapstate-cloud-control-feishu-bot \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/feishu-bot.env \
  --dry-run=client -o yaml | kubectl apply -f -
# For awsdev, use namespace tapstate and its own feishu-bot.env. Restart the
# Control Plane after the update so the environment-backed Secret values reload.

# ---- Optional: SMTP for alert email ----
# Credentials only; the relay host/port and the enable switch are non-sensitive
# values in values.yaml (config.notifications.*). Omit the Secret entirely for
# an unauthenticated relay.
# smtp.env contains username=<smtp user> and password=<smtp password>.
kubectl create secret generic tapstate-cloud-control-smtp \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/smtp.env
# Enable it in the environment values:
#   config:
#     notifications:
#       emailEnabled: "true"
#       smtpHost: email-smtp.ap-southeast-1.amazonaws.com
#       smtpPort: "587"          # STARTTLS
#       smtpStarttls: "true"
#       smtpSslEnable: "false"
#       emailFrom: no-reply@tapstate.com
# For implicit TLS on 465 set smtpStarttls: "false" and smtpSslEnable: "true".
# The control-plane egress policy must allow the chosen SMTP port, and the
# sending domain must be authenticated (SPF/DKIM) before production use.
# After changing the Secret or values, restart the Control Plane so the
# environment-backed values reload. Alert delivery status is visible in
# Console -> Deliveries; failures are not reported as sent.

# ---- Shared sample database password ----
# Keep this file outside the repository with owner-only permissions.
# The Secret must be in the same namespace as the Control Plane release.
# Local installs use tapstate-cloud-control; awsdev uses the tapstate namespace.
kubectl create secret generic tapstate-cloud-control-sample-sources \
  -n tapstate-cloud-control \
  --from-file=password=/secure/path/sample-database-password

# For awsdev, create the same Secret in tapstate instead:
kubectl create secret generic tapstate-cloud-control-sample-sources \
  -n tapstate \
  --from-file=password=/secure/path/sample-database-password

# This Secret is optional: without it, Cluster Consoles hide the sample-source entry.
# After creating or rotating it, restart the Control Plane so its environment refreshes.
kubectl rollout restart deployment/tapstate-cloud-control -n tapstate-cloud-control
# For awsdev, restart it in the tapstate namespace instead.
kubectl rollout restart deployment/tapstate-cloud-control -n tapstate

# 3. Helm install (local k3s dev)
helm install tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-local.yaml \
  -n tapstate-cloud-control \
  --namespace-create

# 3b. Helm install (AWS production k3s)
helm install tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-awsprod.yaml \
  -n tapstate-cloud-control

# 4. Verify
kubectl get pods -n tapstate-cloud-control
kubectl get svc -n tapstate-cloud-control
curl http://localhost:30180/health

# 5. Upgrade
helm upgrade tapstate-cloud-control ./helm/tapstate-cloud-control \
  -f helm/tapstate-cloud-control/values-awsprod.yaml \
  -n tapstate-cloud-control
