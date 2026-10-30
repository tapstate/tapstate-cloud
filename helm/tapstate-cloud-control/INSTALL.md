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

# ---- Invited email registration and OAuth (optional) ----
# Commands below use the local/production namespace. For awsdev, create each
# Secret in the tapstate namespace instead.
# Keep the OTP HMAC key stable across restarts. Rotating it invalidates any
# outstanding email verification codes. Use a persistent value per environment;
# do not put it in Helm values or source control.
kubectl create secret generic tapstate-cloud-control-auth \
  -n tapstate-cloud-control \
  --from-literal=otp-hmac-key="$(openssl rand -hex 32)"

# Google and GitHub OAuth app credentials are optional independently. Create
# these Secrets only for configured providers. Register each callback URL in
# the provider application:
#   production: https://cloud.tapstate.com/login/oauth2/code/google
#               https://cloud.tapstate.com/login/oauth2/code/github
#   awsdev:     https://dev.cloud.tapstate.com/login/oauth2/code/google
#               https://dev.cloud.tapstate.com/login/oauth2/code/github
kubectl create secret generic tapstate-cloud-control-google-oauth \
  -n tapstate-cloud-control \
  --from-literal=client-id='<Google OAuth client ID>' \
  --from-literal=client-secret='<Google OAuth client secret>'
kubectl create secret generic tapstate-cloud-control-github-oauth \
  -n tapstate-cloud-control \
  --from-literal=client-id='<GitHub OAuth client ID>' \
  --from-literal=client-secret='<GitHub OAuth client secret>'

# SMTP credentials enable verification-code delivery. SMTP port, authentication,
# STARTTLS, sender address, OAuth callback base URL and Console redirect URL are
# read from the environment-specific Helm values.
kubectl create secret generic tapstate-cloud-control-smtp \
  -n tapstate-cloud-control \
  --from-literal=host='<SMTP host>' \
  --from-literal=username='<SMTP username>' \
  --from-literal=password='<SMTP password>'

# ---- Limited trial access ----
# Set config.trialIssuerEmail to an existing Cloud account's exact email in
# each environment's Helm values, or pass --set-string config.trialIssuerEmail=<email>
# to helm upgrade. The value is an account identifier, not an OAuth secret.
# Set config.authTrialCodeRequired=false later to allow email-verified signup
# without a trial code. New users still create their own Organization.
# With an empty value, trial-code issuance is disabled. Production needs its
# own issuer setting and its own Google/GitHub callbacks and SMTP configuration.
# Sign in to that environment's Cloud Console as the issuer, then issue one code
# per tester from the browser's developer console:
#   fetch('/v1/api/trial-codes', { method: 'POST', credentials: 'include' })
#     .then(response => response.json()).then(result => console.log(result.data))
# The response contains a one-time code and a ready-to-share /auth#invite= link.
# Store or send it immediately: only its hash is retained, and it expires in
# seven days. The tester may paste the code on /auth or follow the link.
# ---- Optional: Tawk to Feishu feedback notifications ----
# ⚠️ Breaking change (PR #154): this release replaces the old Feishu custom
# webhook bot (FEISHU_BOT_WEBHOOK_URL + FEISHU_BOT_SIGNING_SECRET) with the
# Feishu application bot below. If you previously configured a webhook secret
# and env vars, delete those first, then create the new feishu-bot Secret.
# All deployment env vars have optional: true, so the Control Plane stays up
# after upgrade — but notifications will silently stop until the new Secret
# is created.
#
# Create one Tawk webhook in each environment's existing Property. Use the
# environment's HTTPS callback URL and select Chat Start and New Ticket. Keep
# both env files outside Git with owner-only permissions; never put their values
# in shell history.
# tawk-webhook.env contains webhook-secret=<Tawk webhook signing secret>.
# feishu-bot.env contains app-id=<existing Feedback app ID>,
# app-secret=<existing Feedback app secret>, chat-id=<notification group ID>,
# event-encrypt-key=<Feishu callback encryption key>,
# event-verification-token=<Feishu callback verification token>,
# invite-allowed-chat-id=<trial command group ID>, and
# production-invite-shared-secret=<shared dev/production HMAC key>.
# Generate the relay key once and store the same value in both environments;
# never put it in Helm values, shell history, or this repository.
# The existing app bot must remain enabled, have im:message:send_as_bot, and be
# a member of the configured group.
kubectl create secret generic tapstate-cloud-control-tawk-webhook \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/tawk-webhook.env
kubectl create secret generic tapstate-cloud-control-feishu-bot \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/feishu-bot.env

# For awsdev, create the same Secrets in the tapstate namespace instead.
# The expected Tawk Property ID is selected by values-awsdev.yaml / values-awsprod.yaml.
# The application bot stays the same when the destination group changes. Add it
# to the new group, update chat-id in this environment's feishu-bot.env, then
# reapply the same Secret:
kubectl create secret generic tapstate-cloud-control-feishu-bot \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/feishu-bot.env \
  --dry-run=client -o yaml | kubectl apply -f -
