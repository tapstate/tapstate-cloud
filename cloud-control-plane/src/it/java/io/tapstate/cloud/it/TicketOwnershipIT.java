package io.tapstate.cloud.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Real HTTP and Mongo ownership boundary for Cloud support tickets. */
class TicketOwnershipIT extends BaseIT {

    private static final String PASSWORD = "StrongPass123!";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void callerCannotListReadOrCloseAnotherUsersTicket() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        String callerEmail = "ticket-caller-" + suffix + "@tapstate.io";
        String ownerEmail = "ticket-owner-" + suffix + "@tapstate.io";
        String callerCookie = signupAndCaptureCookie(callerEmail);
        findUserId(callerEmail);

        resetApiClient();
        register(ownerEmail, PASSWORD);
        ObjectId ownerId = findUserId(ownerEmail);

        ObjectId ticketId = new ObjectId();
        seedTicket(ticketId, ownerId);
        Map<String, String> callerSession = Map.of("Cookie", "TAPSTATE_CLOUD_SESSION=" + callerCookie);

        var listResponse = get("/v1/api/tickets", callerSession);
        var readResponse = get("/v1/api/tickets/" + ticketId.toHexString(), callerSession);
        var closeResponse = post("/v1/api/tickets/" + ticketId.toHexString() + "/close", "{}", callerSession);

        // The IT command configures a non-routable JSM URL. An empty list proves that the caller's
        // owner filter ran before Jira status reads; wrong-owner details and close return 404.
        assertEquals(200, listResponse.statusCode());
        JsonNode list = MAPPER.readTree(listResponse.body());
        assertEquals(0, list.path("data").path("items").size());
        assertEquals(404, readResponse.statusCode(), "non-owners must not read another user's ticket");
        assertEquals(404, closeResponse.statusCode(), "non-owners must not close another user's ticket");
        assertEquals(1L, countTickets(ticketId), "ownership rejection must leave the owner's mapping intact");
    }

    private String signupAndCaptureCookie(String email) throws Exception {
        register(email, PASSWORD);
        login(email, PASSWORD);
        return sessionCookieValue;
    }

    private ObjectId findUserId(String email) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            Document user = mongo.getDatabase(MONGO_DB).getCollection("users")
                .find(new Document("email", email.toLowerCase(java.util.Locale.ROOT))).first();
            if (user == null || !(user.get("_id") instanceof ObjectId id)) {
                throw new IllegalStateException("The registered IT user was not persisted");
            }
            return id;
        }
    }

    private void seedTicket(ObjectId ticketId, ObjectId ownerId) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            mongo.getDatabase(MONGO_DB).getCollection("cloud_support_tickets").insertOne(new Document()
                .append("_id", ticketId)
                .append("ownerId", ownerId)
                .append("jiraKey", "TSSD-" + ticketId.toHexString())
                .append("summary", "Private owner ticket")
                .append("createdAt", Date.from(Instant.now())));
        }
    }

    private long countTickets(ObjectId ticketId) {
        try (MongoClient mongo = MongoClients.create(MONGO_URI)) {
            return mongo.getDatabase(MONGO_DB).getCollection("cloud_support_tickets")
                .countDocuments(new Document("_id", ticketId));
        }
    }
}
