package com.noveltea.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Throttles credential endpoints, and nothing else.
 *
 * <p>Applied by path so it cannot accidentally cover a sync route: the list is explicit
 * rather than a prefix, because {@code /auth/pairing-codes} is authenticated and belongs
 * to a signed-in user's normal flow.
 */
@Component
@ConditionalOnProperty(value = "noveltea.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private final SlidingWindowLimiter limiter;
    private final RateLimitProperties properties;
    private final ObjectMapper mapper;

    public AuthRateLimitFilter(
            SlidingWindowLimiter limiter, RateLimitProperties properties, ObjectMapper mapper) {
        this.limiter = limiter;
        this.properties = properties;
        this.mapper = mapper;
    }

    private Integer limitFor(String path) {
        return switch (path) {
            case "/api/v1/auth/login" -> properties.loginAttemptsPerWindow();
            case "/api/v1/auth/register" -> properties.registrationsPerWindow();
            case "/api/v1/auth/pair", "/api/v1/auth/refresh" -> properties.pairingAttemptsPerWindow();
            default -> null;
        };
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Integer limit = "POST".equals(request.getMethod()) ? limitFor(request.getRequestURI()) : null;
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }

        String key = request.getRequestURI() + "|" + clientAddress(request);
        if (!limiter.tryAcquire(key, limit, properties.window())) {
            long retryAfter = properties.window().toSeconds();
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of(
                    "too_many_requests",
                    "too many attempts; wait and try again",
                    request.getRequestURI(),
                    Map.of("retryAfterSeconds", retryAfter)));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Honours X-Forwarded-For only for its first entry.
     *
     * <p>Behind a reverse proxy the socket address is the proxy for every caller, which
     * would throttle the whole world together. The header is attacker-controlled, so this
     * is a limit that can be evaded by spoofing it — acceptable for slowing guessing, and
     * the reason this is not the only defence.
     */
    private static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
