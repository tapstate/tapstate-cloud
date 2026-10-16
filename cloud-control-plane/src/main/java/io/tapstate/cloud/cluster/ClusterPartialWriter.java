package io.tapstate.cloud.cluster;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.data.mongodb.core.mapping.MongoPersistentProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Persists a mutation of an existing Cluster as a targeted update.
 * <p>
 * A repository {@code save} rewrites the whole document from the fields the
 * running revision happens to know, so during a rolling update a replica still
 * on the previous revision erases any field added by the new one: a backfilled
 * or newly persisted attribute disappears the first time the old revision
 * handles a request for that Cluster. This writer instead touches only the
 * properties the given entity maps, leaving every other field — including one
 * this revision does not map at all — exactly as stored.
 * <p>
 * A mapped property whose value is {@code null} is cleared, not written as
 * {@code null}: the converter drops nulls, and a repository {@code save} left
 * such a field absent from the stored document. Clearing is meaningful on
 * termination, on a retry and on a rolled-back provision, so the field is
 * removed rather than nulled.
 * <p>
 * Use it for every partial mutation of an existing Cluster. Creating a Cluster
 * still goes through the repository insert.
 */
@Component
@RequiredArgsConstructor
public class ClusterPartialWriter {

    private final MongoTemplate mongoTemplate;

    /**
     * Write the modified fields of {@code cluster} onto its stored document.
     * <p>
     * {@code updatedAt} is stamped here because the write does not go through the
     * auditing callback that a repository {@code save} would trigger.
     *
     * @param cluster entity whose mutated fields are to be persisted; must carry
     *                its {@code id} and must already exist in the collection
     */
    public void apply(Cluster cluster) {
        MongoConverter converter = mongoTemplate.getConverter();
        Document written = new Document();
        converter.write(cluster, written);
        // Never move the key itself, and leave the stored type hint untouched.
        written.remove("_id");
        written.remove("_class");

        Instant now = Instant.now();
        cluster.setUpdatedAt(now);
        written.put("updatedAt", now);

        Update update = new Update();
        written.forEach(update::set);

        Document cleared = new Document();
        for (MongoPersistentProperty property : converter.getMappingContext()
            .getPersistentEntity(Cluster.class)) {
            if (property.isIdProperty()) {
                continue;
            }
            if (!written.containsKey(property.getFieldName())) {
                cleared.put(property.getFieldName(), "");
            }
        }
        cleared.forEach((field, ignored) -> update.unset(field));

        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(cluster.getId())), update, Cluster.class);
    }
}
