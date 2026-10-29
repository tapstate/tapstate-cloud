#!/usr/bin/env bash
# ======================================================================
# Tapstate Cloud — Production MongoDB Seed Script
#
# Seeds the two MongoDB documents that the prod profile does NOT
# auto-create (because the dev/IT seeders are @Profile("dev | it") only):
#
#   1. atlas_configs  — Activated Atlas Admin API key
#   2. mdb_clusters   — ACTIVE shared Atlas cluster (ap-southeast-1)
#
# Every other collection (regions, spec_config, cluster_versions,
# sample_source_profiles, indexes, time-series) is created on startup
# by the application itself — you do NOT need this script for those.
#
# Usage:
#
#   # First export the Atlas API key (from Atlas → Access → API Keys,
#   # Project Owner role on the shared MDB project):
#   export ATLAS_PUBLIC_KEY="abc123..."
#   export ATLAS_PRIVATE_KEY="..."
#   export ATLAS_ORG_ID="64082d7893e9d409965ecf32"
#   export ATLAS_PROJECT_ID="pv420x"       # groupId of the shared MDB project
#   export ATLAS_CLUSTER_NAME="tapstate-shared-ap-southeast-1"
#
#   # Then run (uses MONGODB_URI env var already in your shell,
#   # or export it explicitly if needed):
#   export MONGODB_URI='mongodb+srv://cloud-control-user:***@.../tapstate_control'
#   bash scripts/prod-mongo-seed.sh
#
#   # Or pass everything on the command line via the seed manifest
#   # (copy manifest.example.json → manifest.json, fill in values, then):
#   bash scripts/prod-mongo-seed.sh --manifest manifest.json
#
# Prerequisites:
#   - mongosh (MongoDB 6.0+ shell — the legacy mongo binary is NOT used)
#   - The Control Plane MongoDB already exists (created by Atlas cluster setup)
#   - Control plane user has readWrite on tapstate_control DB
#   - The Atlas Admin API key must already exist in Atlas
# ======================================================================
set -euo pipefail
IFS=$'\n\t'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MANIFEST=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --manifest)
      MANIFEST="$2"; shift 2 ;;
    *)
      echo "Unknown arg: $1"; exit 1 ;;
  esac
done

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "ERROR: $1 is required but not found"; exit 1; }
}

require_cmd mongosh

# ----------------------------------------------------------------------
# Resolve values — manifest.json takes precedence, fall back to env vars
# ----------------------------------------------------------------------
if [ -n "$MANIFEST" ] && [ -f "$MANIFEST" ]; then
  echo "📋 Reading manifest: $MANIFEST"
  ATLAS_PUBLIC_KEY=$(mongosh --quiet "$MONGODB_URI" --eval "JSON.parse(require('fs').readFileSync('$MANIFEST','utf8')).atlasPublicKey" 2>/dev/null \
    || python3 -c "import json; print(json.load(open('$MANIFEST'))['atlasPublicKey'])")
  ATLAS_PRIVATE_KEY=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['atlasPrivateKey'])")
  ATLAS_ORG_ID=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['atlasOrgId'])")
  ATLAS_PROJECT_ID=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['atlasProjectId'])")
  ATLAS_CLUSTER_NAME=$(python3 -c "import json; print(json.load(open('$MANIFEST'))['atlasClusterName'])")
  ATLAS_CONNECTION_STRING=$(python3 -c "import json; print(json.load(open('$MANIFEST')).get('atlasConnectionString',''))")
  MDB_MAX_ALLOCATIONS=$(python3 -c "import json; print(json.load(open('$MANIFEST')).get('mdbMaxAllocations', 100))")
  MDB_REGION=$(python3 -c "import json; print(json.load(open('$MANIFEST')).get('mdbRegion', 'ap-southeast-1'))")
  MONGODB_URI_SEED=$(python3 -c "import json; print(json.load(open('$MANIFEST')).get('mongodbControlUri', '$MONGODB_URI'))")
