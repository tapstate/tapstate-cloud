package io.tapstate.cloud.config;

import io.tapstate.cloud.identity.User;
import io.tapstate.cloud.identity.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
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

    @Value("${tapstate.cloud.cors.allowed-origin-patterns}")
    private String corsAllowedOrigins;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            AuthenticationSuccessHandler oauthSuccessHandler,
            AuthenticationFailureHandler oauthFailureHandler) throws Exception {
        http
            // CSRF disabled — API uses cookie-based session (Spring Session handles cookie)
            .csrf(AbstractHttpConfigurer::disable)

            // CORS
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // Session management — NOW we WANT sessions (Spring Session MongoDB)
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            )

            // Disable form login and HTTP basic — we use custom /v1/api/auth/login endpoint
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable())

            // Logout handled by our custom /v1/api/auth/logout
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
                    "/v1/api/auth/register",
                    "/v1/api/auth/login",
                    "/v1/api/auth/email/**",
                    "/v1/api/auth/oauth/**",
                    "/v1/api/auth/verify-email",
                    "/oauth2/**",
                    "/login/oauth2/**",
                    // /v1/api/auth/exchange — Cluster Server backend calls this with per-Cluster
                    // service identity Secret (validated inside ExchangeController)
                    "/v1/api/auth/exchange",
                    "/v1/api/jwks.json",

                // C2 status-report endpoints — runtime SDK authenticates with Bearer sdk-static-token
                    // (validated inside the controller, NOT via Spring Session)
                    "/v1/api/clusters/*/status-report",
                    "/v1/api/clusters/*/sample-sources/**"
                ).permitAll()

                // Regions — public (user sees before login)
                .requestMatchers(HttpMethod.GET, "/v1/api/regions").permitAll()

                // Cluster version publish / delist — operator automation authenticates
                // with a static Bearer token inside the controller; the console GET list
                // of /v1/api/cluster-versions stays session-authenticated.
                .requestMatchers(HttpMethod.POST, "/v1/api/cluster-versions").permitAll()
                .requestMatchers(HttpMethod.PATCH, "/v1/api/cluster-versions/*/state").permitAll()

                // All other endpoints — authenticated
                .anyRequest().authenticated()
            )

            // Return 401 UNAUTHORIZED (not 403 FORBIDDEN) for unauthenticated requests
            // to protected endpoints — clearer semantics for API consumers
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) ->
                    response.sendError(HttpStatus.UNAUTHORIZED.value(), "Unauthorized"))
            );

        http.oauth2Login(oauth -> oauth
            .successHandler(oauthSuccessHandler)
            .failureHandler(oauthFailureHandler));

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
        config.setAllowedOriginPatterns(Arrays.stream(corsAllowedOrigins.split(","))
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList());
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
