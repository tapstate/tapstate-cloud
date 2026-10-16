package io.tapstate.cloud.config;

import com.mongodb.client.model.CreateCollectionOptions;
import com.mongodb.client.model.TimeSeriesGranularity;
import com.mongodb.client.model.TimeSeriesOptions;
import io.tapstate.cloud.cluster.Cluster;
import io.tapstate.cloud.cluster.DnsLabels;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.MongoPersistentEntityIndexResolver;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MongoDB initialization listener — fires after ApplicationReadyEvent.
 * <p>
 * Pattern referenced from hare {@code ServerReadyListener}. This component:
 * <ol>
 *   <li>Scans {@code io.tapstate.cloud} for all {@link Document}-annotated entities</li>
 *   <li>Idempotently resolves and applies Spring Data index annotations
 *       ({@code @Indexed}, {@code @CompoundIndex}, {@code @TextIndexed})</li>
 *   <li>Ensures every entity's collection exists (create if missing, no-op otherwise)</li>
 *   <li>Explicitly creates time-series collections with {@code createCollection +
 *       timeseries} options — these CANNOT be created via plain {@code createCollection}
 *       and MUST be initialized before any write happens</li>
 * </ol>
 * <p>
 * Spring Data MongoDB auto-creates indexes by default, but explicit application here
 * guarantees indexes exist even when {@code spring.data.mongodb.auto-index-creation}
 * is disabled or when a new compound/single index is added to an existing entity.
 */
@Slf4j
@Component
public class MongoInitListener implements ApplicationListener<ApplicationReadyEvent> {

