package io.tapstate.cloud.mdb;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AtlasMdbService#buildDbUserRoles(MdbAllocation)}.
 * <p>
 * The owner user must carry BOTH {@code readWrite} and {@code dbAdmin} on every
 * allocation database: Atlas rejects ordinary data reads and inserts for a
 * dbAdmin-only user, which makes the runtime's bootstrap migrations fail and the
 * node never leave 502; dbAdmin is still needed to drop the databases on reclaim.
 */
class AtlasMdbServiceRolesTest {

    private static final String CLUSTER_HEX = "6475a2b3c1d4e5f6a7b8c9d1";
    private static final String DB = "mdb_" + CLUSTER_HEX;
    private static final String OPERATOR_DB = DB + "_operator";
    private static final String VIEWS_DB = DB + "_views";

    @Test
    void ownerUserGetsReadWriteAndDbAdminOnAllThreeDatabases() {
        MdbAllocation allocation = MdbAllocation.builder()
            .id(new ObjectId(CLUSTER_HEX))
            .databaseName(DB)
            .operatorDbName(OPERATOR_DB)
            .viewsDbName(VIEWS_DB)
            .build();

        List<Map<String, String>> roles = AtlasMdbService.buildDbUserRoles(allocation);

        for (String databaseName : List.of(DB, OPERATOR_DB, VIEWS_DB)) {
            Set<String> onDatabase = rolesOn(roles, databaseName);
            assertTrue(onDatabase.contains("readWrite"),
                "owner needs readWrite on " + databaseName + " — runtime data and migration writes");
            assertTrue(onDatabase.contains("dbAdmin"),
                "owner needs dbAdmin on " + databaseName + " — dropDatabase on reclaim");
        }
    }

    @Test
    void systemScopedMonitorRolesAreAddedOnce() {
        MdbAllocation allocation = MdbAllocation.builder()
            .databaseName(DB)
            .operatorDbName(OPERATOR_DB)
            .viewsDbName(VIEWS_DB)
            .build();

        List<Map<String, String>> roles = AtlasMdbService.buildDbUserRoles(allocation);

        // 3 databases x 2 data roles + 3 system roles
        assertEquals(9, roles.size());
        assertEquals(Set.of("clusterMonitor"), rolesOn(roles, "admin"));
        assertEquals(Set.of("read"), rolesOn(roles, "config"));
        assertEquals(Set.of("read"), rolesOn(roles, "local"));
    }

    private static Set<String> rolesOn(List<Map<String, String>> roles, String databaseName) {
        return roles.stream()
            .filter(r -> databaseName.equals(r.get("databaseName")))
            .map(r -> r.get("roleName"))
            .collect(Collectors.toSet());
    }
}
