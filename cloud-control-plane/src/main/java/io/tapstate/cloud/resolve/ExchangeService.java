package io.tapstate.cloud.resolve;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.config.JwksKey;
import io.tapstate.cloud.config.JwksRotationService;
import io.tapstate.cloud.identity.User;
import io.tapstate.cloud.identity.UserRepository;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.Organization;
import io.tapstate.cloud.org.OrganizationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.bson.types.ObjectId;

/**
 * Exchange-code lifecycle (ADR-0125).
 * <ul>
 *   <li>{@link #issueExchangeCode} — called by GC /resolve endpoint; generates a 32-byte
 *       random code, stores SHA-256 hash + binding facts, returns the raw code + base_uri.</li>
 *   <li>{@link #redeemExchangeCode} — called by Cluster Server backend via GC /exchange
 *       endpoint; performs the atomic conditional write that both consumes the code
 *       AND issues the resolve JWT in a single DB round-trip.</li>
 * </ul>
 * <p>
 * Security invariants:
 * <ul>
 *   <li>Raw exchange code NEVER enters the database — only SHA-256 hash is stored.</li>
 *   <li>Redemption is {@code findAndModify} — atomic: unconsumed + unexpired + cluster_id
 *       match → set consumed_at = now → one row affected = success.</li>
 *   <li>Cluster service identity secret is verified separately (in controller) before
 *       this method is reached — the atomic condition only checks DB state.</li>
 * </ul>
 */
@Service
@Slf4j
public class ExchangeService {

    private final ExchangeCodeRepository codeRepo;
    private final MongoTemplate mongoTemplate;
    private final ClusterRepository clusterRepo;
    private final MemberRepository memberRepo;
    private final OrganizationRepository organizationRepo;
    private final UserRepository userRepo;
    private final JtiRecordRepository jtiRecordRepo;
    private final JwksRotationService jwksRotationService;
    private final LocalClusterEndpoints endpoints;

    @Value("${tapstate.cloud.issuer:https://cloud.tapstate.io}")
    private String issuer;

    @Value("${tapstate.cloud.resolve-jwt-expiry-minutes:15}")
    private int jwtExpiryMinutes;

    /** Exchange code TTL — 60 seconds (ADR-0125 D2). */
    private static final int EXCHANGE_CODE_TTL_SECONDS = 60;

    /** In-memory per-Cluster service identity secrets — Week 2 scaffold; production: k3s Secret. */
    private final Map<String, String> clusterServiceSecrets = new java.util.concurrent.ConcurrentHashMap<>();

    private final SecureRandom secureRandom = new SecureRandom();

    public ExchangeService(ExchangeCodeRepository codeRepo,
                           MongoTemplate mongoTemplate,
                           ClusterRepository clusterRepo,
                           MemberRepository memberRepo,
                           OrganizationRepository organizationRepo,
                           UserRepository userRepo,
                           JtiRecordRepository jtiRecordRepo,
                           JwksRotationService jwksRotationService,
                           LocalClusterEndpoints endpoints) {
        this.codeRepo = codeRepo;
        this.mongoTemplate = mongoTemplate;
        this.clusterRepo = clusterRepo;
        this.memberRepo = memberRepo;
        this.organizationRepo = organizationRepo;
        this.userRepo = userRepo;
        this.jtiRecordRepo = jtiRecordRepo;
        this.jwksRotationService = jwksRotationService;
        this.endpoints = endpoints;
    }

    // ========== Step 1: Issue exchange code ==========

