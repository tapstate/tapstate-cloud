package io.tapstate.cloud.event;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventTypeCatalogTest {

    @Mock
    private PlatformEventRepository eventRepository;
    @Mock
    private MongoTemplate mongoTemplate;
    @Mock
    private AlertEvaluationService evaluationService;
    @Mock
    private EventCenterMetrics metrics;

    private EventCenterService service;

    @BeforeEach
    void setUp() {
        service = new EventCenterService(eventRepository, mongoTemplate, evaluationService, metrics, 30, 100);
    }

    @Test
    @DisplayName("event types are returned in a stable alphabetical order")
    void typesAreSorted() {
        when(mongoTemplate.findDistinct(any(Query.class), eq("type"), eq(PlatformEvent.class), eq(String.class)))
            .thenReturn(List.of("user.operation.post", "cluster.task.failed"));

        List<String> types = service.distinctTypes(
            new org.bson.types.ObjectId().toHexString(), "owner@example.com", true, null);

        assertThat(types).containsExactly("cluster.task.failed", "user.operation.post");
    }

    @Test
    @DisplayName("a non-admin is scoped to their own events and the prefix is literal")
    void nonAdminQueryIsScopedAndEscaped() {
        when(mongoTemplate.findDistinct(any(Query.class), eq("type"), eq(PlatformEvent.class), eq(String.class)))
            .thenReturn(List.of());

        service.distinctTypes(new org.bson.types.ObjectId().toHexString(),
            "member@example.com", false, "cluster.");

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).findDistinct(captor.capture(), eq("type"), eq(PlatformEvent.class), eq(String.class));
        Document query = captor.getValue().getQueryObject();
        assertThat(query.getString("actor")).isEqualTo("member@example.com");
        Object type = query.get("type");
        assertThat(type).isInstanceOf(Pattern.class);
        assertThat(((Pattern) type).pattern()).isEqualTo("^" + Pattern.quote("cluster."));
    }
}
