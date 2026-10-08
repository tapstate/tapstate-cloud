package io.tapstate.cloud.progress;

import java.util.Arrays;
import java.util.List;

/**
 * Fixed catalog of deployment operations. Each constant carries its stable
 * code (the external API contract), its {@link DeploymentOpType}, its
 * presentation/dependency sequence, its weight in the aggregate percentage
 * and a human-readable name.
 * <p>
 * The weights of every operation of one {@link DeploymentOpType} sum to 100.
 * Codes are append-only: do not rename or remove an existing code — callers
 * persist them.
 */
public enum DeploymentOperation {

    // ========== INSTALL ==========
    RESERVE_MDB_SLOT("reserve_mdb_slot", DeploymentOpType.INSTALL, 10, 3, "Reserve MongoDB cluster slot"),
    GENERATE_SSH_KEY("generate_ssh_key", DeploymentOpType.INSTALL, 20, 2, "Generate SSH key pair"),
    PROVISION_EC2("provision_ec2", DeploymentOpType.INSTALL, 30, 18, "Provision EC2 node"),
    WAIT_SSH_READY("wait_ssh_ready", DeploymentOpType.INSTALL, 40, 8, "Wait for SSH readiness"),
    RESTRICT_SSH_INGRESS("restrict_ssh_ingress", DeploymentOpType.INSTALL, 50, 3, "Restrict SSH firewall ingress"),
    CREATE_DNS_RECORD("create_dns_record", DeploymentOpType.INSTALL, 60, 5, "Create DNS record"),
    CREATE_DATABASE("create_database", DeploymentOpType.INSTALL, 70, 7, "Create database"),
    CREATE_DATABASE_USER("create_database_user", DeploymentOpType.INSTALL, 80, 10, "Create database user"),
    WAIT_MDB_ALLOCATION("wait_mdb_allocation", DeploymentOpType.INSTALL, 90, 3, "Wait for MongoDB allocation"),
    START_RUNTIME_STACK("start_runtime_stack", DeploymentOpType.INSTALL, 100, 12, "Start runtime stack"),
    CONFIGURE_NGINX("configure_nginx", DeploymentOpType.INSTALL, 110, 7, "Configure Nginx"),
    WAIT_DNS_PROPAGATION("wait_dns_propagation", DeploymentOpType.INSTALL, 120, 7, "Wait for DNS propagation"),
    ISSUE_TLS_CERTIFICATE("issue_tls_certificate", DeploymentOpType.INSTALL, 130, 8, "Issue TLS certificate"),
    HEALTH_CHECK("health_check", DeploymentOpType.INSTALL, 140, 7, "Health check"),

    // ========== UNINSTALL ==========
    TERMINATE_EC2("terminate_ec2", DeploymentOpType.UNINSTALL, 10, 35, "Terminate EC2 node"),
    DELETE_DNS_RECORD("delete_dns_record", DeploymentOpType.UNINSTALL, 20, 15, "Delete DNS record"),
    DROP_DATABASE("drop_database", DeploymentOpType.UNINSTALL, 30, 30, "Drop databases"),
    DROP_DATABASE_USER("drop_database_user", DeploymentOpType.UNINSTALL, 40, 20, "Drop database user");

    private final String code;
    private final DeploymentOpType opType;
    private final int sequence;
    private final int weight;
    private final String displayName;

    DeploymentOperation(String code, DeploymentOpType opType, int sequence,
                        int weight, String displayName) {
        this.code = code;
        this.opType = opType;
        this.sequence = sequence;
        this.weight = weight;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    public DeploymentOpType opType() {
        return opType;
    }

    public int sequence() {
        return sequence;
    }

    public int weight() {
        return weight;
    }

    public String displayName() {
        return displayName;
    }

    /** Every operation of one category, ordered by sequence. */
    public static List<DeploymentOperation> byOpType(DeploymentOpType opType) {
        return Arrays.stream(values())
            .filter(op -> op.opType == opType)
            .sorted((a, b) -> Integer.compare(a.sequence, b.sequence))
            .toList();
    }

    /** Resolve a catalog operation by its code. */
    public static DeploymentOperation fromCode(String code) {
        return Arrays.stream(values())
            .filter(op -> op.code.equals(code))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown deployment operation: " + code));
    }
}
