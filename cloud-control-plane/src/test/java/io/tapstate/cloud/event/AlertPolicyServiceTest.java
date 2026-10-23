package io.tapstate.cloud.event;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertPolicyServiceTest {

    @Mock
    private AlertPolicyRepository policies;

    private AlertPolicyService service;

    private final ObjectId orgId = new ObjectId();

    @BeforeEach
    void setUp() {
        service = new AlertPolicyService(policies);
    }

    @Test
    @DisplayName("a mailbox outside the organization is accepted as an alert recipient")
    void acceptsExternalRecipient() {
        when(policies.countByOrgId(orgId)).thenReturn(0L);
        when(policies.save(any(AlertPolicy.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AlertPolicy policy = service.create(orgId,
            request(List.of(" OnCall@Vendor.Example ", "oncall@vendor.example")));

        assertThat(policy.getRecipients()).containsExactly("oncall@vendor.example");
    }

    @Test
    @DisplayName("a malformed recipient is rejected before the policy is stored")
    void rejectsMalformedRecipient() {
        when(policies.countByOrgId(orgId)).thenReturn(0L);

        assertThatThrownBy(() -> service.create(orgId, request(List.of("not-an-email"))))
            .isInstanceOf(BusinessException.class)
            .satisfies(throwable -> assertThat(((BusinessException) throwable).getErrorCode())
                .isEqualTo(AppErrorCode.ALERT_POLICY_INVALID));
        verify(policies, never()).save(any());
    }

    private AlertPolicyRequest request(List<String> recipients) {
        return new AlertPolicyRequest("Policy", true, List.of("cluster.task.*"),
            EventSeverity.WARNING, recipients, "EMAIL", 0L, null, null, null);
    }
}