# For awsdev, use namespace tapstate and its own feishu-bot.env. Restart the
# Control Plane after the update so the environment-backed Secret values reload.

# ---- Optional: SMTP for auth and alert email ----
# One Secret is shared by auth verification and alert email. It holds the relay
# host plus the SMTP username/password.
# smtp.env contains host=<smtp host>, username=<smtp user>,
# password=<smtp password>.
kubectl create secret generic tapstate-cloud-control-smtp \
  -n tapstate-cloud-control \
  --from-env-file=/secure/path/smtp.env
# Transport is shared through config.smtp*; alert delivery only needs its own
# opt-in switch and sender:
#   config:
#     smtpPort: "587"
#     smtpAuth: "true"
#     smtpStarttls: "true"
#     smtpSslEnable: "false"
#     authMailFrom: no-reply@tapstate.com
#     notifications:
#       emailEnabled: "true"
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

# ---- Optional: Jira Service Management ticket integration ----
# The regular service account needs access to the configured service desk and request type.
# Store the OAuth client ID and secret in protected files outside the repository.
kubectl create secret generic tapstate-cloud-control-jira \
  -n tapstate-cloud-control \
  --from-file=client-id=/secure/path/jira-client-id \
  --from-file=client-secret=/secure/path/jira-client-secret

# Customer auto-provisioning is disabled by default. If enabled, use a separate Jira
# Administrator service account. This grants access beyond the target service desk;
# restrict and audit the credential and ensure it administers the target service desk.
kubectl create secret generic tapstate-cloud-control-jira-customer-admin \
  -n tapstate-cloud-control \
  --from-file=client-id=/secure/path/jira-admin-client-id \
  --from-file=client-secret=/secure/path/jira-admin-client-secret
# Set config.jiraCloudId, config.jiraServiceDeskId and config.jiraRequestTypeId.
# Enable only after email verification is active in the Cloud deployment:
#   config.jiraCustomerProvisioningEnabled: "true"
# Set config.jiraCustomerProvisioningSiteAdminRiskAcknowledged to "true" only after
# reviewing the administrator credential's access to the whole Jira site.
# For awsdev, create both Secrets in the tapstate namespace.

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

### Trial invitation bot commands

Mention the bot in the configured group with `生成邀请链接` to receive a dev trial
link. Use `生产环境邀请码签发` or `生成生产环境邀请链接` for a production link.
Both links are single-use and expire after seven days. These commands create a new
trial organization; they do not invite a member into an existing organization.

Configure the application's `im.message.receive_v1` subscription and the scope
`im:message.group_at_msg:readonly`. For callback mode, use the environment's HTTPS
`/v1/api/webhooks/feishu` endpoint. The dev event connection can instead receive
the same event without a public callback. Add `event-encrypt-key`,
`event-verification-token`, `invite-allowed-chat-id`, and
`production-invite-shared-secret` to each environment's existing Feishu Secret
through private env files. The allowed chat ID limits issuance to the intended
group; every human member can use the commands. Bot issuance records its own
service identity and does not require a trial issuer email.

The dev deployment is the only long-connection consumer and sets
`FEISHU_PRODUCTION_INVITE_API_URL=https://cloud.tapstate.com/v1/api/webhooks/feishu/production-invite`.
The production deployment keeps `config.feishuLongConnectionEnabled=false` and
verifies signed relay requests against the same `production-invite-shared-secret`,
freshness window, and allowlisted chat before writing into production MongoDB.
The URL comes from `config.cloudConsoleUrl`, so the returned link uses the
production Console domain. A production relay failure never falls back to a dev
link.

Keep `event-encrypt-key` stable: it derives retry-stable dev invitation codes.
Production codes use the separate relay HMAC key. Callback requests require a
valid Feishu signature and a timestamp within five minutes; URL verification
uses the same signed callback. No test message is sent on startup.

### Feishu outbound event connection

Set `config.feishuLongConnectionEnabled=true` only in the dev environment for
the existing Feedback application. Configure long connection mode and subscribe
to `im.message.receive_v1` in Feishu, then publish the application version. The
application needs group mention receive permission and bot send permission.
Feishu distributes events across connections of the same application rather
than broadcasting, so production handles invitation issuance through the signed
relay endpoint and does not start a second connection.

Bot trial issuance does not need `config.trialIssuerEmail` or a real mailbox.
It records `feishu-bot` as its source. Human trial issuance retains its existing
issuer authorization. The allowed chat Secret and dev event-encryption key are
required. Production relay issuance also requires the dedicated shared HMAC key;
do not rotate either key casually, because that changes retry-stable tokens.
No inbound public callback is needed when using the outbound connection.
