package io.tapstate.cloud.iac.aws;

import io.tapstate.cloud.iac.CloudProvider;
import io.tapstate.cloud.iac.ComputeInfrastructure;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.model.NodeInstance;
import io.tapstate.cloud.iac.model.NodeSpec;
import io.tapstate.cloud.common.api.AppErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.CloudFormationClientBuilder;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsResponse;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;
import software.amazon.awssdk.services.cloudformation.model.ResourceStatus;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Address;
import software.amazon.awssdk.services.ec2.model.DomainType;
import software.amazon.awssdk.services.ec2.model.ResourceType;
import software.amazon.awssdk.services.ec2.model.Tag;
import software.amazon.awssdk.services.ec2.model.TagSpecification;
import software.amazon.awssdk.services.ec2.model.DescribeSubnetsResponse;
import software.amazon.awssdk.services.ec2.model.DescribeVpcsResponse;
import software.amazon.awssdk.services.ec2.model.Subnet;
import software.amazon.awssdk.services.ec2.model.Vpc;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS implementation of {@link ComputeInfrastructure}.
 * <p>
 * The node and its supporting resources live in one CloudFormation stack rendered
 * from the configured template. Cluster-owned Elastic IPs are managed through the
 * EC2 API and tagged independently so they can be reused across node replacement
 * and released during Cluster termination.
 */
@Component
@Slf4j
public class AwsComputeInfrastructure implements ComputeInfrastructure {

    private static final String TEMPLATE_RESOURCE = "/io/tapstate/cloud/iac/aws/cluster-node.yaml";
    private static final String STACK_NAME_PREFIX = "tapstate-cluster-";
    private static final String CLUSTER_TAG = "io.tapstate/cluster-id";
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(15);
    private static final Duration STACK_CREATE_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration STACK_DELETE_TIMEOUT = Duration.ofMinutes(10);

    private static final Set<StackStatus> CREATE_SUCCESS = Set.of(StackStatus.CREATE_COMPLETE);
    private static final Set<StackStatus> CREATE_FAILED_STATES = Set.of(
        StackStatus.CREATE_FAILED,
        StackStatus.ROLLBACK_COMPLETE,
        StackStatus.ROLLBACK_FAILED);
    private static final Set<StackStatus> DELETE_FAILED_STATES = Set.of(
        StackStatus.DELETE_FAILED);

    private final String accessKeyId;
    private final String secretAccessKey;
    private final Map<String, CloudFormationClient> clientsByRegion = new ConcurrentHashMap<>();
    private final Map<String, Ec2Client> ec2ClientsByRegion = new ConcurrentHashMap<>();

