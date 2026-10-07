package io.tapstate.cloud.mdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.MongoException;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import io.tapstate.cloud.atlas.AtlasApiClient;
import io.tapstate.cloud.atlas.AtlasApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atlas-backed MDB service — allocates databases out of pre-administered shared
 * Atlas clusters ({@link MdbCluster}).
 * <p>
 * PO decisions incorporated:
 * <ul>
 *   <li>API Key credentials moved to {@link AtlasConfig} — every Atlas Admin API
 *       call first looks up {@code status = "Activated"} key</li>
 *   <li>Database name: {@code mdb_{clusterId.toHexString()}} (24-char full ObjectId hex)</li>
 *   <li>operator db: {@code mdb_{clusterId}_operator}</li>
 *   <li>views db: {@code mdb_{clusterId}_views}</li>
 *   <li>dbUser roles: {@code dbAdmin} + {@code readWrite} on all three databases
 *       + clusterMonitor@admin + read@config + read@local. readWrite is mandatory:
 *       dbAdmin alone grants DDL/admin actions but no data read or insert, so the
 *       runtime cannot boot (Atlas rejects its migration writes as unauthorized)</li>
 *   <li>dbAdmin role can drop its own databases → no separate admin dbUser needed</li>
 * </ul>
 * <p>
 * Slot reservation (createMdb): least-loaded ACTIVE MdbCluster for the region,
 * {@code findAndModify(currentAllocations < maxAllocations)} → optimistic concurrency.
 * Up to 3 region-match retries + fallback to any ACTIVE cluster.
 * <p>
 * Production flow: createMdb (reserve slot) → createDatabase (verify cluster reachability)
 * → createDatabaseUser (Atlas Admin API POST /databaseUsers) → markReady →
 * drop path: dropDatabase (authenticated as the dbUser) → dropDatabaseUser →
 * releaseClusterSlot. The order is mandatory: deleting the user first would
 * make the database-drop connection fail authentication and orphan the databases.
 * <p>
 * Atlas Admin API calls live in {@code prod} profile ({@code mockMode=false}).
 * IT profile defaults to {@code mockMode=true} — no real HTTP calls, but slot
 * reservation + capacity counter + state-machine transitions are exercised.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AtlasMdbService implements MdbService {

    private final MdbClusterRepository mdbClusterRepo;
    private final AtlasConfigRepository atlasConfigRepo;
    private final MdbAllocationRepository allocationRepo;
    private final MongoTemplate mongoTemplate;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Set via application.properties — true for IT profile, false for production. */
    @Value("${tapstate.cloud.mdb.atlas.mock:true}")
    private boolean mockMode;

    // ========== createMdb — pick + reserve Atlas Cluster slot ==========

    @Override
    @Transactional
    public MdbCluster createMdb(String orgId, String clusterId, String region) {
        // Retry loop: up to 3 attempts to find + atomically reserve a slot
        for (int attempt = 0; attempt < 3; attempt++) {
            List<MdbCluster> candidates = mdbClusterRepo
                .findByStatusAndRegionOrderByCurrentAllocationsAsc("ACTIVE", region);

            if (candidates.isEmpty()) {
                // Region mismatch — fall back to any ACTIVE instance (dev/IT single-cluster case)
                candidates = mdbClusterRepo.findByStatusOrderByCurrentAllocationsAsc("ACTIVE");
            }

            if (candidates.isEmpty()) {
                throw new MdbException("No ACTIVE MdbCluster available (region=" + region + ")");
            }

            // Try to reserve the least-loaded candidate with capacity
            for (MdbCluster candidate : candidates) {
                if (candidate.getCurrentAllocations() >= candidate.getMaxAllocations()) {
                    continue; // full
                }

                // findAndModify: currentAllocations < maxAllocations → currentAllocations++
                Query query = Query.query(Criteria.where("_id").is(candidate.getId())
                    .and("currentAllocations").lt(candidate.getMaxAllocations()));
                Update update = Update.update("currentAllocations", candidate.getCurrentAllocations() + 1)
                    .set("updatedAt", Instant.now());

                MdbCluster reserved = mongoTemplate.findAndModify(
                    query, update, FindAndModifyOptions.options().returnNew(true),
                    MdbCluster.class);

                if (reserved != null) {
                    log.info("[AtlasMdb] Reserved slot on MdbCluster {} (region={}), capacity {}/{}",
                        reserved.getId(), reserved.getRegion(),
                        reserved.getCurrentAllocations(), reserved.getMaxAllocations());
                    return reserved;
                }
                // Another agent grabbed the slot between read and write — loop tries next candidate / retry
            }
        }

        throw new MdbException("All ACTIVE MdbCluster at capacity for region=" + region);
    }

    // ========== createDatabase — verify cluster reachability ==========

    @Override
    @Transactional
    public void createDatabase(MdbAllocation allocation) {
        allocation.setStatus(MdbAllocationStatus.PROVISIONING);
        allocation.setUpdatedAt(Instant.now());
        allocationRepo.save(allocation);

        if (mockMode) {
            log.info("[AtlasMdb] mockMode: skipping Atlas createDatabase for allocation {} (db={})",
                allocation.getId(), allocation.getDatabaseName());
            return;
        }

        // Atlas has no explicit create-db endpoint — databases materialise on the
        // first successful write. What we CAN verify here is that the target
        // MdbCluster is reachable + IDLE before we hand it to the allocation.
        try {
            MdbCluster cluster = mdbClusterRepo.findById(allocation.getMdbClusterId())
                .orElseThrow(() -> new MdbException("MdbCluster " + allocation.getMdbClusterId() + " not found"));

            AtlasApiClient atlas = newAtlasClient();
            JsonNode clusterJson = atlas.queryCluster(cluster.getProjectId(), cluster.getName());
            if (clusterJson == null) {
                throw new MdbException("Atlas MdbCluster not found on Admin API: project="
                    + cluster.getProjectId() + " name=" + cluster.getName());
            }

            String state = clusterJson.path("stateName").asText();
            if (!"IDLE".equals(state)) {
                throw new MdbException("Atlas MdbCluster " + cluster.getName() + " state=" + state
                    + " — allocation blocked until state returns to IDLE");
            }

            log.info("[AtlasMdb] cluster {} reachable, state=IDLE — databases {} / {} / {} will materialise on first write",
                cluster.getName(), allocation.getDatabaseName(),
                allocation.getOperatorDbName(), allocation.getViewsDbName());

        } catch (MdbException e) {
            throw e;
        } catch (Exception e) {
            throw new MdbException("Atlas cluster reachability check failed for allocation "
                + allocation.getId() + ": " + e.getMessage(), e);
        }
    }

    // ========== createDatabaseUser — Atlas Admin API POST /databaseUsers ==========

    @Override
    @Transactional
    public void createDatabaseUser(MdbAllocation allocation) {
        if (mockMode) {
            log.info("[AtlasMdb] mockMode: skipping Atlas createDatabaseUser for allocation {}", allocation.getId());
            markReady(allocation);
            return;
        }

        try {
            MdbCluster cluster = mdbClusterRepo.findById(allocation.getMdbClusterId())
                .orElseThrow(() -> new MdbException("MdbCluster " + allocation.getMdbClusterId() + " not found"));

            AtlasApiClient atlas = newAtlasClient();
            List<Map<String, String>> roles = buildDbUserRoles(allocation);

            var result = atlas.createDatabaseUser(
                cluster.getProjectId(),
                allocation.getDbUsername(),
                allocation.getDbPassword(),
                roles);

            log.info("[AtlasMdb] AtlasApiClient.createDatabaseUser → createdNew={}, alreadyExists={}, alloc={}, cluster={}",
                result.createdNew(), result.alreadyExists(), allocation.getId(), cluster.getName());

            markReady(allocation);
        } catch (AtlasApiException e) {
            allocation.setProvisionErrorCode("ATLAS_DBUSER_CREATE_FAILED");
            allocation.setProvisionErrorDetail(e.errorCode() + ": " + e.getMessage());
            throw new MdbException("Atlas createDatabaseUser failed for allocation " + allocation.getId(), e);
        } catch (MdbException e) {
            allocation.setProvisionErrorCode("ATLAS_DBUSER_CREATE_FAILED");
            allocation.setProvisionErrorDetail(e.getMessage());
            throw e;
        }
    }

    /**
     * Build the role set for an allocation owner: dbAdmin + readWrite on each of
     * the three databases, plus clusterMonitor@admin + read@config + read@local.
     * <p>
     * Both data roles are required on every database:
     * <ul>
     *   <li>readWrite — runtime data and migration writes. dbAdmin deliberately
     *       excludes read/insert/update/delete privileges; without readWrite the
     *       runtime's bootstrap migrations fail as not authorized and the node
     *       never becomes healthy.</li>
     *   <li>dbAdmin — administrative actions and, on reclaim, dropDatabase on the
     *       allocation's own databases (readWrite cannot drop a database).</li>
     * </ul>
     */
    static List<Map<String, String>> buildDbUserRoles(MdbAllocation allocation) {
        List<Map<String, String>> roles = new ArrayList<>(9);

        for (String databaseName : new String[] {
            allocation.getDatabaseName(),
            allocation.getOperatorDbName(),
            allocation.getViewsDbName()
        }) {
            roles.add(role("dbAdmin", databaseName));
            roles.add(role("readWrite", databaseName));
        }

        // system-level read/monitor roles — Atlas scopes these to admin / config / local
        roles.add(role("clusterMonitor", "admin"));
        roles.add(role("read", "config"));
        roles.add(role("read", "local"));

        return roles;
    }

    private static Map<String, String> role(String roleName, String databaseName) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("roleName", roleName);
        r.put("databaseName", databaseName);
        return r;
    }

    private void markReady(MdbAllocation allocation) {
        allocation.setStatus(MdbAllocationStatus.READY);
        allocation.setReadyAt(Instant.now());
        allocation.setUpdatedAt(Instant.now());
        allocationRepo.save(allocation);
        log.info("[AtlasMdb] Allocation {} READY — db={}, operatorDb={}, viewsDb={}, user={}",
            allocation.getId(), allocation.getDatabaseName(),
            allocation.getOperatorDbName(), allocation.getViewsDbName(),
            allocation.getDbUsername());
    }

    // ========== dropDatabaseUser — Atlas Admin API DELETE /databaseUsers ==========

    @Override
    @Transactional
    public void dropDatabaseUser(MdbAllocation allocation) {
        if (mockMode) {
            log.info("[AtlasMdb] mockMode: skipping Atlas dropDatabaseUser for allocation {}", allocation.getId());
            return;
        }

        try {
            MdbCluster cluster = mdbClusterRepo.findById(allocation.getMdbClusterId())
                .orElseThrow(() -> new MdbException("MdbCluster " + allocation.getMdbClusterId() + " not found"));

            AtlasApiClient atlas = newAtlasClient();
            atlas.dropDatabaseUser(cluster.getProjectId(), allocation.getDbUsername());

            log.info("[AtlasMdb] AtlasApiClient.dropDatabaseUser OK — alloc {}, dbUser={}, cluster={}",
                allocation.getId(), allocation.getDbUsername(), cluster.getName());

        } catch (AtlasApiException e) {
            // drop on reclaim is best-effort — log and continue teardown
            log.warn("[AtlasMdb] AtlasApiClient.dropDatabaseUser failed for alloc {}: {}: {}",
                allocation.getId(), e.errorCode(), e.getMessage());
        } catch (Exception e) {
            log.warn("[AtlasMdb] dropDatabaseUser unexpected error for alloc {}: {}",
                allocation.getId(), e.getMessage());
        }
    }

    // ========== dropDatabase — connect as per-allocation dbUser, drop 3 dbs ==========
    //
    // Atlas Admin API has NO drop-database endpoint — drop is a Mongo-level operation.
    // PO decision: the dbUser's dbAdmin role on its three databases lets it drop
    // them directly. We build a short-lived MongoClient using the allocation's
    // dbUser credentials + the clean connectionString stored on MdbCluster.
    //
    // Connection string assembly:
    //   mongodb+srv://username:password@cluster0.xxx.mongodb.net/?authSource=admin
    //   (roles are scoped with authSource=admin — same as createDatabaseUser)

    @Override
    @Transactional
    public void dropDatabase(MdbAllocation allocation) {
        if (mockMode) {
            log.info("[AtlasMdb] mockMode: skipping Atlas dropDatabase for allocation {}", allocation.getId());
            releaseClusterSlot(allocation);
            return;
        }

        MdbCluster cluster = mdbClusterRepo.findById(allocation.getMdbClusterId())
            .orElseThrow(() -> new MdbException("MdbCluster " + allocation.getMdbClusterId() + " not found"));

        String connUri = buildAtlasMongoUri(cluster.getConnectionString(),
            allocation.getDbUsername(), allocation.getDbPassword());

        try (MongoClient mongo = MongoClients.create(connUri)) {
            // Verify auth works — MongoDatabase admin is scoped for authSource
            mongo.getDatabase("admin").runCommand(new org.bson.Document("ping", 1));

            dropOneDatabase(mongo, allocation.getDatabaseName());
            dropOneDatabase(mongo, allocation.getOperatorDbName());
            dropOneDatabase(mongo, allocation.getViewsDbName());

            log.info("[AtlasMdb] dropDatabase OK — alloc {}, cluster {}, dropped: {}, {}, {}",
                allocation.getId(), cluster.getName(),
                allocation.getDatabaseName(), allocation.getOperatorDbName(), allocation.getViewsDbName());

        } catch (MongoException e) {
            log.warn("[AtlasMdb] dropDatabase MongoClient failure for alloc {}: {}",
                allocation.getId(), e.getMessage());
        } catch (Exception e) {
            log.warn("[AtlasMdb] dropDatabase unexpected error for alloc {}: {}",
                allocation.getId(), e.getMessage());
        } finally {
            // MongoClient.close() is called automatically by try-with-resources
            releaseClusterSlot(allocation);
        }
    }

    /**
     * Drop a single database — idempotent: if the db doesn't exist,
     * Atlas returns a "namespaces not found" error which we treat as success.
     */
    private void dropOneDatabase(MongoClient mongo, String dbName) {
        try {
            MongoDatabase db = mongo.getDatabase(dbName);
            db.drop();
            log.debug("[AtlasMdb] dropped database: {}", dbName);
        } catch (MongoException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("not found") || msg.contains("does not exist") || msg.contains("namespaces")) {
                log.debug("[AtlasMdb] database already gone (idempotent): {}", dbName);
            } else {
                throw e; // real error — propagate
            }
        }
    }

    /**
     * Build an Atlas MongoDB connection URI from a clean connection string
     * plus dbUser credentials. Atlas connection strings come in two flavors:
     * <pre>
     *   mongodb+srv://cluster0.xxx.mongodb.net
     *   mongodb://host1:27017,host2:27017
     * </pre>
     * Both are converted to {@code scheme://username:password@host[?authSource=admin]}.
     * Auth source is always {@code admin} — that's where Atlas Admin API
     * created the dbUser.
     */
    static String buildAtlasMongoUri(String cleanConnStr, String username, String password) {
        if (cleanConnStr == null || cleanConnStr.isBlank()) {
            throw new MdbException("MdbCluster.connectionString is null — admin must seed it from Atlas Admin API QueryOneCluster");
        }

        // Parse scheme + authority (mongodb+srv://host or mongodb://host1:port,host2:port)
        // mongodb+srv://cluster0.xxx.mongodb.net  →  mongodb+srv://user:pass@cluster0.xxx.mongodb.net
        // mongodb://host1:27017,host2:27017      →  mongodb://user:pass@host1:27017,host2:27017
        int schemeEnd = cleanConnStr.indexOf("://");
        if (schemeEnd < 0) {
            throw new MdbException("Invalid connection string (no scheme): " + cleanConnStr);
        }
        String scheme = cleanConnStr.substring(0, schemeEnd + 3);
        String rest = cleanConnStr.substring(schemeEnd + 3);

        // Insert credentials right after scheme, before host/port
        // Special chars in password must be percent-encoded for URI safety
        String safeUser = uriEncode(username);
        String safePass = uriEncode(password);

        // Strip any existing query/fragment from the host part — we'll append authSource=admin
        String hostPart;
        int qIdx = rest.indexOf('?');
        if (qIdx >= 0) {
            hostPart = rest.substring(0, qIdx);
        } else {
            hostPart = rest;
        }

        // authSource=admin is mandatory — Atlas scopes authUser under admin db
        return scheme + safeUser + ":" + safePass + "@" + hostPart + "/?authSource=admin";
    }

    /** Percent-encode per-URI-component. Username/password may contain chars like @ : / ? that break the URI. */
    private static String uriEncode(String raw) {
        try {
            return java.net.URLEncoder.encode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return raw; // unlikely — UTF-8 is always available
        }
    }

    /** Decrement currentAllocations on the parent MdbCluster — slot released. */
    private void releaseClusterSlot(MdbAllocation allocation) {
        mdbClusterRepo.findById(allocation.getMdbClusterId()).ifPresent(cluster -> {
            if (cluster.getCurrentAllocations() > 0) {
                // findAndModify to decrement atomically
                Query q = Query.query(Criteria.where("_id").is(cluster.getId())
                    .and("currentAllocations").gt(0));
                Update u = Update.update("currentAllocations", cluster.getCurrentAllocations() - 1)
                    .set("updatedAt", Instant.now());
                MdbCluster released = mongoTemplate.findAndModify(
                    q, u, FindAndModifyOptions.options().returnNew(true), MdbCluster.class);
                if (released != null) {
                    log.info("[AtlasMdb] Released slot on MdbCluster {} — now {}/{}",
                        released.getId(), released.getCurrentAllocations(), released.getMaxAllocations());
                } else {
                    log.warn("[AtlasMdb] Slot decrement race lost for MdbCluster {} — currentAllocations > 0 check failed",
                        cluster.getId());
                }
            } else {
                log.warn("[AtlasMdb] MdbCluster {} already at 0 allocations — slot release is a no-op",
                    cluster.getId());
            }
        });
    }

    // ========== Helpers ==========

    /**
     * Look up the currently activated Atlas API key from {@link AtlasConfigRepository}
     * and build an {@link AtlasApiClient} pointed at Atlas Cloud.
     * AtlasAdmin API keys can be rotated — always read the latest Activated one.
     */
    private AtlasApiClient newAtlasClient() {
        AtlasConfig cfg = atlasConfigRepo.findTopByStatusOrderByUpdatedAtDesc("Activated")
            .orElseThrow(() -> new MdbException("NO_ACTIVATED_ATLAS_CONFIG — admin must seed one"));
        return new AtlasApiClient(cfg.getPublicKey(), cfg.getPrivateKey());
    }

    /**
     * Generate a unique per-allocation database name — PO decision: 24-char full Cluster ObjectId hex.
     */
    public String generateDatabaseName(String clusterId) {
        String hex;
        try {
            hex = new ObjectId(clusterId).toHexString();
        } catch (IllegalArgumentException e) {
            // Fallback for dev/mock paths where clusterId is not an ObjectId
            hex = clusterId.replaceAll("[^a-fA-F0-9]", "").substring(0, Math.min(24, clusterId.replaceAll("[^a-fA-F0-9]", "").length()));
            hex = (hex + "000000000000000000000000").substring(0, 24);
        }
        return "mdb_" + hex;
    }

    /** Generate operator db name: {@code mdb_{clusterId}_operator}. */
    public String generateOperatorDbName(String databaseName) {
        return databaseName + "_operator";
    }

    /** Generate views db name: {@code mdb_{clusterId}_views}. */
    public String generateViewsDbName(String databaseName) {
        return databaseName + "_views";
    }

    /** Generate per-allocation Atlas db username. Format: owner_{databaseName}, ≤ 64 chars. */
    public String generateDatabaseUsername(String databaseName) {
        String name = "owner_" + databaseName;
        return name.length() > 64 ? name.substring(0, 64) : name;
    }

    /** Generate a 32-byte random password → 48-char hex. */
    public String generateDatabasePassword() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
