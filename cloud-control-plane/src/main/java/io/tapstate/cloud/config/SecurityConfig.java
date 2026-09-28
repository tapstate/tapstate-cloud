package io.tapstate.cloud.config;

import io.tapstate.cloud.identity.User;
import io.tapstate.cloud.identity.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security configuration for Global Control Plane.
 * <p>
 * Authentication model (ADR-0122 D2 — simplified, no custom filters):
 * <ul>
 *   <li>Cloud Console session — TAPSTATE_CLOUD_SESSION cookie handled by Spring Session
 *       (MongoDB-backed, 7-day idle expiry)</li>
 *   <li>Authentication — DaoAuthenticationProvider + BCryptPasswordEncoder + UserDetailsService
 *       that loads User by email from MongoDB</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF disabled — API uses cookie-based session (Spring Session handles cookie)
            .csrf(AbstractHttpConfigurer::disable)

            // CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Session management — NOW we WANT sessions (Spring Session MongoDB)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

            // Disable form login and HTTP basic — we use custom /v1/auth/login endpoint
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // Logout handled by our custom /v1/auth/logout
            .logout(logout -> logout.disable())

            // Authorization rules
            .authorizeHttpRequests(auth -> auth
                // ========== Public (no auth required) ==========
                .requestMatchers(
                    "/health",
                    "/favicon.ico",
                    "/v3/api-docs/**",
                    "/v3/api-docs.yaml",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/knife4j/**",
                    "/actuator/health/**",
                    "/actuator/info",
                    "/v1/auth/register",
                    "/v1/auth/login",
                    "/v1/auth/verify-email",
                    // /v1/auth/exchange — Cluster Server backend calls this with per-Cluster
                    // service identity Secret (validated inside ExchangeController)
                    "/v1/auth/exchange",
                    "/v1/jwks.json",

                // C2 status-report endpoints — runtime SDK authenticates with Bearer sdk-static-token
                    // (validated inside the controller, NOT via Spring Session)
                    "/v1/clusters/*/status-report"
                ).permitAll()

                // Regions — public (user sees before login)
                .requestMatchers(HttpMethod.GET, "/v1/regions").permitAll()

                // All other endpoints — authenticated
                .anyRequest().authenticated()
            )

            // Return 401 UNAUTHORIZED (not 403 FORBIDDEN) for unauthenticated requests
            // to protected endpoints — clearer semantics for API consumers
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized"))
            );

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public UserDetailsService userDetailsService() {
        return email -> {
            User user = userRepository.findByEmail(email.toLowerCase())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + email));

            return new org.springframework.security.core.userdetails.User(
                user.getEmail(),
                user.getPasswordHash(),
                "active".equals(user.getStatus()),   // enabled
                true,    // accountNonExpired
                true,    // credentialsNonExpired
                true,    // accountNonLocked
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
            );
        };
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*")); // TODO: restrict in production
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
