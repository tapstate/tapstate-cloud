package io.tapstate.cloud.identity;

import io.tapstate.cloud.common.api.AppErrorCode;
import io.tapstate.cloud.common.api.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authentication service — registration only.
 * <p>
 * Login/logout/session management is handled by Spring Session + Spring Security.
 * This service only persists new User accounts (email + BCrypt password).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // ========== Registration ==========

    /**
     * Register a new user with email + password.
     * Email verification via magic link — skipped in Week 2 preview (auto-verified).
     */
    @Transactional
    public User register(String email, String password, String displayName) {
        if (userRepository.existsByEmail(email)) {
            throw new BusinessException(AppErrorCode.EMAIL_ALREADY_REGISTERED, 409, email);
        }

        User user = User.builder()
            .email(email.toLowerCase())
            .passwordHash(passwordEncoder.encode(password))
            .displayName(displayName != null ? displayName : email.split("@")[0])
            .emailVerified(true) // Week 2 preview: auto-verified
            .status("active")
            .build();

        User saved = userRepository.save(user);
        log.info("User registered: {}", saved.getEmail());
        return saved;
    }
}