    /**
     * Create an exchange code for a Cloud Console → Cluster Console handoff.
     * <p>
     * Four conditions must pass (ADR-0125 D3):
     * <ol>
     *   <li>User is authenticated (Cloud session valid — validated upstream in SecurityFilter).</li>
     *   <li>User is a member of the target Organization.</li>
     *   <li>Target Cluster exists in that Organization.</li>
     *   <li>Cluster is NOT in provisioning_failed or terminated state.</li>
     * </ol>
     *
     * @return the raw exchange code + base_uri + expiry — ready to return to the browser.
     */
    public IssuedExchangeCode issueExchangeCode(String userEmail, String orgId, String clusterId) {
        // Load User for stable userId — needed for JWT claim "user_id" (Issue #5 item 1)
        User user = userRepo.findByEmail(userEmail.toLowerCase())
            .orElseThrow(() -> new BusinessException(AppErrorCode.USER_NOT_FOUND, 404, userEmail));

        // Condition 2: user ∈ org
        if (!memberRepo.existsByOrgIdAndUserEmail(new ObjectId(orgId), userEmail)) {
            throw new BusinessException(AppErrorCode.ORG_NOT_MEMBER, 403, orgId);
        }

        // Condition 3: cluster exists + belongs to org
        Cluster cluster = clusterRepo.findById(new ObjectId(clusterId))
            .orElseThrow(() -> new BusinessException(AppErrorCode.CLUSTER_NOT_FOUND, 404, clusterId));
        if (!new ObjectId(orgId).equals(cluster.getOrgId())) {
            throw new BusinessException(AppErrorCode.CLUSTER_ORG_MISMATCH, 400, clusterId, orgId);
        }

        // Condition 4: cluster not in terminal failure state
        ClusterState state = cluster.getState();
        if (state == ClusterState.PROVISIONING_FAILED || state == ClusterState.TERMINATED) {
            throw new BusinessException(AppErrorCode.CLUSTER_INVALID_STATE, 409, state);
        }

        // Generate exchange code — 32 bytes random, base64url
        byte[] raw = new byte[32];
        secureRandom.nextBytes(raw);
        String rawCode = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String codeHash = sha256Hex(rawCode);

        Instant now = Instant.now();
        Instant expires = now.plus(EXCHANGE_CODE_TTL_SECONDS, ChronoUnit.SECONDS);

        ExchangeCode ec = ExchangeCode.builder()
            .codeHash(codeHash)
            .userEmail(userEmail)
            .userId(user.getId())
            .orgId(new ObjectId(orgId))
            .clusterId(new ObjectId(clusterId))
            .targetOrigin(endpoints.publicOrigin(cluster))
            .createdAt(now)
            .expiresAt(expires)
            .consumedAt(null)
            .build();
        codeRepo.save(ec);

        log.info("Exchange code issued for user={}, cluster={}, expires={}", userEmail, clusterId, expires);
        return new IssuedExchangeCode(rawCode, ec.getTargetOrigin(), expires);
    }

    // ========== Step 2: Atomic redeem → JWT issue ==========

    /**
     * Atomically consume the exchange code AND issue the resolve JWT (ADR-0125 D4).
     * <p>
     * Single findAndModify: WHERE code_hash = ? AND consumed_at IS NULL AND expires_at > NOW()
     * AND cluster_id = ? → SET consumed_at = NOW(). One row affected = success; zero = failure.
     * <p>
     * Cluster service identity secret is verified by the caller (ExchangeController) —
     * this method trusts that gate has already passed.
     */
    public RedeemedExchange redeemExchangeCode(String rawCode, String expectedClusterId) {
        String codeHash = sha256Hex(rawCode);
        Instant now = Instant.now();

        // Atomic conditional write — one DB round-trip
        Query query = Query.query(Criteria.where("codeHash").is(codeHash)
            .and("consumedAt").isNull()
            .and("expiresAt").gt(now)
            .and("clusterId").is(new ObjectId(expectedClusterId)));
        Update update = Update.update("consumedAt", now);

        ExchangeCode consumed = mongoTemplate.findAndModify(query, update, ExchangeCode.class);

        if (consumed == null) {
            // Distinguish between "already consumed", "expired", "wrong cluster" —
            // lookup without consumed_at filter to diagnose
            ExchangeCode existing = codeRepo.findByCodeHashAndConsumedAtIsNull(codeHash).orElse(null);
            if (existing == null) {
                // Either never existed or was already consumed
                throw new BusinessException(AppErrorCode.EXCHANGE_CODE_CONSUMED, 401);
            } else if (existing.getExpiresAt().isBefore(now)) {
                throw new BusinessException(AppErrorCode.EXCHANGE_CODE_EXPIRED, 401);
            } else if (!existing.getClusterId().equals(new ObjectId(expectedClusterId))) {
                throw new BusinessException(AppErrorCode.EXCHANGE_CODE_CLUSTER_MISMATCH, 401);
            } else {
                log.error("Exchange code validation failed unexpectedly for cluster={}", expectedClusterId);
                throw new BusinessException(AppErrorCode.SYSTEM_ERROR, 500);
            }
        }

        Cluster cluster = clusterRepo.findById(consumed.getClusterId())
            .orElseThrow(() -> new BusinessException(AppErrorCode.SYSTEM_ERROR, 500));
        if (!consumed.getOrgId().equals(cluster.getOrgId())) {
            throw new BusinessException(AppErrorCode.CLUSTER_ORG_MISMATCH, 500,
                consumed.getClusterId(), consumed.getOrgId());
        }
        Organization organization = organizationRepo.findById(consumed.getOrgId())
            .orElseThrow(() -> new BusinessException(AppErrorCode.SYSTEM_ERROR, 500));
        if (organization.getName() == null || cluster.getName() == null || cluster.getRegion() == null) {
            throw new BusinessException(AppErrorCode.SYSTEM_ERROR, 500);
        }

        // Issue resolve JWT — claims per ADR-0122 D4 + Issue #5 item 1 (user_id)
        String jwtId = UUID.randomUUID().toString();
        Instant jwtExp = now.plus(jwtExpiryMinutes, ChronoUnit.MINUTES);
        String aud = endpoints.audience(consumed.getClusterId().toHexString());

        // Live-read the CURRENT active signing key from MongoDB — rotation-aware:
        // if JwksRotationService rotated since this service bean was built, the new
        // key's private key is picked up here. Also read kid from the same key so
        // the JWT header carries the real kid the JWKS endpoint is publishing.
        Optional<JwksKey> activeKeyOpt = jwksRotationService.getActiveKey();
        if (activeKeyOpt.isEmpty()) {
            throw new BusinessException(AppErrorCode.SYSTEM_ERROR, 500,
                "No active JWKS key in MongoDB — cannot sign JWT");
        }
        JwksKey activeKey = activeKeyOpt.get();
        KeyPair signingKp = jwksRotationService.buildActiveSigningKeyPair();

        String jwt = Jwts.builder()
            .id(jwtId)
            .issuer(issuer)
            .subject(consumed.getUserEmail())
            .audience().add(aud).and()
            .claim("user_id", consumed.getUserId() != null ? consumed.getUserId().toHexString() : null)
            .claim("org_id", consumed.getOrgId() != null ? consumed.getOrgId().toHexString() : null)
            .claim("cluster_id", consumed.getClusterId() != null ? consumed.getClusterId().toHexString() : null)
            .claim("scope", List.of("workload:read", "workload:write"))
            .issuedAt(Date.from(now))
            .expiration(Date.from(jwtExp))
            .header()
                .keyId(activeKey.getKid())   // real kid from MongoDB — no hardcoded constant
                .type("JWT")
            .and()
            .signWith(signingKp.getPrivate())
            .compact();

        log.info("Exchange code redeemed → JWT issued jti={}, cluster={}, user={}, userId={}",
            jwtId, consumed.getClusterId(), consumed.getUserEmail(), consumed.getUserId());

        // Track JWT for Cloud-side logout revocation (Issue #5 items 3/4)
        // retentionUntil = 7 days after expiry (Cluster local session outlives JWT window)
        jtiRecordRepo.save(JtiRecord.builder()
            .jti(jwtId)
            .userEmail(consumed.getUserEmail())
            .userId(consumed.getUserId())
            .orgId(consumed.getOrgId())
            .clusterId(consumed.getClusterId())
            .clusterBaseUri(consumed.getTargetOrigin())
            .issuedAt(now)
            .expiresAt(jwtExp)
            .retentionUntil(jwtExp.plus(7, ChronoUnit.DAYS))
            .revokedAt(null)
            .build());

        return new RedeemedExchange(jwt, jwtExp.toString(), jwtId,
            consumed.getUserEmail(),
            consumed.getUserId() != null ? consumed.getUserId().toHexString() : null,
            consumed.getOrgId() != null ? consumed.getOrgId().toHexString() : null,
            consumed.getClusterId() != null ? consumed.getClusterId().toHexString() : null,
            organization.getName(), cluster.getName(), cluster.getRegion());
    }

