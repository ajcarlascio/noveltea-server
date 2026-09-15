package com.noveltea.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.noveltea.config.LimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
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
 *
 * <p><b>Two checks, because one of them is skippable.</b> A declared {@code Content-Length}
 * above the ceiling is refused before a byte is read, which is the cheap case and the
 * common one. But a chunked request declares no length at all — {@code getContentLengthLong()}
 * answers -1 — so that check alone is a ceiling any client evades by setting
 * {@code Transfer-Encoding: chunked}. The body is therefore also counted as it is read,
 * and the read fails the moment the count passes the bound rather than after the whole
 * thing has been buffered.
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
            writeTooLarge(request, response, declared);
            return;
        }

        chain.doFilter(new LimitedRequest(request, limits.maxRequestBytes()), response);
    }

    private void writeTooLarge(HttpServletRequest request, HttpServletResponse response, long declared)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), ApiError.of(
                "payload_too_large",
                "this request is larger than the server accepts",
                request.getRequestURI(),
                Map.of("maxBytes", limits.maxRequestBytes(), "declaredBytes", declared)));
    }

    /**
     * Raised by the counting stream. Distinct from a plain {@link IOException} so the
     * handler can tell "the body was too big" from "the connection died", which are the
     * same type and want opposite answers — one is the caller's to fix, the other is not.
     */
    public static class BodyTooLarge extends IOException {
        private final long maxBytes;

        BodyTooLarge(long maxBytes) {
            super("this request is larger than the server accepts");
            this.maxBytes = maxBytes;
        }

        public long maxBytes() {
            return maxBytes;
        }
    }

    /** Hands out a body that stops rather than growing without bound. */
    private static final class LimitedRequest extends HttpServletRequestWrapper {
        private final long maxBytes;

        LimitedRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CountingStream(super.getInputStream(), maxBytes);
        }
    }

    /**
     * Counts bytes on the way past and refuses once the total exceeds the ceiling.
     *
     * <p>Delegates every {@link ServletInputStream} method rather than extending the
     * default implementations: {@code readLine} and the async {@code isReady}/{@code
     * setReadListener} pair are part of the contract a container may use, and a wrapper
     * that quietly loses them breaks requests it was only meant to measure.
     */
    private static final class CountingStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long seen;

        CountingStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        private void count(int read) throws IOException {
            if (read <= 0) return;
            seen += read;
            if (seen > maxBytes) {
                throw new BodyTooLarge(maxBytes);
            }
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            count(b == -1 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = delegate.read(b, off, len);
            count(read);
            return read;
        }

        @Override
        public int available() throws IOException {
            return delegate.available();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }
    }
}
