package com.noveltea.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects NUL bytes in the query string.
 *
 * <p>Postgres {@code text} cannot hold {@code U+0000}: the driver throws, and without this
 * the request reaches the catch-all handler and reports 500 — telling the client to retry
 * something that can never succeed, and burying a real fault in the same log line.
 *
 * <p>Reads the raw query string rather than the parameter map, because touching parameters
 * would consume the body of a form-encoded request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class NulByteRejectingFilter extends OncePerRequestFilter {

    private final ObjectMapper mapper;

    public NulByteRejectingFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String query = request.getQueryString();
        if (query != null && containsNul(query)) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of(
                    "bad_request",
                    "a null character is not valid text",
                    request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
    }

    /** Catches both a literal NUL and a percent-encoded one. */
    private static boolean containsNul(String query) {
        if (query.indexOf('\0') >= 0) {
            return true;
        }
        try {
            return URLDecoder.decode(query, StandardCharsets.UTF_8).indexOf('\0') >= 0;
        } catch (IllegalArgumentException e) {
            return false; // malformed encoding is someone else's 400 to raise
        }
    }
}
