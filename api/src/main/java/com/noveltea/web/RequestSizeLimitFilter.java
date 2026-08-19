package com.noveltea.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.config.LimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Refuses bodies above the configured ceiling with a 413.
 *
 * <p>Without it an oversized request is dropped at the transport layer, which the client
 * sees as a network failure and retries — forever, since it will never succeed. A status
 * code says what actually happened.
 *
 * <p>The ceiling is deliberately high: some authors keep an entire novel in a single
 * document, and 200,000 words is several megabytes of ProseMirror JSON. This exists to
 * stop something absurd, not to police normal writing.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final LimitProperties limits;
    private final ObjectMapper mapper;

    public RequestSizeLimitFilter(LimitProperties limits, ObjectMapper mapper) {
        this.limits = limits;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        long declared = request.getContentLengthLong();
        if (declared > limits.maxRequestBytes()) {
            response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), ApiError.of(
                    "payload_too_large",
                    "this request is larger than the server accepts",
                    request.getRequestURI(),
                    Map.of("maxBytes", limits.maxRequestBytes(), "declaredBytes", declared)));
            return;
        }
        chain.doFilter(request, response);
    }
}
