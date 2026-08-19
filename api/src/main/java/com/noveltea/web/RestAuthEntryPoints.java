package com.noveltea.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Makes the security filter chain speak the same error dialect as the controllers.
 *
 * <p>Without these, Spring answers an unauthenticated request with 403, which conflates
 * two different problems: "you did not present credentials" (401 — retry after signing
 * in) and "your credentials are fine but insufficient" (403 — signing in again will not
 * help). Clients need to tell those apart to decide whether to refresh a token.
 */
@Component
public class RestAuthEntryPoints {

    private final ObjectMapper mapper;

    public RestAuthEntryPoints(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public AuthenticationEntryPoint unauthenticated() {
        return (request, response, authException) ->
                write(request, response, HttpServletResponse.SC_UNAUTHORIZED,
                        "unauthenticated", "authentication required");
    }

    public AccessDeniedHandler forbidden() {
        return (request, response, deniedException) ->
                write(request, response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "forbidden");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
            int status, String error, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(),
                ApiError.of(error, message, request.getRequestURI()));
    }
}
