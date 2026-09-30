package io.tapstate.cloud.convert;

import org.bson.types.ObjectId;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

public class ObjectIdJsonSerializer extends ValueSerializer<ObjectId> {

    @Override
    public void serialize(ObjectId value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        if (value != null) {
            gen.writeString(value.toHexString());
        } else {
            gen.writeNull();
        }
    }
}
