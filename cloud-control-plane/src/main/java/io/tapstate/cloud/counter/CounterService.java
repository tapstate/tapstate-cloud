package io.tapstate.cloud.counter;

import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Returns strictly increasing sequence numbers backed by the global
 * {@code counters} collection.
 * <p>
 * The advance is a single MongoDB {@code findAndModify} with {@code $inc},
 * {@code upsert} and {@code returnNew}: a missing counter document is created
 * with {@code seq = 1}, and concurrent callers are serialized by the document
 * lock so two callers can never observe the same value or skip a value.
 */
@Service
@RequiredArgsConstructor
public class CounterService {

    private final MongoTemplate mongoTemplate;

    /**
     * Atomically increment the named counter and return the new value.
     *
     * @param name globally unique counter name
     * @return the value after the increment, starting at 1 for a new counter
     */
    public long getNextSeq(String name) {
        Counter updated = mongoTemplate.findAndModify(
            Query.query(Criteria.where("_id").is(name)),
            new Update().inc("seq", 1L),
            FindAndModifyOptions.options().upsert(true).returnNew(true),
            Counter.class);

        if (updated == null || updated.getSeq() <= 0) {
            throw new IllegalStateException("Counter sequence was not returned for counter: " + name);
        }
        return updated.getSeq();
    }
}
