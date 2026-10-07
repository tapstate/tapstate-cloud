package io.tapstate.cloud.iac;

import io.tapstate.cloud.common.api.AppErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cause-carrying constructor must surface the operational detail passed as
 * the first parameter instead of reducing every failure to the symbolic code,
 * otherwise cloud API reasons (auth, quota, template) become undiagnosable.
 */
class IacExceptionTest {

    @Test
    void causeConstructorUsesFirstParamAsMessage() {
        Exception cause = new IllegalStateException("InvalidClientTokenId");

        IacException ex = new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
            cause, "User: arn:aws:iam::1:user/x is not authorized");

        assertThat(ex.getMessage()).isEqualTo("User: arn:aws:iam::1:user/x is not authorized");
        assertThat(ex.errorCode()).isEqualTo(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED);
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex.params()[0]).isEqualTo("User: arn:aws:iam::1:user/x is not authorized");
    }

    @Test
    void causeConstructorFallsBackToCodeWithoutDetail() {
        IacException ex = new IacException(AppErrorCode.SYSTEM_ERROR, new RuntimeException());

        assertThat(ex.getMessage()).isEqualTo(AppErrorCode.SYSTEM_ERROR.getCode());
        assertThat(ex.params()).isEmpty();
    }

    @Test
    void messageConstructorKeepsBareMessage() {
        IacException ex = new IacException(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED,
            "No default VPC in region ap-southeast-1");

        assertThat(ex.getMessage()).isEqualTo("No default VPC in region ap-southeast-1");
        assertThat(ex.errorCode()).isEqualTo(AppErrorCode.IAC_COMPUTE_PROVISION_FAILED);
    }
}
