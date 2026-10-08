package io.tapstate.cloud.mdb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AtlasMdbService#buildAtlasMongoUri(String, String, String)}.
 * <p>
 * The stored MdbCluster seed is in practice a full Atlas URI embedding the admin
 * user's credentials; inserting the per-allocation dbUser without stripping that
 * userinfo produced a double-userinfo URI
 * ({@code owner:pass@admin:pass@host}) that the Mongo driver rejected, so every
 * reclaim drop failed while the FSM still reported success.
 */
class AtlasMdbServiceUriTest {

    private static final String USER = "owner_mdb_6475a2b3c1d4e5f6a7b8c9d1";
    private static final String PASS = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void cleanSrvUriGetsPerAllocationCredentials() {
        String uri = AtlasMdbService.buildAtlasMongoUri(
            "mongodb+srv://cluster0.xxx.mongodb.net", USER, PASS);

        assertEquals("mongodb+srv://" + USER + ":" + PASS
            + "@cluster0.xxx.mongodb.net/?authSource=admin", uri);
    }

    @Test
    void standardSchemeMultiHostKeepsEveryHost() {
        String uri = AtlasMdbService.buildAtlasMongoUri(
            "mongodb://host1:27017,host2:27017", USER, PASS);

        assertEquals("mongodb://" + USER + ":" + PASS
            + "@host1:27017,host2:27017/?authSource=admin", uri);
    }

    @Test
    void embeddedAdminCredentialsAreStrippedInsteadOfDoubled() {
        String uri = AtlasMdbService.buildAtlasMongoUri(
            "mongodb+srv://adminUser:adminPass@cluster0.xxx.mongodb.net/admin?retryWrites=true&w=majority",
            USER, PASS);

        assertEquals("mongodb+srv://" + USER + ":" + PASS
            + "@cluster0.xxx.mongodb.net/?authSource=admin", uri);
        assertTrue(!uri.contains("adminUser"), "embedded admin userinfo must be stripped");
        assertEquals(1, countAtSigns(uri), "URI must carry exactly one userinfo separator");
    }

    @Test
    void embeddedCredentialsWithFragmentAndNoPathAreStripped() {
        String uri = AtlasMdbService.buildAtlasMongoUri(
            "mongodb://admin:secret@host1:27017,host2:27017?authSource=admin#frag",
            USER, PASS);

        assertEquals("mongodb://" + USER + ":" + PASS
            + "@host1:27017,host2:27017/?authSource=admin", uri);
    }

    @Test
    void specialCharactersInCredentialsArePercentEncoded() {
        String uri = AtlasMdbService.buildAtlasMongoUri(
            "mongodb+srv://cluster0.xxx.mongodb.net", "us:er@x", "p@ss/word?");

        // Exactly one unencoded '@' separates userinfo from hosts.
        assertEquals(1, countAtSigns(uri), "raw '@' in credentials must be encoded");
        assertTrue(uri.startsWith("mongodb+srv://us%3Aer%40x:p%40ss%2Fword%3F@cluster0.xxx.mongodb.net/"),
            "got: " + uri);
    }

    @Test
    void rejectsUnusableSeedsAndCredentials() {
        assertThrows(MdbException.class,
            () -> AtlasMdbService.buildAtlasMongoUri(null, USER, PASS));
        assertThrows(MdbException.class,
            () -> AtlasMdbService.buildAtlasMongoUri("  ", USER, PASS));
        assertThrows(MdbException.class,
            () -> AtlasMdbService.buildAtlasMongoUri("cluster0.xxx.mongodb.net", USER, PASS));
        assertThrows(MdbException.class,
            () -> AtlasMdbService.buildAtlasMongoUri("mongodb+srv://", USER, PASS));
        assertThrows(MdbException.class,
            () -> AtlasMdbService.buildAtlasMongoUri(
                "mongodb+srv://cluster0.xxx.mongodb.net", null, PASS));
        assertThrows(MdbException.class,
            () -> AtlasMdbService.buildAtlasMongoUri(
                "mongodb+srv://cluster0.xxx.mongodb.net", USER, ""));
    }

    private static int countAtSigns(String uri) {
        int count = 0;
        for (int i = 0; i < uri.length(); i++) {
            if (uri.charAt(i) == '@') {
                count++;
            }
        }
        return count;
    }
}
