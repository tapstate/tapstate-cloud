package io.tapstate.cloud.config;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;

/**
 * MongoDB configuration — Control Plane metadata store (ADR-0121 D2).
 * <p>
 * Explicitly wires MongoDatabaseFactory from spring.data.mongodb.uri, because Spring Boot
 * 4.x + reactive starter was dropping the database name and defaulting to "test".
 */
@Configuration
@EnableMongoAuditing
@EnableMongoRepositories(basePackages = "io.tapstate.cloud")
@Slf4j
public class MongoConfig {

    @Value("${spring.data.mongodb.uri}")
    private String mongoUri;

    @Value("${spring.data.mongodb.database:}")
    private String mongoDatabase;

    @Bean
    public MongoClient mongoClient() {
        ConnectionString cs = new ConnectionString(mongoUri);
        log.info("[MongoConfig] Creating MongoClient from URI: {}", mongoUri);
        return MongoClients.create(cs);
    }

    @Bean
    public SimpleMongoClientDatabaseFactory mongoDatabaseFactory(MongoClient mongoClient) {
        ConnectionString cs = new ConnectionString(mongoUri);
        String database = mongoDatabase != null && !mongoDatabase.isEmpty()
            ? mongoDatabase
            : (cs.getDatabase() != null ? cs.getDatabase() : "tapstate_cloud");
        log.info("[MongoConfig] Connecting to database: {}", database);
        return new SimpleMongoClientDatabaseFactory(mongoClient, database);
    }

    @Bean
    public MongoTemplate mongoTemplate(SimpleMongoClientDatabaseFactory factory) {
        return new MongoTemplate(factory);
    }

    @Bean
    public PlatformTransactionManager transactionManager(SimpleMongoClientDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }
}
