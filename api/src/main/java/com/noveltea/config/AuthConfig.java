package com.noveltea.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auth beans that are not web-specific.
 *
 * <p>Kept separate from {@code SecurityConfig}, which only exists in a servlet context:
 * password hashing is needed by service-layer tests and any future non-web entry point.
 *
 * <p>Configuration property records are registered by {@code @ConfigurationPropertiesScan}
 * on the application class rather than listed here.
 */
@Configuration
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
