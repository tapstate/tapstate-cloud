package io.tapstate.cloud.cluster;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Editable sample inventory in the Cloud control-plane database. */
@Document(collection = "sample_source_profiles")
public record SampleSourceProfile(
        @Id String id,
        String name,
        String description,
        String connector,
        String host,
        int port,
        String database,
        String schema,
        String username,
        String secretRef,
        String rootTable,
        String orderLineTable,
        String customerTable,
        boolean available,
        boolean guidedDemo,
        int sortOrder) { }
