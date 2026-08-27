package com.noveltea.config;

import com.noveltea.auth.JwtAuthenticationFilter;
import com.noveltea.auth.PasswordChangeRequiredFilter;
import com.noveltea.web.RestAuthEntryPoints;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfig {

    /**
     * Stateless bearer-token auth. Everything under /api/v1 requires a valid access token
     * except the auth endpoints themselves, which are the only way to obtain one.
     */
    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http, JwtAuthenticationFilter jwtFilter,
            PasswordChangeRequiredFilter passwordChangeFilter, RestAuthEntryPoints entryPoints,
            CorsProperties corsProperties)
            throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource(corsProperties)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/health", "/health/ready").permitAll()
                        // Off by default (see application.yml); permitted here so that when
                        // an operator does turn them on, they are reachable without a token —
                        // a route needing auth to view its own documentation is not useful.
                        // Deliberately outside /api/v1, so IdorSweepTest's invariant (nothing
                        // under that prefix is reachable unauthenticated) is untouched by them.
                        .requestMatchers(
                                "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs.json",
                                "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/pair",
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/password-reset",
                                "/api/v1/auth/password-reset/confirm")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoints.unauthenticated())
                        .accessDeniedHandler(entryPoints.forbidden()))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // After the principal exists and before any handler runs, so an account
                // holding a password somebody else chose cannot reach a route at all.
                .addFilterAfter(passwordChangeFilter, JwtAuthenticationFilter.class)
                .build();
    }

    /**
     * Keeps the servlet container from registering the lock a second time.
     *
     * <p>Spring Boot registers every {@code Filter} bean with the container automatically,
     * so a filter meant for the security chain ends up in two places: once where it was
     * put, and once at the end of the servlet chain where it was not. {@code
     * OncePerRequestFilter} hides that — the first copy to run marks the request and the
     * second skips — which is exactly the problem: deleting the {@code addFilterAfter}
     * line above changes nothing observable, so nothing stops a later refactor from moving
     * this out of the chain and leaving it to run somewhere its principal may already have
     * been cleared. Disabling the automatic registration makes that line the only one, and
     * removing it a change that fails a test.
     */
    @Bean
    public FilterRegistrationBean<PasswordChangeRequiredFilter> passwordChangeFilterStaysInTheChain(
            PasswordChangeRequiredFilter filter) {
        FilterRegistrationBean<PasswordChangeRequiredFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Cross-origin access for a separately deployed browser client.
     *
     * <p>Built here rather than as its own bean, because Boot also contributes a
     * {@code CorsConfigurationSource} and two of them is an ambiguous injection.
     *
     * <p>Origins are listed explicitly and never wildcarded. With none configured, no
     * cross-origin request is permitted, which is correct when the API and the app share an
     * origin; a separate front-end deployment must list its own.
     */
    private static CorsConfigurationSource corsSource(CorsProperties properties) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (properties.allowedOrigins().isEmpty()) {
            return source;
        }
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Content-Disposition", "Retry-After"));
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