else
  ATLAS_PUBLIC_KEY="${ATLAS_PUBLIC_KEY:-}"
  ATLAS_PRIVATE_KEY="${ATLAS_PRIVATE_KEY:-}"
  ATLAS_ORG_ID="${ATLAS_ORG_ID:-}"
  ATLAS_PROJECT_ID="${ATLAS_PROJECT_ID:-}"
  ATLAS_CLUSTER_NAME="${ATLAS_CLUSTER_NAME:-tapstate-shared-ap-southeast-1}"
  ATLAS_CONNECTION_STRING="${ATLAS_CONNECTION_STRING:-}"
  MDB_MAX_ALLOCATIONS="${MDB_MAX_ALLOCATIONS:-100}"
  MDB_REGION="${MDB_REGION:-ap-southeast-1}"
  MONGODB_URI_SEED="${MONGODB_URI:-}"
fi

# ----------------------------------------------------------------------
# Validation
# ----------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  Tapstate Cloud — awsprod MongoDB Seed"
echo "=============================================="

errors=0
[ -z "$MONGODB_URI_SEED" ] && { echo "✗ MONGODB_URI is not set"; errors=$((errors+1)); }
[ -z "$ATLAS_PUBLIC_KEY" ] && { echo "✗ ATLAS_PUBLIC_KEY is not set"; errors=$((errors+1)); }
[ -z "$ATLAS_PRIVATE_KEY" ] && { echo "✗ ATLAS_PRIVATE_KEY is not set"; errors=$((errors+1)); }
[ -z "$ATLAS_ORG_ID" ] && { echo "✗ ATLAS_ORG_ID is not set"; errors=$((errors+1)); }
[ -z "$ATLAS_PROJECT_ID" ] && { echo "✗ ATLAS_PROJECT_ID is not set"; errors=$((errors+1)); }

if [ $errors -gt 0 ]; then
  echo ""
  echo "Please set all required values and retry."
  echo "  export MONGODB_URI='mongodb+srv://cloud-control-user:***@.../tapstate_control'"
  echo "  export ATLAS_PUBLIC_KEY='...'"
  echo "  export ATLAS_PRIVATE_KEY='...'"
  echo "  export ATLAS_ORG_ID='...'"
  echo "  export ATLAS_PROJECT_ID='...'"
  echo "  export ATLAS_CLUSTER_NAME='tapstate-shared-ap-southeast-1'"
  exit 1
fi

echo ""
echo "→ Control Plane DB URI:   ${MONGODB_URI_SEED//:\/\/*/:***@...}"
echo "→ Atlas Org ID:           $ATLAS_ORG_ID"
echo "→ Atlas Project ID:       $ATLAS_PROJECT_ID"
echo "→ Atlas Cluster:          $ATLAS_CLUSTER_NAME"
echo "→ Atlas Region:           $MDB_REGION"
echo "→ MDB Max Allocations:    $MDB_MAX_ALLOCATIONS"
echo ""

# ----------------------------------------------------------------------
# Step 1: Verify DB connectivity
# ----------------------------------------------------------------------
echo "──────────────────────────────────────────────"
echo " 1. Verify MongoDB connectivity"
echo "──────────────────────────────────────────────"

if ! mongosh --quiet "$MONGODB_URI_SEED" --eval "db.runCommand({ping:1}).ok" 2>&1; then
  echo "✗ Cannot connect to MongoDB — check MONGODB_URI"
  exit 1
fi
echo "✓ MongoDB connected"

# ----------------------------------------------------------------------
# Step 2: Ensure collection indexes exist (MongoInitListener 会自动做，
#   但 seed 脚本也可以在应用启动前手动确保 partialFilterExpression unique)
# ----------------------------------------------------------------------
echo ""
echo "──────────────────────────────────────────────"
echo " 2. Ensure partialFilterExpression index on atlas_configs"
echo "──────────────────────────────────────────────"

# PartialFilterExpression unique index — only ONE Activated key allowed
mongosh --quiet "$MONGODB_URI_SEED" --eval '
db.atlas_configs.createIndex(
  { status: 1 },
  {
    unique: true,
    partialFilterExpression: { status: "Activated" },
    name: "unique_activated_key"
  }
)' 2>&1
echo "✓ Index ensured"

