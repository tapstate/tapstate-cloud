package io.tapstate.cloud.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClusterEgressAddressControllerTest {
    private final ClusterService service = mock(ClusterService.class);
    private final ClusterEgressAddressController controller = new ClusterEgressAddressController(service);
    private final ObjectId id = new ObjectId();

    @Test
    void absentOrInvalidCredentialsAreRejected() {
        for (String header : new String[] {null, "", "Basic token"}) {
            BusinessException failure = assertThrows(BusinessException.class,
                () -> controller.current(id.toHexString(), header));
            assertEquals(401, failure.getHttpStatus());
        }
        when(service.getBySdkToken("bad")).thenThrow(new BusinessException(AppErrorCode.SDK_TOKEN_INVALID, 401));
        assertEquals(401, assertThrows(BusinessException.class,
            () -> controller.current(id.toHexString(), "Bearer bad")).getHttpStatus());
    }

    @Test
    void tokensCannotReadAnotherCluster() {
        Cluster cluster = new Cluster();
        cluster.setId(id);
        when(service.getBySdkToken("valid")).thenReturn(cluster);
        assertEquals(401, assertThrows(BusinessException.class,
            () -> controller.current(new ObjectId().toHexString(), "Bearer valid")).getHttpStatus());
    }

    @Test
    void readsFreshMetadataWithoutExposingCredentialsOrCaching() throws Exception {
        Cluster cluster = new Cluster();
        cluster.setId(id);
        cluster.setOrgId(new ObjectId());
        cluster.setState(ClusterState.READY);
        cluster.setSdkStaticToken("credential-must-stay-private");
        cluster.setEc2PublicIp("198.51.100.1");
        when(service.getBySdkToken("valid")).thenReturn(cluster);
        var legacy = controller.current(id.toHexString(), "Bearer valid");
        assertEquals("DYNAMIC", legacy.getBody().getData().egressAddressStatus());
        cluster.setEc2PublicIp("203.0.113.42");
        cluster.setEgressAddressStatus(ClusterEgressAddressStatus.STABLE);
        var fresh = controller.current(id.toHexString(), "Bearer valid");
        assertEquals("no-store", fresh.getHeaders().getCacheControl());
        assertEquals("203.0.113.42", fresh.getBody().getData().ec2PublicIp());
        assertEquals("STABLE", fresh.getBody().getData().egressAddressStatus());
        var json = new ObjectMapper().valueToTree(fresh.getBody().getData());
        assertEquals(4, json.size());
        assertFalse(json.toString().contains("credential-must-stay-private"));
        verify(service, times(2)).getBySdkToken("valid");
        for (ClusterState unavailable : new ClusterState[] {ClusterState.PROVISIONING_FAILED,
                ClusterState.DRAINED, ClusterState.TERMINATED}) {
            cluster.setState(unavailable);
            var reclaimed = controller.current(id.toHexString(), "Bearer valid").getBody().getData();
            assertNull(reclaimed.ec2PublicIp());
            assertEquals("FAILED", reclaimed.egressAddressStatus());
        }
        cluster.setState(ClusterState.PROVISIONING);
        var pending = controller.current(id.toHexString(), "Bearer valid").getBody().getData();
        assertNull(pending.ec2PublicIp());
        assertEquals("ATTACHING", pending.egressAddressStatus());
    }
}
