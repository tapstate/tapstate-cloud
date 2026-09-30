package io.tapstate.cloud.convert;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.bson.types.ObjectId;

import java.io.IOException;
import java.util.regex.Pattern;

public class ObjectIdJsonDeserializer extends JsonDeserializer<ObjectId> {
    private static final Pattern pattern = Pattern.compile("^[0-9a-f]{24}$");

    public static ObjectId toObjectId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return pattern.matcher(id).matches() ? new ObjectId(id) : null;
    }

    @Override
    public ObjectId deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String value = p.getValueAsString();
        return toObjectId(value);
    }
}
