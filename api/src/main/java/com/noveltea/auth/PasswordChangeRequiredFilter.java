package com.noveltea.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Holds an account at the door until it has a password its holder chose.
 *
 * <p>Enforced here rather than in the client, because "the app shows a change-password
 * screen" is not a control: the token works, and anything holding it can call the API
 * directly. On a first-run instance that token belongs to an account whose password is
 * printed in the startup log, so this is the difference between a default credential that
 * is a formality and one that is a way in.
 *
 * <p>Runs after {@link JwtAuthenticationFilter}, so the principal is already resolved, and
 * before authorization, so a caller in this state cannot reach a handler at all. Only
 * {@link #ESCAPE_HATCH} is allowed through — deliberately one route and not a prefix,
 * because {@code /api/v1/account/deletion} sits next to it and letting a locked account
 * schedule the destruction of an instance's projects is not the intent.
 *
 * <p>403 rather than 401: the credentials are valid and presenting them again changes
 * nothing, which is exactly the distinction {@code RestAuthEntryPoints} exists to keep.
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** The only thing an account in this state may do, and the thing that ends the state. */
    public static final String ESCAPE_HATCH = "/api/v1/account/password";

    public static final String ERROR_CODE = "password_change_required";

    private final ObjectMapper mapper;

    public PasswordChangeRequiredFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean locked = authentication != null
                && authentication.getPrincipal() instanceof CurrentUser user
                && user.mustChangePassword();

        if (!locked || isEscapeHatch(request)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of(
                ERROR_CODE,
                "choose a new password before using this account",
                request.getRequestURI()));
    }

    private static boolean isEscapeHatch(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && ESCAPE_HATCH.equals(request.getRequestURI());
    }
}
