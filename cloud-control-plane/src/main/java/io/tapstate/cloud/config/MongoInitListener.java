package io.tapstate.cloud.config;

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
import org.springframework.stereotype.Component;

/**
 * MongoDB initialization listener — fires after ApplicationReadyEvent.
 * <p>
 * Pattern referenced from hare {@code ServerReadyListener}. This component:
 * <ol>
 *   <li>Scans {@code io.tapstate.cloud} for all {@link Document}-annotated entities</li>
 *   <li>Idempotently resolves and applies Spring Data index annotations
 *       ({@code @Indexed}, {@code @CompoundIndex}, {@code @TextIndexed})</li>
 *   <li>Ensures every entity's collection exists (create if missing, no-op otherwise)</li>
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
        ensureIndexes(event);
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

                // Ensure collection exists first (idempotent — createCollection is a no-op if present)
                mongoTemplate.getCollectionName(entity); // triggers auto-collection-name resolution
                if (!mongoTemplate.collectionExists(entity)) {
                    mongoTemplate.createCollection(entity);
                    log.debug("[Mongo Init] Created collection for {}", entity.getSimpleName());
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

                if (idxCount > 0) {
                    log.info("[Mongo Init] Applied {} index(es) to {}", idxCount, entity.getSimpleName());
                    applied++;
                } else {
                    skipped++;
                }
            } catch (ClassNotFoundException e) {
                log.error("[Mongo Init] Could not load entity class: {}", beanDef.getBeanClassName(), e);
            } catch (Exception e) {
                log.error("[Mongo Init] Failed to apply indexes for {}: {}", beanDef.getBeanClassName(), e.getMessage());
            }
        }

        log.info("[Mongo Init] Done — applied indexes to {} entities, {} had no explicit indexes", applied, skipped);
    }
}