    private static final String ENTITY_BASE_PACKAGE = "io.tapstate.cloud";

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ensureTimeSeriesCollections(event);
        ensureIndexes(event);
    }

    /**
     * Create MongoDB time-series collections explicitly.
     * <p>
     * These cannot be created via the plain {@code createCollection} call below —
     * they require {@code db.createCollection(name, { timeseries: {...} })}.
     * Guarded by {@code collectionExists} to be idempotent on restarts.
     */
    private void ensureTimeSeriesCollections(ApplicationReadyEvent event) {
        MongoTemplate mongoTemplate = event.getApplicationContext().getBean(MongoTemplate.class);

        createTimeSeriesIfMissing(mongoTemplate, "cluster_status_report_history",
            "timestamp", "clusterId", TimeSeriesGranularity.SECONDS);
    }

    private void createTimeSeriesIfMissing(MongoTemplate mongoTemplate, String collectionName,
                                            String timeField, String metaField,
                                            TimeSeriesGranularity granularity) {
        if (mongoTemplate.collectionExists(collectionName)) {
            log.debug("[Mongo Init] time-series collection '{}' already exists — skip", collectionName);
            return;
        }

        TimeSeriesOptions tsOptions = new TimeSeriesOptions(timeField)
            .metaField(metaField)
            .granularity(granularity);

        CreateCollectionOptions options = new CreateCollectionOptions().timeSeriesOptions(tsOptions);

        mongoTemplate.getDb().createCollection(collectionName, options);
        log.info("[Mongo Init] Created time-series collection '{}' (timeField={}, metaField={}, granularity={})",
            collectionName, timeField, metaField, granularity);
    }

    private void ensureIndexes(ApplicationReadyEvent event) {
        MongoTemplate mongoTemplate = event.getApplicationContext().getBean(MongoTemplate.class);
        MongoMappingContext mappingContext = event.getApplicationContext().getBean(MongoMappingContext.class);

        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Document.class));

        var entities = scanner.findCandidateComponents(ENTITY_BASE_PACKAGE);
        log.info("[Mongo Init] Scanning @Document entities under {}", ENTITY_BASE_PACKAGE);

        int applied = 0;
        int skipped = 0;
        for (var beanDef : entities) {
            try {
                Class<?> entity = Class.forName(beanDef.getBeanClassName());
                if (!entity.isAnnotationPresent(Document.class)) {
                    continue;
                }

                // Time-series collections are created above — skip plain createCollection here
                String collectionName = mongoTemplate.getCollectionName(entity);
                if (!mongoTemplate.collectionExists(collectionName)) {
                    mongoTemplate.createCollection(entity);
                    log.debug("[Mongo Init] Created collection for {}", entity.getSimpleName());
                }

                // Backfill fields introduced after the entity existed before
                // their unique indexes are ensured, otherwise existing
                // documents would make index creation fail.
                if (entity == Cluster.class) {
                    backfillClusterDnsLabels(mongoTemplate);
                }

                // Resolve and apply indexes from annotation metadata
                IndexOperations indexOps = mongoTemplate.indexOps(entity);
                MongoPersistentEntityIndexResolver resolver = new MongoPersistentEntityIndexResolver(mappingContext);
                var definitions = resolver.resolveIndexFor(entity);
                int idxCount = 0;
                for (var definition : definitions) {
                    indexOps.ensureIndex(definition);
                    idxCount++;
                }

                if (entity == Cluster.class) {
                    boolean activeIndexExists = indexOps.getIndexInfo().stream()
                        .anyMatch(index -> "org_region_name_active_unique".equals(index.getName()));
                    if (!activeIndexExists) {
                        throw new IllegalStateException("Active Cluster name index is missing");
                    }
                    boolean dnsLabelIndexExists = indexOps.getIndexInfo().stream()
                        .anyMatch(index -> "dns_label_active_unique".equals(index.getName()));
                    if (!dnsLabelIndexExists) {
                        throw new IllegalStateException("Active Cluster DNS-label index is missing");
                    }
                    boolean legacyIndexExists = indexOps.getIndexInfo().stream()
                        .anyMatch(index -> "org_region_name_unique".equals(index.getName()));
                    if (legacyIndexExists) {
                        indexOps.dropIndex("org_region_name_unique");
                        log.info("[Mongo Init] Replaced legacy Cluster name index with active-only index");
                    }
                }

                if (idxCount > 0) {
                    log.info("[Mongo Init] Applied {} index(es) to {}", idxCount, entity.getSimpleName());
                    applied++;
                } else {
                    skipped++;
                }
            } catch (ClassNotFoundException e) {
                log.error("[Mongo Init] Could not load entity class: {}", beanDef.getBeanClassName(), e);
            } catch (Exception e) {
                if (Cluster.class.getName().equals(beanDef.getBeanClassName())) {
                    throw new IllegalStateException("Cluster name index migration failed", e);
                }
                log.error("[Mongo Init] Failed to apply indexes for {}: {}", beanDef.getBeanClassName(), e.getMessage());
            }
        }

        log.info("[Mongo Init] Done — applied indexes to {} entities, {} had no explicit indexes", applied, skipped);
    }

    /**
     * Backfill {@code dnsLabel} on Cluster documents created before the field
     * existed, so the global unique partial index can be created against
     * complete data. Prefer the name-derived label used by the current rule;
     * fall back to the id hex (the former domain label) when the name
     * normalizes to nothing or clashes with another document's label.
     */
    private void backfillClusterDnsLabels(MongoTemplate mongoTemplate) {
        // {field: null} matches both null and missing values.
        List<Cluster> unlabeled = mongoTemplate.find(
            Query.query(Criteria.where("dnsLabel").is(null)), Cluster.class);
        for (Cluster cluster : unlabeled) {
            String nameLabel = DnsLabels.normalize(cluster.getName());
            String label = nameLabel;
            if (!DnsLabels.isValidLabel(label) || mongoTemplate.exists(
                    Query.query(Criteria.where("dnsLabel").is(label)
                        .and("_id").ne(cluster.getId())), Cluster.class)) {
                label = cluster.getId().toHexString();
                log.warn("[Mongo Init] Cluster {} keeps id-based DNS label '{}' — name '{}' normalizes to '{}' or collides",
                    cluster.getId().toHexString(), label, cluster.getName(), nameLabel);
            }
            mongoTemplate.updateFirst(
                Query.query(Criteria.where("_id").is(cluster.getId())),
                Update.update("dnsLabel", label), Cluster.class);
        }
        if (!unlabeled.isEmpty()) {
            log.info("[Mongo Init] Backfilled dnsLabel on {} Cluster document(s)", unlabeled.size());
        }
    }
}
