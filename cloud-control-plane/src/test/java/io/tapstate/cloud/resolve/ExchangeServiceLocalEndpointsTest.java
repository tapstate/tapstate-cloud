package io.tapstate.cloud.resolve;

import io.jsonwebtoken.Jwts;
import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.ClusterRepository;
import io.tapstate.cloud.cluster.ClusterState;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import io.tapstate.cloud.config.JwksKey;
import io.tapstate.cloud.config.JwksRotationService;
import io.tapstate.cloud.identity.User;
import io.tapstate.cloud.identity.UserRepository;
import io.tapstate.cloud.iac.ClusterDomainName;
import io.tapstate.cloud.iac.IacProperties;
import io.tapstate.cloud.org.MemberRepository;
import io.tapstate.cloud.org.Organization;
import io.tapstate.cloud.org.OrganizationRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPairGenerator;
import java.util.Optional;

import static io.tapstate.cloud.resolve.LocalClusterEndpointsTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExchangeServiceLocalEndpointsTest {
    @Test
    void usesMappedOriginAndSignedAudienceWhileKeepingAtomicCodeAndOrgBinding() throws Exception {
        assertExchangeContract(local(properties("http://cluster-1:28443")),
            "http://localhost:28443", "localhost", true, new ClusterDomainName(new IacProperties()));
    }

    @Test
    void acceptsUppercaseClusterIdentityDuringRedemption() throws Exception {
        assertExchangeContract(local(properties("http://cluster-1:28443")),
            "http://localhost:28443", "localhost", true, new ClusterDomainName(new IacProperties()),
            CLUSTER_ID.toUpperCase(java.util.Locale.ROOT));
    }

    @Test
    void rejectsUnmappedClusterWithExchangeErrorBeforeConsumingCode() {
        var codes = mock(ExchangeCodeRepository.class);
        var mongo = mock(MongoTemplate.class);
        var records = mock(JtiRecordRepository.class);
        var keys = mock(JwksRotationService.class);
        var service = new ExchangeService(codes, mongo, mock(ClusterRepository.class), mock(MemberRepository.class),
            mock(OrganizationRepository.class), mock(UserRepository.class), records, keys,
            local(properties("http://cluster-1:28443")), mock(ClusterDomainName.class));
        BusinessException failure = assertThrows(BusinessException.class,
            () -> service.redeemExchangeCode("unconsumed-code", "fedcba9876543210fedcba98"));
        assertEquals(AppErrorCode.EXCHANGE_CODE_CLUSTER_MISMATCH, failure.getErrorCode());
        assertEquals(401, failure.getHttpStatus());
        verifyNoInteractions(codes, mongo, records, keys);
    }

    @Test
    void ordinaryExchangeKeepsDomainOriginAudienceAndItsExistingAtomicConditions() throws Exception {
        var properties = properties("http://cluster-1:28443");
        properties.setEnabled(false);
        var resolver = new LocalClusterEndpoints(properties, new org.springframework.mock.env.MockEnvironment(),
            "https://cloud.tapstate.io");
        assertExchangeContract(resolver, "https://ignored.example", "local-cluster.dev.cloud.tapstate.com",
            false, new ClusterDomainName(new IacProperties()));
    }

    @Test
    void ordinaryProductionExchangeUsesThePersistedDnsLabelAndConfiguredRoot() throws Exception {
        var properties = properties("http://cluster-1:28443");
        properties.setEnabled(false);
        var resolver = new LocalClusterEndpoints(properties, new org.springframework.mock.env.MockEnvironment(),
            "https://cloud.tapstate.io");
        var deployment = new IacProperties();
        deployment.setEnvironment("prod");
        deployment.getGodaddy().setDomain("example.com");
        assertExchangeContract(resolver, "https://ignored.example", "local-cluster.cloud.example.com",
            false, new ClusterDomainName(deployment));
    }

    @Test
    void foreignStoredOriginIsRejectedByTheAtomicConditionBeforeSigningOrRecordingAJwt() {
        var codes = mock(ExchangeCodeRepository.class);
        var mongo = mock(MongoTemplate.class);
        var records = mock(JtiRecordRepository.class);
        var keys = mock(JwksRotationService.class);
        var resolver = local(properties("http://cluster-1:28443"));
        var service = new ExchangeService(codes, mongo, mock(ClusterRepository.class), mock(MemberRepository.class),
            mock(OrganizationRepository.class), mock(UserRepository.class), records, keys, resolver,
            mock(ClusterDomainName.class));
        var stored = ExchangeCode.builder().clusterId(new ObjectId(CLUSTER_ID))
            .targetOrigin("http://localhost:28444").expiresAt(java.time.Instant.now().plusSeconds(60)).build();
        when(codes.findByCodeHashAndConsumedAtIsNull(anyString())).thenReturn(Optional.of(stored));
        BusinessException mismatch = assertThrows(BusinessException.class,
            () -> service.redeemExchangeCode("unconsumed-code", CLUSTER_ID));
        assertEquals(AppErrorCode.EXCHANGE_CODE_CLUSTER_MISMATCH, mismatch.getErrorCode());
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(query.capture(), any(Update.class), eq(ExchangeCode.class));
        assertEquals("http://localhost:28443", query.getValue().getQueryObject().get("targetOrigin"));
        verifyNoInteractions(records, keys);
    }

    private void assertExchangeContract(LocalClusterEndpoints resolver, String expectedOrigin,
                                        String expectedAudience, boolean local, ClusterDomainName domains) throws Exception {
        assertExchangeContract(resolver, expectedOrigin, expectedAudience, local, domains, CLUSTER_ID);
    }

    private void assertExchangeContract(LocalClusterEndpoints resolver, String expectedOrigin,
                                        String expectedAudience, boolean local, ClusterDomainName domains,
                                        String requestClusterId) throws Exception {
        var codes = mock(ExchangeCodeRepository.class);
        var mongo = mock(MongoTemplate.class);
        var clusters = mock(ClusterRepository.class);
        var members = mock(MemberRepository.class);
        var organizations = mock(OrganizationRepository.class);
        var users = mock(UserRepository.class);
        var records = mock(JtiRecordRepository.class);
        var keys = mock(JwksRotationService.class);
        var service = new ExchangeService(codes, mongo, clusters, members, organizations, users, records, keys,
            resolver, domains);
        String expectedIssuer = local ? ISSUER : "https://cloud.tapstate.io";
        ReflectionTestUtils.setField(service, "issuer", expectedIssuer);
        ReflectionTestUtils.setField(service, "jwtExpiryMinutes", 15);
        ObjectId clusterId = new ObjectId(CLUSTER_ID);
        ObjectId orgId = new ObjectId("111111111111111111111111");
        ObjectId userId = new ObjectId("222222222222222222222222");
        var cluster = Cluster.builder().id(clusterId).orgId(orgId).name("local").region("local")
            .dnsLabel("local-cluster").state(ClusterState.READY).publicDns("ignored.example").build();
        when(users.findByEmail("user@example.com")).thenReturn(Optional.of(User.builder().id(userId).build()));
        when(members.existsByOrgIdAndUserEmail(orgId, "user@example.com")).thenReturn(true);
        when(clusters.findById(clusterId)).thenReturn(Optional.of(cluster));
        when(organizations.findById(orgId)).thenReturn(Optional.of(Organization.builder().id(orgId).name("org").build()));
        var issued = service.issueExchangeCode("user@example.com", orgId.toHexString(), CLUSTER_ID);
        assertEquals(expectedOrigin, issued.baseUri());
        var codeCaptor = ArgumentCaptor.forClass(ExchangeCode.class);
        verify(codes).save(codeCaptor.capture());
        var stored = codeCaptor.getValue();
        assertNotEquals(issued.rawCode(), stored.getCodeHash());
        when(mongo.findAndModify(any(Query.class), any(Update.class), eq(ExchangeCode.class)))
            .thenReturn(stored).thenReturn(null);
        var keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        when(keys.getActiveKey()).thenReturn(Optional.of(JwksKey.builder().kid("local-key").build()));
        when(keys.buildActiveSigningKeyPair()).thenReturn(keyPair);

        var redeemed = service.redeemExchangeCode(issued.rawCode(), requestClusterId);
        var claims = Jwts.parser().verifyWith(keyPair.getPublic()).requireIssuer(expectedIssuer)
            .build().parseSignedClaims(redeemed.jwt()).getPayload();
        assertEquals(java.util.Set.of(expectedAudience), claims.getAudience());
        assertEquals(CLUSTER_ID, claims.get("cluster_id"));
        assertEquals(orgId.toHexString(), claims.get("org_id"));
        var queryCaptor = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findAndModify(queryCaptor.capture(), any(Update.class), eq(ExchangeCode.class));
        assertEquals(clusterId, queryCaptor.getValue().getQueryObject().get("clusterId"));
        if (local) {
            assertEquals(expectedOrigin, queryCaptor.getValue().getQueryObject().get("targetOrigin"));
        } else {
            assertFalse(queryCaptor.getValue().getQueryObject().containsKey("targetOrigin"));
        }
        assertTrue(queryCaptor.getValue().getQueryObject().containsKey("consumedAt"));
        assertTrue(queryCaptor.getValue().getQueryObject().containsKey("expiresAt"));
        BusinessException consumed = assertThrows(BusinessException.class,
            () -> service.redeemExchangeCode(issued.rawCode(), CLUSTER_ID));
        assertEquals(AppErrorCode.EXCHANGE_CODE_CONSUMED, consumed.getErrorCode());
        verify(records, times(1)).save(any(JtiRecord.class));

        var wrongOrg = new ObjectId("333333333333333333333333");
        when(members.existsByOrgIdAndUserEmail(wrongOrg, "user@example.com")).thenReturn(true);
        BusinessException mismatch = assertThrows(BusinessException.class,
            () -> service.issueExchangeCode("user@example.com", wrongOrg.toHexString(), CLUSTER_ID));
        assertEquals(AppErrorCode.CLUSTER_ORG_MISMATCH, mismatch.getErrorCode());
        verify(codes, times(1)).save(any(ExchangeCode.class));
    }
}
