package com.noveltea.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Turns a bearer access token into a {@link CurrentUser} principal.
 *
 * <p>A malformed or expired token leaves the context unauthenticated rather than
 * throwing: the authorization rules decide what an anonymous caller may do, so that
 * decision stays in one place.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final TokenService tokens;

    public JwtAuthenticationFilter(TokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jwt jwt = tokens.verifyAccessToken(header.substring(7).trim());
                UUID userId = UUID.fromString(jwt.getSubject());
                String deviceClaim = jwt.getClaimAsString(TokenService.DEVICE_CLAIM);
                UUID deviceId = deviceClaim == null ? null : UUID.fromString(deviceClaim);

                boolean mustChangePassword =
                        Boolean.TRUE.equals(jwt.getClaim(TokenService.PASSWORD_CHANGE_CLAIM));

                CurrentUser principal = new CurrentUser(userId, deviceId, mustChangePassword);
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
