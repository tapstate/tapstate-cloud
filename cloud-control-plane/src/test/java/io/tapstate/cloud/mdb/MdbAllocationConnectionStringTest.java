package io.tapstate.cloud.mdb;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MdbAllocation#buildRuntimeConnectionString(String)} —
 * the URI injected into the Cluster node docker-compose as
 * {@code TAPSTATE_CLOUD_ATLAS_URI}.
 */
class MdbAllocationConnectionStringTest {

    private static final String CLUSTER_HEX = "6475a2b3c1d4e5f6a7b8c9d1";
    private static final String DB = "mdb_" + CLUSTER_HEX;
    private static final String USER = "owner_" + DB;
    private static final String PASS = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private MdbAllocation allocation() {
        return MdbAllocation.builder()
            .id(new ObjectId(CLUSTER_HEX))
            .databaseName(DB)
            .dbUsername(USER)
            .dbPassword(PASS)
            .build();
    }

    @Test
    void srvCleanConnectionStringGetsCredentialsDatabaseAndAuthSource() {
        String uri = allocation().buildRuntimeConnectionString(
            "mongodb+srv://tapstate-shared.mongodb.net");

        assertEquals(
            "mongodb+srv://" + USER + ":" + PASS
                + "@tapstate-shared.mongodb.net/" + DB + "?authSource=admin",
            uri);
    }

    @Test
    void standardSchemeWithReplicaSetHostsIsSupported() {
        String uri = allocation().buildRuntimeConnectionString(
            "mongodb://host1:27017,host2:27017");

        assertEquals(
            "mongodb://" + USER + ":" + PASS
                + "@host1:27017,host2:27017/" + DB + "?authSource=admin",
            uri);
    }

    @Test
    void embeddedCredentialsAndPathAreReplacedNotMerged() {
        String uri = allocation().buildRuntimeConnectionString(
            "mongodb+srv://old-user:old-secret@tapstate-shared.mongodb.net/otherdb?retryWrites=true");

        assertTrue(uri.startsWith("mongodb+srv://" + USER + ":" + PASS + "@tapstate-shared.mongodb.net/" + DB),
            "URI must carry only the allocation credentials and main db, got: " + uri);
        assertTrue(uri.endsWith("?authSource=admin&retryWrites=true"),
            "existing options must be preserved after authSource, got: " + uri);
        assertTrue(!uri.contains("old-user") && !uri.contains("old-secret") && !uri.contains("otherdb"),
            "embedded credentials and database must be stripped, got: " + uri);
    }

    @Test
    void nullInputReturnsNullAndGarbageSchemeIsRejected() {
        assertNull(allocation().buildRuntimeConnectionString(null));
        assertThrows(IllegalArgumentException.class,
            () -> allocation().buildRuntimeConnectionString("not-a-mongo-uri"));
    }
}