    public AwsComputeInfrastructure(
        @Value("${tapstate.cloud.aws.access-key-id:}") String accessKeyId,
        @Value("${tapstate.cloud.aws.secret-access-key:}") String secretAccessKey) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
    }

    @Override
    public NodeInstance provision(NodeSpec spec) {
        String stackName = stackName(spec.clusterId());
        CloudFormationClient cf = clientFor(spec.region());
        Network network = resolveNetwork(spec);

        Stack existing = describeStack(stackName, cf);
        if (existing == null) {
            try {
                cf.createStack(b -> b
                .stackName(stackName)
                .templateBody(loadTemplate())
                .parameters(
                    cfParameter("ClusterId", spec.clusterId()),
                    cfParameter("InstanceType", spec.instanceType()),
                    cfParameter("DiskSize", String.valueOf(spec.diskSizeGb())),
                    cfParameter("SshPublicKey", spec.sshPublicKey()),
                    cfParameter("VpcId", network.vpcId()),
                    cfParameter("SubnetId", network.subnetId())));
                log.info("[AwsIaC] stack create requested stack={} region={} vpc={} subnet={}",
                    stackName, spec.region(), network.vpcId(), network.subnetId());
            } catch (CloudFormationException e) {
                throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e, awsMessage(e));
            }
        }

        Stack stack = awaitStatus(stackName, cf, CREATE_SUCCESS, CREATE_FAILED_STATES,
            STACK_CREATE_TIMEOUT, AppErrorCode.IAC_COMPUTE_PROVISION_FAILED);

        try {
            Map<String, String> outputs = mapOutputs(stack);
            openTemporarySshIngress(ec2ClientFor(spec.region()), outputs.get("SecurityGroupId"));

            String instanceId = outputs.get("InstanceId");
            String publicIp = outputs.get("PublicIp");
            String privateIp = outputs.get("PrivateIp");
            NodeInstance node = new NodeInstance(CloudProvider.AWS, spec.clusterId(), instanceId, publicIp, privateIp);
            return ensureEgressAddress(spec.region(), node, null);
        } catch (RuntimeException provisioningFailure) {
            // A repeated invocation does not own the node created by an earlier attempt.
            // Leave it for the state machine's durable-state compensation, which can
            // distinguish retrying that node from deleting the Cluster and its EIP.
            if (existing != null) throw provisioningFailure;
            try {
                terminateNodeForRetry(spec.region(), spec.clusterId());
            } catch (RuntimeException cleanupFailure) {
                provisioningFailure.addSuppressed(cleanupFailure);
                log.error("[AwsIaC] compensation failed after node provisioning error cluster={} cleanupType={}",
                    spec.clusterId(), cleanupFailure.getClass().getSimpleName());
            }
            throw provisioningFailure;
        }
    }

    @Override
    public NodeInstance stabilizeEgressAddress(String region, String clusterId, String allocationId) {
        return ensureEgressAddress(region, currentNode(region, clusterId), allocationId);
    }

    @Override
    public NodeInstance currentNode(String region, String clusterId) {
        String name = stackName(clusterId);
        CloudFormationClient cf = clientFor(region);
        Stack current = describeStack(name, cf);
        if (current == null) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                "Cluster stack is missing; current node cannot be inspected");
        }
        NodeInstance node = nodeFromStack(clusterId, current);
        Address address = findClusterAddress(ec2ClientFor(region), clusterId, null, node.instanceId());
        if (address == null || !Objects.equals(address.instanceId(), node.instanceId())) return node;
        return new NodeInstance(CloudProvider.AWS, clusterId, node.instanceId(),
            address.publicIp(), node.privateIp(), address.allocationId());
    }

    @Override
    public void terminate(String region, String clusterId) {
        terminateNodeForRetry(region, clusterId);
        releaseEgressAddresses(region, clusterId);
    }

    @Override
    public void terminateNodeForRetry(String region, String clusterId) {
        String stackName = stackName(clusterId);
        CloudFormationClient cf = clientFor(region);

        if (stackExists(stackName, cf)) {
            try {
                cf.deleteStack(b -> b.stackName(stackName));
                log.info("[AwsIaC] stack delete requested stack={}", stackName);
            } catch (CloudFormationException e) {
                throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e, awsMessage(e));
            }
            awaitDeletion(stackName, cf);
        } else {
            log.info("[AwsIaC] stack already absent stack={}", stackName);
        }
    }

    @Override
    public CloudProvider provider() {
        return CloudProvider.AWS;
    }

    @Override
    public java.util.Optional<String> describeInfrastructureStatus(String region, String clusterId) {
        String stackName = stackName(clusterId);
        CloudFormationClient cf = clientFor(region);
        try {
            DescribeStacksResponse response = cf.describeStacks(b -> b.stackName(stackName));
            Stack stack = response.stacks().get(0);
            return java.util.Optional.of(stack.stackStatusAsString());
        } catch (CloudFormationException e) {
            if (isMissingStack(e)) {
                return java.util.Optional.empty();
            }
            // Remote query failure — return empty so caller falls back to local state.
            log.warn("[AwsIaC] describe stack failed stack={}: {}", stackName, awsMessage(e));
            return java.util.Optional.empty();
        }
    }

    // ========== Polling ==========

    /** Poll the stack until it reaches a success or failure state, or times out. */
    private Stack awaitStatus(String stackName, CloudFormationClient cf,
                               Set<StackStatus> success, Set<StackStatus> failure,
                               Duration timeout, AppErrorCode failureCode) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            DescribeStacksResponse response = cf.describeStacks(b -> b.stackName(stackName));
            Stack stack = response.stacks().get(0);
            StackStatus status = stack.stackStatus();
            if (success.contains(status)) {
                return stack;
            }
            if (failure.contains(status)) {
                throw new IacException(failureCode,
                    describeFailure(stackName, cf, status));
            }
            sleep(POLL_INTERVAL);
        }
        throw new IacException(failureCode,
            "stack did not finish within " + timeout + " (" + stackName + ")");
    }

    /**
     * Build a human-readable failure description for a stack in a terminal failure state.
     * Prefers the stack-level status reason; when that is blank (ROLLBACK_COMPLETE often
     * has no reason), falls back to the resource-level CREATE_FAILED / ROLLBACK_IN_PROGRESS
     * event's ResourceStatusReason so the API surfaces the actionable cause — e.g. which
     * resource hit a vCPU limit, which security group rule was denied, etc.
     */
    private String describeFailure(String stackName, CloudFormationClient cf, StackStatus status) {
        String stackReason = stackReason(stackName, cf);
        if (stackReason != null && !stackReason.isBlank()) {
            return "stack " + status + ": " + abbreviate(stackReason);
        }
        String resourceReason = firstResourceFailureReason(cf, stackName);
        if (resourceReason != null) {
            return "stack " + status + " — resource failure: " + abbreviate(resourceReason);
        }
        return "stack " + status + " (no reason from stack or resource events)";
    }

    /** Stack-level status reason, queried fresh (the {@link Stack} snapshot may carry null). */
    private static String stackReason(String stackName, CloudFormationClient cf) {
        try {
            Stack s = cf.describeStacks(b -> b.stackName(stackName)).stacks().get(0);
            return s.stackStatusReason();
        } catch (Exception e) {
            log.warn("[AwsIaC] cannot re-query stack status reason stack={}: {}", stackName, e.getMessage());
            return null;
        }
    }

    /**
     * Scan stack events and return the first CREATE_FAILED / ROLLBACK_IN_PROGRESS event's
     * resource status reason — the actionable failure cause. Returns null when no failure
     * event is found or the API call itself fails (we never want a diagnostics call to
     * mask the original provision failure).
     */
    private static String firstResourceFailureReason(CloudFormationClient cf, String stackName) {
        try {
            DescribeStackEventsResponse response = cf.describeStackEvents(
                DescribeStackEventsRequest.builder().stackName(stackName).build());
            for (StackEvent event : response.stackEvents()) {
                ResourceStatus rs = event.resourceStatus();
                if (rs == null) continue;
                String reason = event.resourceStatusReason();
                if (reason == null || reason.isBlank()) continue;
                switch (rs) {
                    case CREATE_FAILED, ROLLBACK_IN_PROGRESS, UPDATE_FAILED, DELETE_FAILED -> {
                        String resource = event.logicalResourceId();
                        String type = event.resourceType();
                        String prefix = (resource != null ? resource : "?");
                        if (type != null) prefix += " (" + type + ")";
                        return prefix + ": " + reason;
                    }
                    default -> { /* keep scanning */ }
                }
            }
        } catch (Exception e) {
            log.warn("[AwsIaC] describeStackEvents failed stack={}: {}", stackName, e.getMessage());
        }
        return null;
    }

    /** Poll until delete succeeds (stack disappears) or fails, or times out. */
    private void awaitDeletion(String stackName, CloudFormationClient cf) {
        long deadline = System.nanoTime() + STACK_DELETE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                DescribeStacksResponse response = cf.describeStacks(b -> b.stackName(stackName));
                Stack stack = response.stacks().get(0);
                StackStatus status = stack.stackStatus();
                if (DELETE_FAILED_STATES.contains(status)) {
                    throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED,
                        describeFailure(stackName, cf, status));
                }
            } catch (CloudFormationException e) {
                if (isMissingStack(e)) {
                    return;
                }
                throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e, awsMessage(e));
            }
            sleep(POLL_INTERVAL);
        }
        throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED,
            "stack did not delete within " + STACK_DELETE_TIMEOUT + " (" + stackName + ")");
    }

    /** Whether the stack currently exists. */
    private boolean stackExists(String stackName, CloudFormationClient cf) {
        try {
            cf.describeStacks(b -> b.stackName(stackName));
            return true;
        } catch (CloudFormationException e) {
            if (isMissingStack(e)) {
                return false;
            }
            throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e, awsMessage(e));
        }
    }

    private Stack describeStack(String stackName, CloudFormationClient cf) {
        try {
            return cf.describeStacks(b -> b.stackName(stackName)).stacks().get(0);
        } catch (CloudFormationException e) {
            if (isMissingStack(e)) return null;
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e, awsMessage(e));
        }
    }

    private static NodeInstance nodeFromStack(String clusterId, Stack stack) {
        Map<String, String> outputs = mapOutputs(stack);
        return new NodeInstance(CloudProvider.AWS, clusterId,
            outputs.get("InstanceId"), outputs.get("PublicIp"), outputs.get("PrivateIp"));
    }

    private NodeInstance ensureEgressAddress(String region, NodeInstance node, String preferredAllocationId) {
        Ec2Client ec2 = ec2ClientFor(region);
        Address address = findClusterAddress(ec2, node.clusterId(), preferredAllocationId, node.instanceId());
        if (preferredAllocationId != null && !preferredAllocationId.isBlank()
                && (address == null || !preferredAllocationId.equals(address.allocationId()))) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                "Pinned Cluster egress allocation is missing; refusing to change the allowlisted address");
        }
        if (address == null) {
            try {
                var allocated = ec2.allocateAddress(b -> b
                    .domain(DomainType.VPC)
                    .tagSpecifications(TagSpecification.builder()
                        .resourceType(ResourceType.ELASTIC_IP)
                        .tags(List.of(
                            Tag.builder().key("Name").value("tapstate-cluster-egress-" + node.clusterId()).build(),
                            Tag.builder().key(CLUSTER_TAG).value(node.clusterId()).build()))
                        .build()));
                address = Address.builder()
                    .allocationId(allocated.allocationId())
                    .publicIp(allocated.publicIp())
                    .build();
                log.info("[AwsIaC] Cluster egress address allocated cluster={} allocation={}",
                    node.clusterId(), allocated.allocationId());
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                    "failed to allocate Cluster egress address: " + e.getMessage());
            }
        }

        String allocationId = address.allocationId();
        if (!Objects.equals(address.instanceId(), node.instanceId())) {
            try {
                ec2.associateAddress(b -> b
                    .allocationId(allocationId)
                    .instanceId(node.instanceId())
                    .allowReassociation(true));
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                    "failed to associate Cluster egress address: " + e.getMessage());
            }
        }

        return new NodeInstance(CloudProvider.AWS, node.clusterId(), node.instanceId(),
            address.publicIp(), node.privateIp(), allocationId);
    }

    private static Address findClusterAddress(Ec2Client ec2, String clusterId,
            String preferredAllocationId, String instanceId) {
        List<Address> addresses;
        try {
            addresses = ec2.describeAddresses(b -> b.filters(
                software.amazon.awssdk.services.ec2.model.Filter.builder()
                    .name("tag:" + CLUSTER_TAG).values(clusterId).build())).addresses();
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                "failed to find Cluster egress addresses: " + e.getMessage());
        }
        Comparator<Address> priority = Comparator
            .comparing((Address candidate) -> !Objects.equals(candidate.allocationId(), preferredAllocationId))
            .thenComparing(candidate -> !Objects.equals(candidate.instanceId(), instanceId))
            .thenComparing(Address::allocationId);
        return addresses.stream().sorted(priority).findFirst().orElse(null);
    }

    private void releaseEgressAddresses(String region, String clusterId) {
        Ec2Client ec2 = ec2ClientFor(region);
        List<Address> addresses;
        try {
            addresses = ec2.describeAddresses(b -> b.filters(
                software.amazon.awssdk.services.ec2.model.Filter.builder()
                    .name("tag:" + CLUSTER_TAG).values(clusterId).build())).addresses();
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e,
                "failed to find Cluster egress addresses: " + e.getMessage());
        }

        for (Address address : addresses) {
            if (address.associationId() != null && !address.associationId().isBlank()) {
                try {
                    ec2.disassociateAddress(b -> b.associationId(address.associationId()));
                } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                    if (!"InvalidAssociationID.NotFound".equals(awsErrorCode(e))) {
                        throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e,
                            "failed to disassociate Cluster egress address: " + e.getMessage());
                    }
                }
            }
            try {
                ec2.releaseAddress(b -> b.allocationId(address.allocationId()));
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                if (!"InvalidAllocationID.NotFound".equals(awsErrorCode(e))) {
                    throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e,
                        "failed to release Cluster egress address: " + e.getMessage());
                }
            }
        }
    }

    private static String awsErrorCode(software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    /** A 400 "stack does not exist" response marks successful completion. */
    private static boolean isMissingStack(CloudFormationException e) {
        AwsErrorDetails details = e.awsErrorDetails();
        int status = details != null && details.sdkHttpResponse() != null
            ? details.sdkHttpResponse().statusCode() : 0;
        String message = details != null ? details.errorMessage() : e.getMessage();
        return status == 400 && message != null && message.contains("does not exist");
    }

    // ========== Network resolution ==========

    /** Resolved VPC and subnet for a node. */
    private record Network(String vpcId, String subnetId) {
    }

    /**
     * Resolve the target VPC and subnet. Explicit values from {@link NodeSpec} win;
     * otherwise the region default VPC and one of its public subnets are discovered.
     */
    private Network resolveNetwork(NodeSpec spec) {
        Ec2Client ec2 = ec2ClientFor(spec.region());
        try {
            String vpcId = spec.vpcId();
            if (vpcId == null || vpcId.isBlank()) {
                DescribeVpcsResponse vpcs = ec2.describeVpcs(b -> b
                    .filters(software.amazon.awssdk.services.ec2.model.Filter.builder()
                        .name("isDefault").values("true").build()));
                Vpc defaultVpc = vpcs.vpcs().stream().findFirst()
                    .orElseThrow(() -> new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                        "No default VPC in region " + spec.region()
                            + "; configure tapstate.cloud.iac.aws.vpc-id"));
                vpcId = defaultVpc.vpcId();
            }

            String subnetId = spec.subnetId();
            if (subnetId == null || subnetId.isBlank()) {
                final String finalVpcId = vpcId;
                DescribeSubnetsResponse subnets = ec2.describeSubnets(b -> b
                    .filters(
                        software.amazon.awssdk.services.ec2.model.Filter.builder()
                            .name("vpc-id").values(finalVpcId).build(),
                        software.amazon.awssdk.services.ec2.model.Filter.builder()
                            .name("map-public-ip-on-launch").values("true").build()));
                Subnet selected = subnets.subnets().stream()
                    .skip(new java.security.SecureRandom().nextInt(Math.max(1, subnets.subnets().size())))
                    .findFirst()
                    .orElseThrow(() -> new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                        "No public subnet in VPC " + finalVpcId
                            + "; configure tapstate.cloud.iac.aws.subnet-id"));
                subnetId = selected.subnetId();
            }

            return new Network(vpcId, subnetId);
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                "network resolution failed: " + e.getMessage());
        }
    }

    /** Build or reuse the regional EC2 client. */
    private Ec2Client ec2ClientFor(String region) {
        return ec2ClientsByRegion.computeIfAbsent(region, key -> {
            var builder = Ec2Client.builder()
                .region(Region.of(key))
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(Duration.ofSeconds(30)));
            if (accessKeyId != null && !accessKeyId.isBlank()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            }
            return builder.build();
        });
    }

    // ========== Helpers ==========

    /** Build or reuse the regional CloudFormation client. */
    private CloudFormationClient clientFor(String region) {
        return clientsByRegion.computeIfAbsent(region, key -> {
            CloudFormationClientBuilder builder = CloudFormationClient.builder()
                .region(Region.of(key))
                .overrideConfiguration(c -> c.apiCallAttemptTimeout(Duration.ofSeconds(30)));
            if (accessKeyId != null && !accessKeyId.isBlank()) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKeyId, secretAccessKey)));
            }
            return builder.build();
        });
    }

    private static String stackName(String clusterId) {
        return STACK_NAME_PREFIX + clusterId;
    }

    private static Parameter cfParameter(String key, String value) {
        return Parameter.builder().parameterKey(key).parameterValue(value).build();
    }

    /** Temporarily allow port 22 from any source while the egress is unknown. */
    private static void openTemporarySshIngress(Ec2Client ec2, String securityGroupId) {
        try {
            ec2.authorizeSecurityGroupIngress(b -> b
                .groupId(securityGroupId)
                .ipPermissions(sshPermission(
                    List.of(software.amazon.awssdk.services.ec2.model.IpRange.builder()
                        .cidrIp("0.0.0.0/0").build()))));
            log.info("[AwsIaC] temporary SSH ingress opened sg={}", securityGroupId);
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            if ("InvalidPermission.Duplicate".equals(awsErrorCode(e))) return;
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                "failed to open temporary SSH ingress: " + e.getMessage());
        }
    }

    @Override
    public void restrictSshIngress(String region, String clusterId, List<String> cidrs) {
        CloudFormationClient cf = clientFor(region);
        Ec2Client ec2 = ec2ClientFor(region);
        String securityGroupId = securityGroupId(cf, stackName(clusterId));

        try {
            // Authorize the narrow rules first so a revocation hiccup cannot lock
            // the control plane out of a node still being provisioned.
            // A resumed run re-observes the same egress ranges, so an already
            // present rule is success, not a conflict.
            List<software.amazon.awssdk.services.ec2.model.IpRange> ranges = cidrs.stream()
                .map(cidr -> software.amazon.awssdk.services.ec2.model.IpRange.builder()
                    .cidrIp(cidr).build())
                .toList();
            try {
                ec2.authorizeSecurityGroupIngress(b -> b
                    .groupId(securityGroupId)
                    .ipPermissions(sshPermission(ranges)));
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                if (!"InvalidPermission.Duplicate".equals(ec2ErrorCode(e))) {
                    throw e;
                }
            }

            try {
                ec2.revokeSecurityGroupIngress(b -> b
                    .groupId(securityGroupId)
                    .ipPermissions(sshPermission(
                        List.of(software.amazon.awssdk.services.ec2.model.IpRange.builder()
                            .cidrIp("0.0.0.0/0").build()))));
            } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
                // A resumed run already dropped the wide-open rule; nothing to undo.
                if (!"InvalidPermission.NotFound".equals(ec2ErrorCode(e))) {
                    throw e;
                }
            }
            log.info("[AwsIaC] SSH ingress restricted sg={} cidrs={}", securityGroupId, cidrs);
        } catch (software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e,
                "failed to restrict SSH ingress: " + e.getMessage());
        }
    }

    /** AWS error code of an EC2 failure, or {@code null} when absent. */
    private static String ec2ErrorCode(software.amazon.awssdk.services.ec2.model.Ec2Exception e) {
        return e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
    }

    /** Build the single port-22 permission carrying the given ranges. */
    private static software.amazon.awssdk.services.ec2.model.IpPermission sshPermission(
        List<software.amazon.awssdk.services.ec2.model.IpRange> ranges) {
        return software.amazon.awssdk.services.ec2.model.IpPermission.builder()
            .ipProtocol("tcp")
            .fromPort(22)
            .toPort(22)
            .ipRanges(ranges)
            .build();
    }

    /** Resolve the node security group id from the stack resources. */
    private static String securityGroupId(CloudFormationClient cf, String stackName) {
        return cf.describeStackResources(b -> b.stackName(stackName))
            .stackResources().stream()
            .filter(resource -> "ClusterSecurityGroup".equals(resource.logicalResourceId()))
            .map(software.amazon.awssdk.services.cloudformation.model.StackResource::physicalResourceId)
            .findFirst()
            .orElseThrow(() -> new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
                "stack has no ClusterSecurityGroup: " + stackName));
    }

    private static Map<String, String> mapOutputs(Stack stack) {
        List<Output> outputs = stack.outputs();
        if (outputs == null) {
            return Map.of();
        }
        return outputs.stream()
            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                Output::outputKey, Output::outputValue, (first, second) -> first));
    }

    private static String loadTemplate() {
        try (InputStream in = new ClassPathResource(
            "io/tapstate/cloud/iac/aws/cluster-node.yaml").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IacException(AppErrorCode.SYSTEM_ERROR, e, "cannot load cluster node template");
        }
    }

    private static String awsMessage(CloudFormationException e) {
        AwsErrorDetails details = e.awsErrorDetails();
        return abbreviate(details != null && details.errorMessage() != null
            ? details.errorMessage() : e.getMessage());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() > 500 ? trimmed.substring(0, 500) : trimmed;
    }
}
