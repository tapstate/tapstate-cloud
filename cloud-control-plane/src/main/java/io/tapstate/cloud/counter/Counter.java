package io.tapstate.cloud.counter;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Atomic sequence counter document in the global {@code counters} collection.
 * <p>
 * One document per named sequence. The document id is the globally unique
 * counter name and {@code seq} the last handed-out value; sequences start at 1
 * (the first {@code $inc} on an upserted document yields 1).
 */
@Document(collection = "counters")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Counter {

    /** Globally unique counter name. */
    @Id
    private String name;

    /** Current sequence value; monotonically increasing, never reused. */
    private long seq;
}
