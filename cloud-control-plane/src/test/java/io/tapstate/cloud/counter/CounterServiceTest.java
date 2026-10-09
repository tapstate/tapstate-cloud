package io.tapstate.cloud.counter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Unit tests for {@link CounterService}: the advance must be a single
 * findAndModify with $inc, upsert and returnNew so concurrent callers are
 * serialized and a fresh counter starts at 1.
 */
class CounterServiceTest {

    private final MongoTemplate mongoTemplate = mock(MongoTemplate.class);
    private final CounterService counterService = new CounterService(mongoTemplate);

    @Test
    void firstIncrementUpsertsAtOneAndReturnsNewValue() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(FindAndModifyOptions.class), eq(Counter.class)))
            .thenAnswer(inv -> {
                FindAndModifyOptions options = inv.getArgument(2);
                assertTrue(options.isUpsert(), "missing counter must be upserted");
                assertTrue(options.isReturnNew(), "caller needs the post-increment value");
                return new Counter("cluster_version", 1L);
            });

        long seq = counterService.getNextSeq("cluster_version");

        assertEquals(1L, seq);
    }

    @Test
    void incrementsByOneAndQueriesByCounterName() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(FindAndModifyOptions.class), eq(Counter.class)))
            .thenReturn(new Counter("cluster_version", 7L));

        long seq = counterService.getNextSeq("cluster_version");

        assertEquals(7L, seq);

        ArgumentCaptor<Query> queryCaptor = ArgumentCaptor.forClass(Query.class);
        ArgumentCaptor<Update> updateCaptor = ArgumentCaptor.forClass(Update.class);
        verify(mongoTemplate).findAndModify(queryCaptor.capture(), updateCaptor.capture(),
            any(FindAndModifyOptions.class), eq(Counter.class));

        Document queryObject = queryCaptor.getValue().getQueryObject();
        assertEquals("cluster_version", queryObject.get("_id"));

        Document inc = (Document) updateCaptor.getValue().getUpdateObject().get("$inc");
        assertEquals(1L, ((Number) inc.get("seq")).longValue());
    }

    @Test
    void illegalStateWhenMongoDoesNotReturnTheUpdatedDocument() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
            any(FindAndModifyOptions.class), eq(Counter.class)))
            .thenReturn(null);

        assertThrows(IllegalStateException.class,
            () -> counterService.getNextSeq("cluster_version"));
    }
}
