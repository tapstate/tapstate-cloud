package io.tapstate.cloud.config;

import io.tapstate.cloud.convert.ObjectIdJsonDeserializer;
import io.tapstate.cloud.convert.ObjectIdJsonSerializer;
import org.bson.types.ObjectId;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.module.SimpleModule;

@Configuration
public class JacksonConfig {

    @Bean
    public JsonMapperBuilderCustomizer jsonMapperBuilderCustomizer() {
        return jsonMapperBuilder -> {
            SimpleModule module = new SimpleModule();
            module.addSerializer(ObjectId.class, new ObjectIdJsonSerializer());
            module.addDeserializer(ObjectId.class, new ObjectIdJsonDeserializer());
            jsonMapperBuilder.addModule(module);
        };
    }
}
