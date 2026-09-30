package io.tapstate.cloud.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import io.tapstate.cloud.convert.ObjectIdJsonDeserializer;
import io.tapstate.cloud.convert.ObjectIdJsonSerializer;
import org.bson.types.ObjectId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

@Configuration
public class ObjectIdConfig {

    @Bean
    public Jackson2ObjectMapperBuilder jacksonBuilder() {
        SimpleModule module = new SimpleModule();
        module.addSerializer(ObjectId.class, new ObjectIdJsonSerializer());
        module.addDeserializer(ObjectId.class, new ObjectIdJsonDeserializer());
        
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        builder.modules(module);
        return builder;
    }
}
