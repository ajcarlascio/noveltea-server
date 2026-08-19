package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveltea.config.LimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Driven directly with a mocked request, because MockMvc and MockHttpServletRequest both
 * derive Content-Length from the body they were handed and so cannot express "the client
 * declared something enormous" without actually allocating it.
 */
class RequestSizeLimitFilterTest {

    private final LimitProperties limits =
            new LimitProperties(null, null, null, null, null, null, null, null, null);
    // ApiError carries an OffsetDateTime; Boot's configured mapper knows how to write one
    // and a bare ObjectMapper does not.
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(limits, mapper);

    private MockHttpServletResponse run(long declaredLength) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getContentLengthLong()).thenReturn(declaredLength);
        when(request.getRequestURI()).thenReturn("/api/v1/projects");

        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> ((MockHttpServletResponse) res).setStatus(200);
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    @DisplayName("a body above the ceiling is refused with 413")
    void oversizedIsRefused() throws Exception {
        MockHttpServletResponse response = run(64L * 1024 * 1024);

        assertThat(response.getStatus())
                .as("dropping the connection instead looks like a network fault and gets retried")
                .isEqualTo(413);
        assertThat(response.getContentAsString()).contains("payload_too_large").contains("maxBytes");
    }

    @Test
    @DisplayName("a large but permitted body passes through")
    void largeButAllowedPasses() throws Exception {
        assertThat(run(8L * 1024 * 1024).getStatus())
                .as("an entire novel in one document is several megabytes and must still work")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown length is not blocked")
    void unknownLengthPasses() throws Exception {
        assertThat(run(-1).getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the ceiling is high enough for a full-length manuscript")
    void ceilingIsGenerous() {
        assertThat(limits.maxRequestBytes())
                .as("200,000 words of ProseMirror JSON is several megabytes")
                .isGreaterThanOrEqualTo(32 * 1024 * 1024);
    }
}
