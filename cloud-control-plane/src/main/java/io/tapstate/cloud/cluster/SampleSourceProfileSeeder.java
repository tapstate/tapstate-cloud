package io.tapstate.cloud.cluster;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Inserts defaults once; operators may subsequently edit the MongoDB documents directly. */
@Component
final class SampleSourceProfileSeeder implements ApplicationRunner {
    private final SampleSourceProfileRepository profiles;
    private final String defaultHost;

    SampleSourceProfileSeeder(SampleSourceProfileRepository profiles,
            @Value("${tapstate.cloud.sample-sources.host:47.238.126.103}") String defaultHost) {
        this.profiles = profiles;
        this.defaultHost = defaultHost;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (SampleSourceProfile profile : defaults()) {
            if (!profiles.existsById(profile.id())) profiles.save(profile);
        }
    }

    private List<SampleSourceProfile> defaults() {
        return List.of(
                new SampleSourceProfile("sample_core_banking", "MySQL order sample",
                        "Orders with customer and order lines", "mysql", defaultHost, 33306,
                        "tpcc_331", null, "root", "shared-sample-password", "bmsql_oorder",
                        "bmsql_order_line", "bmsql_customer", true, true, 10),
                new SampleSourceProfile("sample_cards_crm", "PostgreSQL order sample",
                        "Independent order dataset", "postgres", defaultHost, 55433,
                        "postgres", "public", "root", "shared-sample-password",
                        "T_9_3_2_bmsql_oorder", "T_9_3_2_bmsql_order_line",
                        "T_9_3_2_bmsql_customer", true, true, 20),
                upcoming("ibm-db2-i", "IBM DB2 for i (IBM AS400)", "db2-i", 30),
                upcoming("ibm-db2-luw", "IBM DB2 for LUW", "db2-luw", 40),
                upcoming("sybase-ase", "Sybase ASE", "sybase", 50),
                upcoming("apache-kafka", "Apache Kafka", "kafka", 60),
                upcoming("confluent-kafka", "Confluent Kafka", "confluent-kafka", 70));
    }

    private SampleSourceProfile upcoming(String id, String name, String connector, int order) {
        return new SampleSourceProfile(id, name, "Coming soon", connector, null, 0,
                null, null, null, null, null, null, null, false, false, order);
    }
}
