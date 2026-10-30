package io.tapstate.cloud.cluster;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.iac.config.ClusterRuntimeConfigs;
import io.tapstate.cloud.iac.config.RuntimeAtlasUriResolver;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Real single-document competition and stale snapshots, with no infrastructure or external Cloud calls. */
@Testcontainers(disabledWithoutDocker = true)
class ClusterConfigurationInitializationMongoTest {
    private static final String FIELD = ClusterConfigurationInitialization.FIELD;
    @Container static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    private MongoClient client;
    private MongoTemplate mongo;
    private Cluster cluster;
    private RuntimeAtlasUriResolver resolver;
    private ClusterConfigurationInitializationService service;
    private String hash;

    @BeforeEach void open() {
        client = MongoClients.create(MONGO.getReplicaSetUrl());
        mongo = new MongoTemplate(client, "initialization_" + UUID.randomUUID().toString().replace("-", ""));
        cluster = Cluster.builder().id(new ObjectId()).state(ClusterState.PROVISIONING).sdkStaticToken("fixture-service-secret")
            .configurationInitialization(ClusterConfigurationInitialization.available()).build();
        mongo.insert(cluster);
        resolver = mock(RuntimeAtlasUriResolver.class);
        when(resolver.resolveReady(any())).thenReturn("mongodb://fixture/config");
        service = new ClusterConfigurationInitializationService(mongo, resolver);
        hash = ClusterConfigurationInitializationService.fingerprint("mongodb://fixture/config");
    }
    @AfterEach void close() { if (client != null) client.close(); }
    private String id() { return cluster.getId().toHexString(); }
    private Document stored() { return mongo.getCollection("clusters").find(new Document("_id", cluster.getId())).first(); }

    @Test void uuidRequestCaseDoesNotChangeTheCanonicalOwnerOrOperation() {
        String boot = "f550e840-e29b-41d4-a716-446655440001";
        var claimed = service.claim(id(), cluster.getSdkStaticToken(), boot.toUpperCase(Locale.ROOT), hash);
        assertThat(claimed.creationGranted()).isTrue();
        assertThat(stored().get(FIELD, Document.class).getString("ownerBootId")).isEqualTo(boot);
        assertThat(service.claim(id(), cluster.getSdkStaticToken(), boot, hash)).isEqualTo(claimed);
        var completed = service.complete(id(), cluster.getSdkStaticToken(), claimed.operationId().toUpperCase(Locale.ROOT), hash);
        assertThat(completed.operationId()).isEqualTo(claimed.operationId());
        assertThat(completed.phase()).isEqualTo("COMPLETE");
        assertThat(service.complete(id(), cluster.getSdkStaticToken(), claimed.operationId(), hash)).isEqualTo(completed);
        assertThat(service.claim(id(), cluster.getSdkStaticToken(), boot.toUpperCase(Locale.ROOT), hash).creationGranted()).isFalse();
    }

