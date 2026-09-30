package io.tapstate.cloud.convert;

import org.bson.types.ObjectId;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.util.regex.Pattern;

public class ObjectIdJsonDeserializer extends ValueDeserializer<ObjectId> {
    private static final Pattern pattern = Pattern.compile("^[0-9a-f]{24}$");

    public static ObjectId toObjectId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return pattern.matcher(id).matches() ? new ObjectId(id) : null;
    }

    @Override
    public ObjectId deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        String value = p.getValueAsString();
        return toObjectId(value);
    }
}
