package dev.changeassurance.intelligence;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.List;

/** Trusted-local boundary, not authentication. No CORS grants or forwarded-host trust. */
@Order(Ordered.HIGHEST_PRECEDENCE)
final class LocalApiFilter extends OncePerRequestFilter {
    static final int MAX_BODY_BYTES = 8192;
    private static final JsonMapper JSON = new JsonMapper();
    private final String browserOrigin;

    LocalApiFilter(String browserOrigin) {
        if (!browserOrigin.isEmpty() && !validOrigin(browserOrigin))
            throw new IllegalArgumentException("Browser origin must be an exact loopback HTTP origin");
        this.browserOrigin = browserOrigin;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        var hosts = Collections.list(request.getHeaders("Host"));
        String host = hosts.size() == 1 ? hosts.getFirst() : "";
        if (!validOrigin("http://" + host) || port(URI.create("http://" + host)) != request.getLocalPort()) {
            reject(response, 403, "LOCAL_ACCESS_REQUIRED", "The request host is not allowed."); return;
        }
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.size() > 1 || (!origins.isEmpty() && !origins.getFirst().equals("http://" + host)
                && (browserOrigin.isEmpty() || !origins.getFirst().equals(browserOrigin)))) {
            reject(response, 403, "ORIGIN_NOT_ALLOWED", "The browser origin is not allowed."); return;
        }
        if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
            reject(response, 403, "ORIGIN_NOT_ALLOWED", "Cross-site browser requests are not allowed."); return;
        }
        // Read at most limit + 1, including chunked requests without Content-Length.
        if ("POST".equals(request.getMethod())) {
            if (request.getContentLengthLong() > MAX_BODY_BYTES) {
                reject(response, 413, "REQUEST_TOO_LARGE", "The request body exceeds the allowed size."); return;
            }
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                reject(response, 413, "REQUEST_TOO_LARGE", "The request body exceeds the allowed size."); return;
            }
            request = new BufferedRequest(request, body);
        }
        chain.doFilter(request, response);
    }

    private static boolean validOrigin(String value) {
        try {
            var uri = URI.create(value);
            return "http".equals(uri.getScheme()) && uri.getHost() != null
                    && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && uri.getRawPath().isEmpty() && port(uri) > 0 && port(uri) <= 65535
                    && ("http://" + uri.getRawAuthority()).equals(value);
        } catch (IllegalArgumentException failure) { return false; }
    }
    private static int port(URI uri) { return uri.getPort() == -1 ? 80 : uri.getPort(); }

    private static void reject(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getOutputStream().write(JSON.writeValueAsBytes(
                RepositoryIntelligenceExceptionHandler.problem(status, code, detail)));
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
