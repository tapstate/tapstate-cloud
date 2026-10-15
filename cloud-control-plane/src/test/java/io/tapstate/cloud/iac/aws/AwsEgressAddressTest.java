package io.tapstate.cloud.iac.aws;

import io.tapstate.cloud.iac.IacException;
import io.tapstate.cloud.iac.model.NodeSpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AwsEgressAddressTest {
    private static final String REGION = "us-east-1";
    private static final String CLUSTER = "6475a2b3c1d4e5f6a7b8c9d1";
    private static final String ALLOCATION = "eipalloc-owned";
    private static final String IP = "203.0.113.42";
    private Ec2Client ec2;
    private CloudFormationClient cf;
    private AwsComputeInfrastructure compute;

    @BeforeEach
    void setUp() {
        org.mockito.stubbing.Answer<Object> builders = invocation ->
            invocation.getArguments().length == 1 && invocation.getArgument(0) instanceof java.util.function.Consumer<?>
                ? invocation.callRealMethod() : RETURNS_DEFAULTS.answer(invocation);
        ec2 = mock(Ec2Client.class, builders);
        cf = mock(CloudFormationClient.class, builders);
        compute = new AwsComputeInfrastructure("", "");
        ReflectionTestUtils.setField(compute, "clientsByRegion", new ConcurrentHashMap<>(Map.of(REGION, cf)));
        ReflectionTestUtils.setField(compute, "ec2ClientsByRegion", new ConcurrentHashMap<>(Map.of(REGION, ec2)));
        when(cf.describeStacks(any(DescribeStacksRequest.class))).thenReturn(stack("i-replacement"));
        when(ec2.describeAddresses(any(DescribeAddressesRequest.class)))
            .thenReturn(DescribeAddressesResponse.builder().addresses(address("i-original")).build());
        when(ec2.associateAddress(any(AssociateAddressRequest.class)))
            .thenReturn(AssociateAddressResponse.builder().associationId("eipassoc-new").build());
    }

    @Test
    void replacementReassociatesTheOriginalAllocationWithoutAllocatingAnotherAddress() {
        var node = compute.stabilizeEgressAddress(REGION, CLUSTER, ALLOCATION);
        assertThat(node.instanceId()).isEqualTo("i-replacement");
        assertThat(node.egressAddressAllocationId()).isEqualTo(ALLOCATION);
        assertThat(node.publicIp()).isEqualTo(IP);
        var request = ArgumentCaptor.forClass(AssociateAddressRequest.class);
        verify(ec2).associateAddress(request.capture());
        assertThat(request.getValue().allocationId()).isEqualTo(ALLOCATION);
        assertThat(request.getValue().instanceId()).isEqualTo("i-replacement");
        assertThat(request.getValue().allowReassociation()).isTrue();
        verify(ec2, never()).allocateAddress(any(AllocateAddressRequest.class));
        assertClusterFilter();
    }

    @Test
    void repeatedAttachmentToTheSameNodeDoesNotRemapOrAllocate() {
        when(ec2.describeAddresses(any(DescribeAddressesRequest.class)))
            .thenReturn(DescribeAddressesResponse.builder().addresses(address("i-replacement")).build());
        assertThat(compute.stabilizeEgressAddress(REGION, CLUSTER, ALLOCATION).publicIp()).isEqualTo(IP);
        assertThat(compute.stabilizeEgressAddress(REGION, CLUSTER, ALLOCATION).publicIp()).isEqualTo(IP);
        verify(ec2, never()).associateAddress(any(AssociateAddressRequest.class));
        verify(ec2, never()).allocateAddress(any(AllocateAddressRequest.class));
    }

    @Test
    void missingPinnedAllocationFailsInsteadOfChangingTheAllowlistedIdentity() {
        when(ec2.describeAddresses(any(DescribeAddressesRequest.class)))
            .thenReturn(DescribeAddressesResponse.builder().build());
        when(ec2.allocateAddress(any(AllocateAddressRequest.class)))
            .thenReturn(AllocateAddressResponse.builder().allocationId("eipalloc-new").publicIp("203.0.113.99").build());
        assertThatThrownBy(() -> compute.stabilizeEgressAddress(REGION, CLUSTER, ALLOCATION))
            .isInstanceOf(IacException.class).hasMessageContaining("allocation");
        verify(ec2, never()).allocateAddress(any(AllocateAddressRequest.class));
    }

    @Test
    void failedAttachmentDuringProvisioningRetainsTheTaggedAllocationForRetry() {
        when(cf.createStack(any(CreateStackRequest.class))).thenReturn(CreateStackResponse.builder().build());
        when(ec2.authorizeSecurityGroupIngress(any(AuthorizeSecurityGroupIngressRequest.class)))
            .thenReturn(AuthorizeSecurityGroupIngressResponse.builder().build());
        when(ec2.associateAddress(any(AssociateAddressRequest.class)))
            .thenThrow(ec2Failure("UnauthorizedOperation"));
        when(cf.deleteStack(any(DeleteStackRequest.class))).thenAnswer(invocation -> {
            when(cf.describeStacks(any(DescribeStacksRequest.class))).thenThrow(missingStack());
            return DeleteStackResponse.builder().build();
        });
        assertThatThrownBy(() -> compute.provision(spec())).isInstanceOf(IacException.class);
        verify(ec2, never()).releaseAddress(any(ReleaseAddressRequest.class));
    }

    @Test
    void repeatedProvisionUsesTheExistingStackAndAddress() {
        when(cf.createStack(any(CreateStackRequest.class)))
            .thenThrow(CloudFormationException.builder().message("already exists").build());
        when(ec2.authorizeSecurityGroupIngress(any(AuthorizeSecurityGroupIngressRequest.class)))
            .thenReturn(AuthorizeSecurityGroupIngressResponse.builder().build());
        assertThat(compute.provision(spec()).publicIp()).isEqualTo(IP);
        verify(cf, never()).createStack(any(CreateStackRequest.class));
        verify(ec2, never()).allocateAddress(any(AllocateAddressRequest.class));
    }

    @Test
    void repeatedProvisionAcceptsAnExistingSshRuleWithoutDeletingTheNode() {
        when(ec2.authorizeSecurityGroupIngress(any(AuthorizeSecurityGroupIngressRequest.class)))
            .thenThrow(ec2Failure("InvalidPermission.Duplicate"));
        when(cf.deleteStack(any(DeleteStackRequest.class))).thenAnswer(invocation -> {
            when(cf.describeStacks(any(DescribeStacksRequest.class))).thenThrow(missingStack());
            return DeleteStackResponse.builder().build();
        });
        assertThat(compute.provision(spec()).publicIp()).isEqualTo(IP);
        verify(cf, never()).deleteStack(any(DeleteStackRequest.class));
    }

    @Test
    void failureWhileReusingANodeDoesNotCompensateAnotherAttemptsStack() {
        when(ec2.associateAddress(any(AssociateAddressRequest.class)))
            .thenThrow(ec2Failure("UnauthorizedOperation"));
        when(cf.deleteStack(any(DeleteStackRequest.class))).thenAnswer(invocation -> {
            when(cf.describeStacks(any(DescribeStacksRequest.class))).thenThrow(missingStack());
            return DeleteStackResponse.builder().build();
        });
        assertThatThrownBy(() -> compute.provision(spec())).isInstanceOf(IacException.class);
        verify(cf, never()).deleteStack(any(DeleteStackRequest.class));
    }

    @Test
    void destroyAnAlreadyMissingStackStillReleasesOnlyClusterTaggedAddresses() {
        when(cf.describeStacks(any(DescribeStacksRequest.class))).thenThrow(missingStack());
        when(ec2.disassociateAddress(any(DisassociateAddressRequest.class)))
            .thenReturn(DisassociateAddressResponse.builder().build());
        when(ec2.releaseAddress(any(ReleaseAddressRequest.class))).thenReturn(ReleaseAddressResponse.builder().build());
        compute.terminate(REGION, CLUSTER);
        var request = ArgumentCaptor.forClass(ReleaseAddressRequest.class);
        verify(ec2).releaseAddress(request.capture());
        assertThat(request.getValue().allocationId()).isEqualTo(ALLOCATION);
        assertClusterFilter();
    }

    @Test
    void releaseFailureIsObservableAndCanBeRetried() {
        when(cf.describeStacks(any(DescribeStacksRequest.class))).thenThrow(missingStack());
        when(ec2.disassociateAddress(any(DisassociateAddressRequest.class)))
            .thenReturn(DisassociateAddressResponse.builder().build());
        when(ec2.releaseAddress(any(ReleaseAddressRequest.class)))
            .thenThrow(ec2Failure("UnauthorizedOperation")).thenReturn(ReleaseAddressResponse.builder().build());
        assertThatThrownBy(() -> compute.terminate(REGION, CLUSTER)).isInstanceOf(IacException.class);
        compute.terminate(REGION, CLUSTER);
        verify(ec2, times(2)).releaseAddress(any(ReleaseAddressRequest.class));
    }

    private void assertClusterFilter() {
        var request = ArgumentCaptor.forClass(DescribeAddressesRequest.class);
        verify(ec2, atLeastOnce()).describeAddresses(request.capture());
        assertThat(request.getAllValues()).allSatisfy(value -> {
            assertThat(value.filters()).singleElement().satisfies(filter -> {
                assertThat(filter.name()).isEqualTo("tag:io.tapstate/cluster-id");
                assertThat(filter.values()).containsExactly(CLUSTER);
            });
        });
    }

    private static NodeSpec spec() {
        return new NodeSpec(CLUSTER, REGION, "t3.medium", 40, "ssh-public", List.of(), Map.of(), "vpc-1", "subnet-1");
    }

    private static DescribeStacksResponse stack(String instance) {
        return DescribeStacksResponse.builder().stacks(software.amazon.awssdk.services.cloudformation.model.Stack.builder()
            .stackStatus(StackStatus.CREATE_COMPLETE).outputs(
                output("InstanceId", instance), output("PublicIp", "198.51.100.1"),
                output("PrivateIp", "10.0.0.1"), output("SecurityGroupId", "sg-1")).build()).build();
    }

    private static Output output(String key, String value) {
        return Output.builder().outputKey(key).outputValue(value).build();
    }

    private static Address address(String instance) {
        return Address.builder().allocationId(ALLOCATION).publicIp(IP).instanceId(instance)
            .associationId("eipassoc-old").build();
    }

    private static Ec2Exception ec2Failure(String code) {
        var builder = Ec2Exception.builder();
        builder.message(code).awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build());
        return (Ec2Exception) builder.build();
    }

    private static CloudFormationException missingStack() {
        var builder = CloudFormationException.builder();
        builder.statusCode(400).message("Stack does not exist")
            .awsErrorDetails(AwsErrorDetails.builder().errorMessage("Stack does not exist")
                .sdkHttpResponse(software.amazon.awssdk.http.SdkHttpResponse.builder().statusCode(400).build()).build());
        return (CloudFormationException) builder.build();
    }
}
