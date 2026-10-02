package io.tapstate.cloud.iac.aws;

import io.tapstate.cloud.iac.CloudProvider;
import io.tapstate.cloud.iac.ComputeInfrastructure;
import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.model.IngressRule;
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
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackStatus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AWS implementation of {@link ComputeInfrastructure}.
 * <p>
 * All node resources live in one CloudFormation stack rendered from a classpath
 * template. Provision creates the stack and polls to completion; termination
 * deletes it and polls until the stack no longer exists, so an instance, its
 * security group and its disk are always reclaimed as a unit.
 */
@Component
@Slf4j
public class AwsComputeInfrastructure implements ComputeInfrastructure {

    private static final String TEMPLATE_RESOURCE = "/io/tapstate/cloud/iac/aws/cluster-node.yaml";
    private static final String STACK_NAME_PREFIX = "tapstate-cluster-";
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

        try {
            String sshCidr = sshCidr(spec);
            cf.createStack(b -> b
                .stackName(stackName)
                .templateBody(loadTemplate())
                .parameters(
                    cfParameter("ClusterId", spec.clusterId()),
                    cfParameter("InstanceType", spec.instanceType()),
                    cfParameter("DiskSize", String.valueOf(spec.diskSizeGb())),
                    cfParameter("SshPublicKey", spec.sshPublicKey()),
                    cfParameter("SshCidr", sshCidr)));
            log.info("[AwsIaC] stack create requested stack={} region={}", stackName, spec.region());
        } catch (CloudFormationException e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED, e, awsMessage(e));
        }

        Stack stack = awaitStatus(stackName, cf, CREATE_SUCCESS, CREATE_FAILED_STATES,
            STACK_CREATE_TIMEOUT, AppErrorCode.IAC_COMPUTE_PROVISION_FAILED);

        Map<String, String> outputs = mapOutputs(stack);
        String instanceId = outputs.get("InstanceId");
        String publicIp = outputs.get("PublicIp");
        String privateIp = outputs.get("PrivateIp");
        return new NodeInstance(CloudProvider.AWS, spec.clusterId(), instanceId, publicIp, privateIp);
    }

    @Override
    public void terminate(String region, String clusterId) {
        String stackName = stackName(clusterId);
        CloudFormationClient cf = clientFor(region);

        if (!stackExists(stackName, cf)) {
            log.info("[AwsIaC] stack already absent stack={}", stackName);
            return;
        }

        try {
            cf.deleteStack(b -> b.stackName(stackName));
            log.info("[AwsIaC] stack delete requested stack={}", stackName);
        } catch (CloudFormationException e) {
            throw new IacException(AppErrorCode.IAC_COMPUTE_TERMINATE_FAILED, e, awsMessage(e));
        }

        awaitDeletion(stackName, cf);
    }

    @Override
    public CloudProvider provider() {
        return CloudProvider.AWS;
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
                    "stack " + status + ": " + abbreviate(stack.stackStatusReason()));
            }
            sleep(POLL_INTERVAL);
        }
        throw new IacException(failureCode,
            "stack did not finish within " + timeout + " (" + stackName + ")");
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
                        "stack " + status + ": " + abbreviate(stack.stackStatusReason()));
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

    /** A 400 "stack does not exist" response marks successful completion. */
    private static boolean isMissingStack(CloudFormationException e) {
        AwsErrorDetails details = e.awsErrorDetails();
        int status = details != null && details.sdkHttpResponse() != null
            ? details.sdkHttpResponse().statusCode() : 0;
        String message = details != null ? details.errorMessage() : e.getMessage();
        return status == 400 && message != null && message.contains("does not exist");
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

    /** Extract the SSH source CIDR from the generic ingress rules. */
    private static String sshCidr(NodeSpec spec) {
        Optional<String> cidr = spec.ingressRules().stream()
            .filter(rule -> "tcp".equalsIgnoreCase(rule.protocol())
                && rule.fromPort() == 22 && rule.toPort() == 22)
            .map(IngressRule::cidr)
            .findFirst();
        return cidr.orElseThrow(() -> new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
            "NodeSpec must provide a port-22 ingress CIDR"));
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
