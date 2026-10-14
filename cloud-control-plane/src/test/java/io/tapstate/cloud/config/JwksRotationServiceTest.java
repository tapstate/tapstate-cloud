package io.tapstate.cloud.config;

import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the multi-replica bootstrap race: two nodes starting on an
 * empty collection both inserted an ACTIVE key with the same deterministic
 * kid, and the unique index declared on the entity was applied too late
 * (ApplicationReadyEvent) to prevent it. The fix ensures the index before
 * find-or-create and adopts the winning row when the insert collides.
 */
class JwksRotationServiceTest {

    private JwksKeyRepository repository;
    private IndexOperations indexOps;
    private JwksRotationService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = mock(JwksKeyRepository.class);
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        indexOps = mock(IndexOperations.class);
        when(mongoTemplate.indexOps(JwksKey.class)).thenReturn(indexOps);

        service = new JwksRotationService(repository, mongoTemplate);
        Field field = JwksRotationService.class.getDeclaredField("rotationEnabled");
        field.setAccessible(true);
        field.setBoolean(service, true);
    }

    @Test
    void freshClusterInsertsOneActiveKey() {
        when(repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE))
            .thenReturn(Optional.empty());

        service.init();

        verify(repository, times(1)).save(any(JwksKey.class));
    }

    @Test
    void existingActiveKeyIsLeftUntouched() {
        JwksKey active = JwksKey.builder()
            .id(new ObjectId())
            .kid("key-2026-10-14")
            .status(JwksKey.KeyStatus.ACTIVE)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(10, ChronoUnit.HOURS))
            .build();
        when(repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE))
            .thenReturn(Optional.of(active));

        service.init();

        verify(repository, never()).save(any(JwksKey.class));
    }

    @Test
    void concurrentBootstrapDuplicateAdoptsTheWinningRow() {
        JwksKey winner = JwksKey.builder()
            .id(new ObjectId())
            .kid("key-2026-10-14")
            .status(JwksKey.KeyStatus.ACTIVE)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(10, ChronoUnit.HOURS))
            .build();
        when(repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winner));
        when(repository.save(any(JwksKey.class)))
            .thenThrow(new DuplicateKeyException("E11000 duplicate key on kid"));

        service.init();

        // Exactly one insert attempt, then a re-read adopts the winner
        // instead of crashing or leaving a second ACTIVE row behind.
        verify(repository, times(1)).save(any(JwksKey.class));
        verify(repository, times(2))
            .findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE);
    }

    @Test
    void uniqueKidIndexIsEnsuredBeforeBootstrapInsert() {
        when(repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE))
            .thenReturn(Optional.empty());

        service.init();

        InOrder order = inOrder(indexOps, repository);
        order.verify(indexOps).ensureIndex(any(IndexDefinition.class));
        order.verify(repository).save(any(JwksKey.class));
    }

    @Test
    void ensuredKidIndexIsUniqueAndNamedAfterTheField() {
        when(repository.findFirstByStatusOrderByCreatedAtDesc(JwksKey.KeyStatus.ACTIVE))
            .thenReturn(Optional.empty());

        service.init();

        org.mockito.ArgumentCaptor<IndexDefinition> captor =
            org.mockito.ArgumentCaptor.forClass(IndexDefinition.class);
        verify(indexOps).ensureIndex(captor.capture());
        List<? extends IndexDefinition> definitions = captor.getAllValues();
        assertEquals(1, definitions.size());
        IndexDefinition definition = definitions.get(0);
        assertEquals(Boolean.TRUE, definition.getIndexOptions().get("unique"));
        assertEquals("kid", definition.getIndexOptions().get("name"));
        assertNotNull(definition.getIndexKeys().get("kid"));
    }
}
