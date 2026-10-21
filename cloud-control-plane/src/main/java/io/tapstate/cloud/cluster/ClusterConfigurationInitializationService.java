package io.tapstate.cloud.cluster;

import com.mongodb.ReadConcern;
import com.mongodb.ReadPreference;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.config.RuntimeAtlasUriResolver;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A fixed CONFIG initialization facet on the existing Cluster, independent from runtime heartbeats and user sessions. */
@Service
public class ClusterConfigurationInitializationService {
    private static final String FIELD = ClusterConfigurationInitialization.FIELD;
    private static final List<String> LIVE = List.of("PROVISIONING", "READY", "DEGRADED");
    private static final Set<String> FIELDS = Set.of("formatVersion", "operationId", "phase", "ownerBootId", "configurationHash");
    private final MongoTemplate mongo;
    private final RuntimeAtlasUriResolver endpoints;

    public ClusterConfigurationInitializationService(MongoTemplate mongo, RuntimeAtlasUriResolver endpoints) {
        this.mongo = mongo; this.endpoints = endpoints;
    }

    public record Result(String clusterId, String operationId, String phase, String configurationHash, boolean creationGranted) {}

    public Result claim(String clusterId, String secret, String bootId, String configurationHash) {
        requireUuid(bootId); requireHash(configurationHash);
        return guarded(() -> {
            var collection = collection(); var identity = identity(clusterId, secret);
            Document cluster = authenticated(collection, identity); Document original = original(cluster);
            Cluster mapped = mongo.getConverter().read(Cluster.class, cluster);
            String expected = fingerprint(ClusterRuntimeConfigs.effectiveAtlasUri(endpoints.resolve(mapped)));
            if (!expected.equals(configurationHash)) throw refused();
            if ("AVAILABLE".equals(original.getString("phase"))) {
                Document next = new Document(original).append("phase", "CLAIMED")
                    .append("ownerBootId", bootId).append("configurationHash", expected);
                var winner = collection.findOneAndUpdate(new Document(identity).append(FIELD, original),
                    new Document("$set", new Document(FIELD, next)), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
                cluster = winner == null ? authenticated(collection, identity) : winner;
                original = original(cluster);
            }
            if (!configurationHash.equals(original.getString("configurationHash"))) throw refused();
            return result(clusterId, original, "CLAIMED".equals(original.getString("phase")) && bootId.equals(original.getString("ownerBootId")));
        });
    }

    public Result complete(String clusterId, String secret, String operationId, String configurationHash) {
        requireUuid(operationId); requireHash(configurationHash);
        return guarded(() -> {
            var collection = collection(); var identity = identity(clusterId, secret);
            var original = original(authenticated(collection, identity));
            if (!operationId.equals(original.getString("operationId")) || !configurationHash.equals(original.getString("configurationHash"))) throw refused();
            if ("COMPLETE".equals(original.getString("phase"))) return result(clusterId, original, false);
            if (!"CLAIMED".equals(original.getString("phase"))) throw refused();
            var next = new Document(original).append("phase", "COMPLETE");
            var winner = collection.findOneAndUpdate(new Document(identity).append(FIELD, original),
                new Document("$set", new Document(FIELD, next)), new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER));
            var actual = original(winner == null ? authenticated(collection, identity) : winner);
            if (!"COMPLETE".equals(actual.getString("phase")) || !operationId.equals(actual.getString("operationId"))
                    || !configurationHash.equals(actual.getString("configurationHash"))) throw refused();
            return result(clusterId, actual, false);
        });
    }

    private MongoCollection<Document> collection() {
        return mongo.getCollection(mongo.getCollectionName(Cluster.class)).withReadConcern(ReadConcern.MAJORITY)
            .withReadPreference(ReadPreference.primary()).withWriteConcern(WriteConcern.MAJORITY.withJournal(true));
    }
    private static Document identity(String clusterId, String secret) {
        if (clusterId == null || !ObjectId.isValid(clusterId) || secret == null || secret.isBlank() || secret.length() > 4096)
            throw unauthorized();
        return new Document("_id", new ObjectId(clusterId)).append("sdkStaticToken", secret).append("state", new Document("$in", LIVE));
    }
    private static Document authenticated(MongoCollection<Document> collection, Document identity) {
        var found = collection.find(identity).first(); if (found == null) throw unauthorized(); return found;
    }
    private static Document original(Document cluster) {
        Object value = cluster.get(FIELD);
        if (!(value instanceof Document row) || !row.keySet().equals(FIELDS) || !Integer.valueOf(1).equals(row.get("formatVersion"))
                || !(row.get("operationId") instanceof String operation) || !(row.get("phase") instanceof String phase)
                || !(row.get("ownerBootId") instanceof String boot) || !(row.get("configurationHash") instanceof String hash)) throw refused();
        requireUuid(operation);
        if (phase.equals("AVAILABLE")) { if (!boot.isEmpty() || !hash.isEmpty()) throw refused(); }
        else {
            if (!Set.of("CLAIMED", "COMPLETE").contains(phase)) throw refused();
            requireUuid(boot); requireHash(hash);
        }
        return row;
    }
    private static Result result(String clusterId, Document value, boolean granted) {
        return new Result(clusterId, value.getString("operationId"), value.getString("phase"), value.getString("configurationHash"), granted);
    }
    public static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void requireUuid(String value) {
        try { if (value == null || !UUID.fromString(value).toString().equals(value)) throw refused(); }
        catch (IllegalArgumentException invalid) { throw refused(); }
    }
    private static void requireHash(String value) { if (value == null || !value.matches("[0-9a-f]{64}")) throw refused(); }
    private static <T> T guarded(java.util.function.Supplier<T> action) {
        try { return action.get(); }
        catch (BusinessException refused) { throw refused; }
        catch (org.springframework.dao.DataAccessException | com.mongodb.MongoException failure) {
            throw new BusinessException(AppErrorCode.CLUSTER_CONFIGURATION_INITIALIZATION_UNAVAILABLE, 503);
        }
    }
    private static BusinessException refused() { return new BusinessException(AppErrorCode.CLUSTER_CONFIGURATION_INITIALIZATION_REFUSED, 409); }
    private static BusinessException unauthorized() { return new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, 401); }
}