    // ========== Cluster service identity secret verification ==========

    /**
     * Verify the Cluster service identity Secret per-Cluster (ADR-0125 D4 — per-Cluster isolation).
     * <p>
     * Two sources checked in order:
     * <ol>
     *   <li>In-memory config Map (populated from {@code tapstate.cloud.cluster-service-secrets}
     *       — useful for dev/IT with pre-known cluster ids)</li>
     *   <li>Cluster.sdkStaticToken from DB — every provisioned Cluster carries its own
     *       service identity token set at creation time</li>
     * </ol>
     */
    public boolean verifyClusterServiceSecret(String clusterId, String presentedSecret) {
        // 1. Check in-memory config map first (if populated — dev/IT convenience)
        String fromConfig = clusterServiceSecrets.get(clusterId);
        if (fromConfig != null && fromConfig.equals(presentedSecret)) {
            return true;
        }
        // 2. Fall back to Cluster.sdkStaticToken from DB — the per-Cluster identity secret
        return clusterRepo.findById(new ObjectId(clusterId))
            .map(c -> presentedSecret != null && presentedSecret.equals(c.getSdkStaticToken()))
            .orElse(false);
    }

    /** Register a per-Cluster service identity secret — called during bootstrap (Week 3+). */
    public void registerClusterServiceSecret(String clusterId, String secret) {
        clusterServiceSecrets.put(clusterId, secret);
    }

    // ========== Helpers ==========

    private String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ========== DTOs ==========

    /** Result of issuing an exchange code — returned to browser via GC /resolve. */
    public record IssuedExchangeCode(String rawCode, String baseUri, Instant expiresAt) {}

    /** Result of successful code redemption — returned to Cluster Server via GC /exchange. */
    public record RedeemedExchange(String jwt, String expiresAt, String jti,
                                    String userEmail, String userId, String orgId, String clusterId,
                                    String organizationName, String clusterName, String region) {}
}
