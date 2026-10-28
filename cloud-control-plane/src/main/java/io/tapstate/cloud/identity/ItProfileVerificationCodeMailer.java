package io.tapstate.cloud.identity;

import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@Primary
@Profile("it")
@RequiredArgsConstructor
public class ItProfileVerificationCodeMailer implements VerificationCodeMailer {

    private final MongoTemplate mongoTemplate;

    @Override
    public void send(String email, String code) {
        mongoTemplate.getCollection("test_email_outbox").insertOne(new Document("email", email)
            .append("code", code).append("sentAt", Instant.now()));
    }
}
