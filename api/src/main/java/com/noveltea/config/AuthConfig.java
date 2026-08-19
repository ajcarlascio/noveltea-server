package com.noveltea.config;

import com.noveltea.auth.AuthProperties;
import com.noveltea.compile.CompileProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auth beans that are not web-specific.
 *
 * <p>Kept separate from {@link SecurityConfig}, which only exists in a servlet context:
 * token issuing and password hashing are needed by service-layer tests and any future
 * non-web entry point too.
 */
@Configuration
@EnableConfigurationProperties({AuthProperties.class, LimitProperties.class, CompileProperties.class})
public class AuthConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