    @Test void requestAliasesDoNotRelaxMalformedUuidHashOrPersistedFacetValidation() {
        Document before = stored();
        String valid = "f550e840-e29b-41d4-a716-446655440001";
        for (String invalid : List.of("1-1-1-1-1", " " + valid, valid + " ", valid.replace("-", ""))) {
            assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), invalid, hash)).isInstanceOf(BusinessException.class);
            assertThat(stored()).isEqualTo(before);
        }
        assertThat(hash.toUpperCase(Locale.ROOT)).isNotEqualTo(hash);
        assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), valid, hash.toUpperCase(Locale.ROOT)))
            .isInstanceOf(BusinessException.class);
        assertThat(stored()).isEqualTo(before);
        service.claim(id(), cluster.getSdkStaticToken(), valid, hash);
        mongo.getCollection("clusters").updateOne(new Document("_id", cluster.getId()),
            new Document("$set", new Document(FIELD + ".ownerBootId", valid.toUpperCase(Locale.ROOT))));
        before = stored();
        assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), valid, hash)).isInstanceOf(BusinessException.class);
        assertThat(stored()).isEqualTo(before);
    }

    @Test void pendingAllocationReturns503AfterOneReadWithoutChangingAuthority() throws Exception {
        var allocations = mock(io.tapstate.cloud.mdb.MdbAllocationRepository.class);
        var parents = mock(io.tapstate.cloud.mdb.MdbClusterRepository.class);
        var allocationId = new ObjectId();
        var pending = io.tapstate.cloud.mdb.MdbAllocation.builder().id(allocationId)
            .status(io.tapstate.cloud.mdb.MdbAllocationStatus.PROVISIONING).build();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        when(allocations.findById(allocationId)).thenAnswer(ignored -> {
            if (reads.incrementAndGet() > 1) throw new AssertionError("initialization HTTP must not poll allocation readiness");
            return Optional.of(pending);
        });
        mongo.getCollection("clusters").updateOne(new Document("_id", cluster.getId()),
            new Document("$set", new Document("mdbAllocationId", allocationId)));
        Document before = stored();
        var endpoint = new RuntimeAtlasUriResolver(allocations, parents, new io.tapstate.cloud.iac.IacProperties(), false);
        var actual = new ClusterConfigurationInitializationService(mongo, endpoint);
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new ClusterConfigurationInitializationController(actual))
            .setControllerAdvice(new io.tapstate.cloud.common.api.GlobalExceptionHandler()).build();
        var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/v1/api/clusters/" + id() + "/configuration-initialization/claim")
                .header("Authorization", "Bearer " + cluster.getSdkStaticToken()).contentType("application/json")
                .content(new Document("bootId", UUID.randomUUID().toString()).append("configurationHash", hash).toJson()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
            .andReturn().getResponse().getContentAsString();
        assertThat(Document.parse(response).getString("code")).isEqualTo("cluster.configuration-initialization-unavailable");
        assertThat(stored()).isEqualTo(before);
        verify(allocations).findById(allocationId);
        verifyNoInteractions(parents);
    }

    @Test void concurrentBootsHaveOneOwnerAndCompletedAuthorityNeverReopens() throws Exception {
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        try (var threads = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = threads.submit(() -> { start.await(); return service.claim(id(), cluster.getSdkStaticToken(), first, hash); });
            var b = threads.submit(() -> { start.await(); return service.claim(id(), cluster.getSdkStaticToken(), second, hash); });
            start.countDown(); var left = a.get(15, TimeUnit.SECONDS); var right = b.get(15, TimeUnit.SECONDS);
            assertThat(List.of(left, right)).filteredOn(ClusterConfigurationInitializationService.Result::creationGranted).hasSize(1);
            assertThat(left.operationId()).isEqualTo(right.operationId());
            String owner = left.creationGranted() ? first : second;
            assertThat(service.claim(id(), cluster.getSdkStaticToken(), owner, hash).creationGranted()).isTrue();
            assertThat(service.claim(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(), hash).creationGranted()).isFalse();
            var done = service.complete(id(), cluster.getSdkStaticToken(), left.operationId(), hash);
            assertThat(done.phase()).isEqualTo("COMPLETE");
            assertThat(service.complete(id(), cluster.getSdkStaticToken(), left.operationId(), hash)).isEqualTo(done);
            assertThat(service.claim(id(), cluster.getSdkStaticToken(), owner, hash).creationGranted()).isFalse();
            assertThat(service.claim(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(), hash).creationGranted()).isFalse();
        }
    }

    @Test void staleLifecycleSnapshotsCannotResetOrRemoveTheInitializationFacet() {
        Cluster stale = mongo.findById(cluster.getId(), Cluster.class);
        var held = service.claim(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(), hash);
        service.complete(id(), cluster.getSdkStaticToken(), held.operationId(), hash);
        Document before = stored().get(ClusterConfigurationInitialization.FIELD, Document.class);
        var writer = new ClusterPartialWriter(mongo);
        stale.setPublicDns("fixture.example"); writer.apply(stale);
        assertThat(stored().get(ClusterConfigurationInitialization.FIELD)).isEqualTo(before);
        stale.setConfigurationInitialization(null); writer.apply(stale);
        assertThat(stored().get(ClusterConfigurationInitialization.FIELD)).isEqualTo(before);
        assertThat(stored().getString("publicDns")).isEqualTo("fixture.example");
    }

    @Test void invalidIdentityTargetOperationOrLifecycleCannotMutateAuthority() {
        String boot = UUID.randomUUID().toString(); Document before = stored();
        assertThatThrownBy(() -> service.claim(id(), "other-secret", boot, hash)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.claim(new ObjectId().toHexString(), cluster.getSdkStaticToken(), boot, hash)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), boot, "a".repeat(64))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), "not-a-boot", hash)).isInstanceOf(BusinessException.class);
        assertThat(stored()).isEqualTo(before);
        var held = service.claim(id(), cluster.getSdkStaticToken(), boot, hash); before = stored();
        assertThatThrownBy(() -> service.complete(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(), hash)).isInstanceOf(BusinessException.class);
        assertThat(stored()).isEqualTo(before);
        mongo.getCollection("clusters").updateOne(new Document("_id", cluster.getId()), new Document("$set", new Document("state", "TERMINATED")));
        assertThatThrownBy(() -> service.complete(id(), cluster.getSdkStaticToken(), held.operationId(), hash)).isInstanceOf(BusinessException.class);
        assertThat(stored().get(ClusterConfigurationInitialization.FIELD)).isEqualTo(before.get(ClusterConfigurationInitialization.FIELD));
    }

    @Test void missingOrMalformedOriginalFacetIsNeverInferredFromAnEmptyConfiguration() {
        var collection = mongo.getCollection("clusters");
        collection.updateOne(new Document("_id", cluster.getId()), new Document("$unset", new Document(ClusterConfigurationInitialization.FIELD, "")));
        Document before = stored();
        assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(), hash)).isInstanceOf(BusinessException.class);
        assertThat(stored()).isEqualTo(before);
        collection.updateOne(new Document("_id", cluster.getId()), new Document("$set", new Document(ClusterConfigurationInitialization.FIELD,
            new Document("phase", "AVAILABLE"))));
        assertThatThrownBy(() -> service.claim(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(), hash)).isInstanceOf(BusinessException.class);
    }

    @Test void sidecarProofMatchesTheEndpointActuallyRenderedForTheRuntime() {
        when(resolver.resolveReady(any())).thenReturn("");
        String actual = ClusterRuntimeConfigs.effectiveAtlasUri("");
        assertThat(actual).isEqualTo("mongodb://tapstate-mongo:27017/tapstate_cluster");
        assertThat(service.claim(id(), cluster.getSdkStaticToken(), UUID.randomUUID().toString(),
            ClusterConfigurationInitializationService.fingerprint(actual)).creationGranted()).isTrue();
    }

    @Test void allocationUnavailabilityReturns503WithoutChangingAuthority() throws Exception {
        Document before = stored().get(ClusterConfigurationInitialization.FIELD, Document.class);
        when(resolver.resolveReady(any())).thenThrow(new io.tapstate.cloud.iac.IacException(
            io.tapstate.cloud.common.api.AppErrorCode.IAC_MDB_NOT_READY, "allocation-private-detail"));
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new ClusterConfigurationInitializationController(service))
            .setControllerAdvice(new io.tapstate.cloud.common.api.GlobalExceptionHandler()).build();
        var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/v1/api/clusters/" + id() + "/configuration-initialization/claim")
                .header("Authorization", "Bearer " + cluster.getSdkStaticToken())
                .contentType("application/json")
                .content(new Document("bootId", UUID.randomUUID().toString()).append("configurationHash", hash).toJson()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isServiceUnavailable())
            .andReturn().getResponse().getContentAsString();
        assertThat(Document.parse(response).getString("code")).isEqualTo("cluster.configuration-initialization-unavailable");
        assertThat(response).doesNotContain("allocation-private-detail", cluster.getSdkStaticToken());
        assertThat(stored().get(ClusterConfigurationInitialization.FIELD)).isEqualTo(before);
    }

    @Test void httpUsesTheServiceIdentityAndTheVersionedContractEnvelope() throws Exception {
        Document fixture;
        try (var input = getClass().getResourceAsStream("/contract-fixtures/c4-configuration-initialization-v1.json")) {
            fixture = Document.parse(new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        mongo.getCollection("clusters").deleteMany(new Document());
        cluster.setId(new ObjectId(fixture.getString("clusterId")));
        cluster.setConfigurationInitialization(new ClusterConfigurationInitialization(1,
            fixture.get("completeRequest", Document.class).getString("operationId"), "AVAILABLE", "", ""));
        mongo.insert(cluster);
        var http = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            new ClusterConfigurationInitializationController(service))
            .setControllerAdvice(new io.tapstate.cloud.common.api.GlobalExceptionHandler()).build();
        var body = fixture.get("claimRequest", Document.class).toJson();
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fixture.getString("claimPath"))
                .contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fixture.getString("claimPath"))
                .header("Authorization", "Bearer other-secret").contentType("application/json").content(body))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        for (String action : List.of("claim", "complete")) {
            var response = http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(fixture.getString(action + "Path"))
                    .header("Authorization", "Bearer " + cluster.getSdkStaticToken()).contentType("application/json")
                    .content(fixture.get(action + "Request", Document.class).toJson()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse().getContentAsString();
            var result = Document.parse(response);
            assertThat(result.getString("code")).isEqualTo("ok");
            assertThat(result.get("data")).isEqualTo(fixture.get(action + "Response", Document.class).get("data"));
            assertThat(response).doesNotContain(cluster.getSdkStaticToken(), "ownerBootId", "mongodb://");
        }
    }
}