# ----------------------------------------------------------------------
# Step 3: Upsert AtlasConfig (Activated)
# ----------------------------------------------------------------------
echo ""
echo "──────────────────────────────────────────────"
echo " 3. Seed atlas_configs (Activated)"
echo "──────────────────────────────────────────────"

ATLAS_PUBLIC_KEY_JS=$(printf '%s' "$ATLAS_PUBLIC_KEY" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
ATLAS_PRIVATE_KEY_JS=$(printf '%s' "$ATLAS_PRIVATE_KEY" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
ATLAS_ORG_ID_JS=$(printf '%s' "$ATLAS_ORG_ID" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")

mongosh --quiet "$MONGODB_URI_SEED" --eval "
const existing = db.atlas_configs.findOne({ status: 'Activated' });
if (existing) {
  print('ℹ Found Activated AtlasConfig _id=' + existing._id);
  print('ℹ Updating publicKey / privateKey / orgId (other fields preserved)');
  db.atlas_configs.updateOne(
    { _id: existing._id },
    {
      \$set: {
        publicKey: ${ATLAS_PUBLIC_KEY_JS},
        privateKey: ${ATLAS_PRIVATE_KEY_JS},
        orgId: ${ATLAS_ORG_ID_JS},
        updatedAt: new Date()
      }
    }
  );
} else {
  print('➕ Creating new Activated AtlasConfig');
  db.atlas_configs.insertOne({
    publicKey: ${ATLAS_PUBLIC_KEY_JS},
    privateKey: ${ATLAS_PRIVATE_KEY_JS},
    domain: 'cloud.mongodb.com',
    orgId: ${ATLAS_ORG_ID_JS},
    status: 'Activated',
    createdAt: new Date(),
    updatedAt: new Date()
  });
}
const result = db.atlas_configs.findOne({ status: 'Activated' });
print('✅ atlas_configs Activated _id=' + result._id);
" 2>&1

# ----------------------------------------------------------------------
# Step 4: Seed MdbCluster (ACTIVE, ap-southeast-1)
# ----------------------------------------------------------------------
echo ""
echo "──────────────────────────────────────────────"
echo " 4. Seed mdb_clusters (ACTIVE, $MDB_REGION)"
echo "──────────────────────────────────────────────"

ATLAS_PROJECT_ID_JS=$(printf '%s' "$ATLAS_PROJECT_ID" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
ATLAS_CLUSTER_NAME_JS=$(printf '%s' "$ATLAS_CLUSTER_NAME" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
MDB_REGION_JS=$(printf '%s' "$MDB_REGION" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")

# First, fetch the Activated AtlasConfig _id we just wrote
ATLAS_CONFIG_ID=$(mongosh --quiet "$MONGODB_URI_SEED" --eval '
const cfg = db.atlas_configs.findOne({ status: "Activated" });
if (!cfg) { print("ERROR: no Activated AtlasConfig"); }
else { print(cfg._id.str); }
' 2>&1)

echo "  → atlasConfigId: $ATLAS_CONFIG_ID"

CONNECTION_STRING_JS="null"
if [ -n "$ATLAS_CONNECTION_STRING" ]; then
  CONNECTION_STRING_JS=$(printf '%s' "$ATLAS_CONNECTION_STRING" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
fi

mongosh --quiet "$MONGODB_URI_SEED" --eval "
const ATLAS_CONFIG_ID = ObjectId('$ATLAS_CONFIG_ID');
const MDB_REGION = ${MDB_REGION_JS};
const ATLAS_PROJECT_ID = ${ATLAS_PROJECT_ID_JS};
const ATLAS_CLUSTER_NAME = ${ATLAS_CLUSTER_NAME_JS};
const MAX_ALLOCATIONS = $MDB_MAX_ALLOCATIONS;
const CONNECTION_STRING = $CONNECTION_STRING_JS;

const existing = db.mdb_clusters.findOne({ name: ATLAS_CLUSTER_NAME });
if (existing) {
  print('ℹ Found existing MdbCluster _id=' + existing._id + ' status=' + existing.status);
  print('ℹ Updating projectId / region / maxAllocations');
  db.mdb_clusters.updateOne(
    { _id: existing._id },
    {
      \$set: {
        atlasConfigId: ATLAS_CONFIG_ID,
        projectId: ATLAS_PROJECT_ID,
        name: ATLAS_CLUSTER_NAME,
        region: MDB_REGION,
        maxAllocations: MAX_ALLOCATIONS,
        status: 'ACTIVE',
        connectionString: CONNECTION_STRING,
        updatedAt: new Date()
      },
      \$setOnInsert: { currentAllocations: 0 }
    }
  );
} else {
  print('➕ Creating new ACTIVE MdbCluster (region=' + MDB_REGION + ')');
  db.mdb_clusters.insertOne({
    atlasConfigId: ATLAS_CONFIG_ID,
    projectId: ATLAS_PROJECT_ID,
    name: ATLAS_CLUSTER_NAME,
    region: MDB_REGION,
    maxAllocations: MAX_ALLOCATIONS,
    currentAllocations: 0,
    connectionString: CONNECTION_STRING,
    status: 'ACTIVE',
    createdAt: new Date(),
    updatedAt: new Date()
  });
}

// Verify
const result = db.mdb_clusters.findOne({ name: ATLAS_CLUSTER_NAME });
print('✅ mdb_clusters _id=' + result._id + ' status=' + result.status + ' region=' + result.region + ' capacity=' + result.currentAllocations + '/' + result.maxAllocations);
" 2>&1

# ----------------------------------------------------------------------
# Step 5: Quick sanity — what else is already seeded?
# ----------------------------------------------------------------------
echo ""
echo "──────────────────────────────────────────────"
echo " 5. Verify auto-seeded collections exist"
echo "──────────────────────────────────────────────"

mongosh --quiet "$MONGODB_URI_SEED" --eval '
const checks = [
  ["regions", db.regions.countDocuments({})],
  ["spec_config", db.spec_config.countDocuments({})],
  ["cluster_versions", db.cluster_versions.countDocuments({})],
  ["sample_source_profiles", db.sample_source_profiles.countDocuments({})],
  ["counters", db.counters.countDocuments({})]
];
print("ℹ Auto-seeded collection counts:");
for (const [name, count] of checks) {
  const ok = count > 0 ? "✓" : "⚠ empty (will be seeded on app startup)";
  print("  " + ok + " " + name + ": " + count);
}
' 2>&1

# ----------------------------------------------------------------------
# Summary
# ----------------------------------------------------------------------
echo ""
echo "=============================================="
echo "✅ MongoDB seed complete."
echo ""
echo "Manual seed summary:"
echo "  ✓ atlas_configs    — Activated (publicKey orgId updated)"
echo "  ✓ mdb_clusters     — ACTIVE ap-southeast-1 (capacity $MDB_MAX_ALLOCATIONS)"
echo ""
echo "Auto-seeded by app startup (no action needed):"
echo "  • regions              (RegionSeeder)"
echo "  • spec_config          (SpecConfigBootstrap ← spec-config.json)"
echo "  • cluster_versions     (ClusterVersionSeeder @PostConstruct)"
echo "  • sample_source_profiles (SampleSourceProfileSeeder)"
echo "  • counters             (CounterService upsert on first call)"
echo "  • All collections + indexes (MongoInitListener)"
echo "  • Time-series cluster_status_report_history"
echo "  • Stripe products/pricing (StripeCatalogRefreshRunner, optional)"
echo ""
echo "⚠ What STILL needs verification after app starts:"
echo "  1. Atlas Admin API connectivity — the app tests this on first MdbAllocation create()"
echo "  2. Stripe product catalog — if Stripe Secret present, refresh runs on boot"
echo "  3. Application error logs for any seed-related exceptions"
echo "=============================================="
