package io.tapstate.cloud.identity;

import io.tapstate.cloud.config.JwksRotationService;
import io.tapstate.cloud.config.LegalConfig;
import io.tapstate.cloud.resolve.CallbackSignService;
import io.tapstate.cloud.resolve.JtiRecordRepository;
import io.tapstate.cloud.resolve.LocalClusterEndpoints;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthEmailLoginTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void verifiedCodeCreatesTheSameAuthenticatedSessionAsPasswordLogin() {
        UserRepository users = mock(UserRepository.class);
        EmailVerificationService codes = mock(EmailVerificationService.class);
        LegalConfig legal = mock(LegalConfig.class);
        User user = User.builder().email("member@example.com").displayName("Member").status("active").build();
        when(codes.verifyLoginCode("member@example.com", "123456")).thenReturn(user);
        when(legal.getCurrentVersion()).thenReturn("legal-v1");
        AuthController controller = new AuthController(users, codes, mock(InvitationService.class),
            mock(TrialCodePolicy.class), mock(AuthenticationManager.class), mock(JtiRecordRepository.class),
            new CallbackSignService(mock(JwksRotationService.class)), mock(LocalClusterEndpoints.class),
            mock(ClientRegistrationRepository.class), legal);
        MockHttpServletRequest request = new MockHttpServletRequest();

        var response = controller.loginWithEmailCode(new AuthController.EmailLoginRequest("member@example.com", "123456"), request);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("member@example.com", request.getSession().getAttribute(AuthController.SESSION_USER_EMAIL));
        assertEquals("member@example.com", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(users).save(user);
    }
}
