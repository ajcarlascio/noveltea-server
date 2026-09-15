package com.noveltea.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.noveltea.config.LimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
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

    // ------------------------------------------------------ chunked transfers

    /**
     * A small ceiling, so these can exceed it without allocating 32MB per assertion.
     */
    private static final int SMALL = 64;

    private final RequestSizeLimitFilter smallFilter = new RequestSizeLimitFilter(
            new LimitProperties(null, null, null, null, null, null, null, null, SMALL), mapper);

    /**
     * A request that declares no length, which is what {@code Transfer-Encoding: chunked}
     * produces. {@code MockHttpServletRequest} derives the length from the body it holds,
     * so the one thing this test is about has to be overridden.
     */
    private static MockHttpServletRequest chunked(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContent(body);
        return request;
    }

    /** Reads the body the way a message converter would, through the wrapped request. */
    private byte[] readThrough(RequestSizeLimitFilter filter, MockHttpServletRequest request)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        byte[][] seen = new byte[1][];
        filter.doFilter(request, response, (req, res) ->
                seen[0] = ((HttpServletRequest) req).getInputStream().readAllBytes());
        return seen[0];
    }

    @Test
    @DisplayName("a chunked body past the ceiling is refused rather than read to the end")
    void chunkedBodyIsCounted() {
        // The whole point: with no Content-Length there is nothing to check up front, so
        // a ceiling that only reads the header is a ceiling any client opts out of by
        // setting one header.
        byte[] body = new byte[SMALL * 4];

        assertThatThrownBy(() -> readThrough(smallFilter, chunked(body)))
                .isInstanceOf(RequestSizeLimitFilter.BodyTooLarge.class)
                .satisfies(e -> assertThat(
                        ((RequestSizeLimitFilter.BodyTooLarge) e).maxBytes()).isEqualTo(SMALL));
    }

    @Test
    @DisplayName("a chunked body within the ceiling is delivered unchanged")
    void chunkedBodyWithinLimitPassesThrough() throws Exception {
        byte[] body = "{\"changes\":[]}".getBytes(StandardCharsets.UTF_8);

        assertThat(readThrough(smallFilter, chunked(body)))
                .as("counting a body must not alter it")
                .isEqualTo(body);
    }

    @Test
    @DisplayName("a body exactly at the ceiling is accepted")
    void chunkedBodyAtTheLimitIsAccepted() throws Exception {
        assertThat(readThrough(smallFilter, chunked(new byte[SMALL]))).hasSize(SMALL);
    }

    @Test
    @DisplayName("the count is of the whole body, not of any single read")
    void manySmallReadsStillAddUp() {
        // Read one byte at a time, so no individual read is anywhere near the ceiling.
        // A check written per-read rather than per-request passes this by accident.
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockHttpServletRequest request = chunked(new byte[SMALL * 2]);

        assertThatThrownBy(() -> smallFilter.doFilter(request, response, (req, res) -> {
            var in = ((HttpServletRequest) req).getInputStream();
            while (in.read() != -1) {
                // drain
            }
        })).isInstanceOf(RequestSizeLimitFilter.BodyTooLarge.class);
    }
}
