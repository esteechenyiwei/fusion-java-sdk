package io.github.jpmorganchase.fusion.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JdkClient implements Client {

    private static final Logger logger =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static final String METHOD_GET = "GET";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_PUT = "PUT";
    public static final String METHOD_DELETE = "DELETE";

    /**
     * Headers that are computed and owned by the underlying HTTP client. Callers may still supply them,
     * in which case the value provided by the client is authoritative.
     */
    private static final Set<String> CLIENT_MANAGED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final java.net.http.HttpClient httpClient;

    JdkClient(Proxy proxy) {
        this.httpClient = newHttpClient(proxy);
    }

    private static java.net.http.HttpClient newHttpClient(Proxy proxy) {
        java.net.http.HttpClient.Builder builder =
                java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL);

        if (Objects.nonNull(proxy) && proxy.type() == Proxy.Type.HTTP && proxy.address() instanceof InetSocketAddress) {
            builder.proxy(ProxySelector.of((InetSocketAddress) proxy.address()));
        }

        return builder.build();
    }

    @Override
    public HttpResponse<String> get(String path, Map<String, String> headers) {
        return executeMethod(METHOD_GET, path, headers, null);
    }

    @Override
    public HttpResponse<InputStream> getInputStream(String path, Map<String, String> headers) {
        return executeMethod(
                METHOD_GET,
                path,
                headers,
                HttpRequest.BodyPublishers.noBody(),
                BodyHandlers.ofInputStream(),
                Function.identity());
    }

    @Override
    public HttpResponse<String> post(String path, Map<String, String> headers, String body) {
        return executeMethod(METHOD_POST, path, headers, body);
    }

    @Override
    public HttpResponse<String> put(String path, String body, Map<String, String> headers) {
        return executeMethod(METHOD_PUT, path, headers, body);
    }

    @Override
    public HttpResponse<String> put(String path, Map<String, String> headers, InputStream body) {
        if (body == null) {
            throw new ClientException("No request body specified for PUT operation");
        }
        return executeMethod(
                METHOD_PUT,
                path,
                headers,
                HttpRequest.BodyPublishers.ofByteArray(drain(body)),
                BodyHandlers.ofString(StandardCharsets.UTF_8),
                JdkClient::normaliseBody);
    }

    @Override
    public HttpResponse<String> delete(String path, Map<String, String> headers, String body) {
        return executeMethod(METHOD_DELETE, path, headers, body);
    }

    private HttpResponse<String> executeMethod(String method, String path, Map<String, String> headers, String body) {
        logger.debug("Request body: {}", body);
        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();

        return executeMethod(
                method,
                path,
                headers,
                publisher,
                BodyHandlers.ofString(StandardCharsets.UTF_8),
                JdkClient::normaliseBody);
    }

    private <B, T> HttpResponse<T> executeMethod(
            String method,
            String path,
            Map<String, String> headers,
            HttpRequest.BodyPublisher bodyPublisher,
            BodyHandler<B> bodyHandler,
            Function<B, T> resultMapper) {

        HttpRequest request = buildRequest(method, path, headers, bodyPublisher);
        logger.debug("Executing {} request for URL: {}", method, request.uri());

        java.net.http.HttpResponse<B> jdkResponse = send(request, bodyHandler);

        HttpResponse<T> response = HttpResponse.<T>builder()
                .body(resultMapper.apply(jdkResponse.body()))
                .headers(jdkResponse.headers().map())
                .statusCode(jdkResponse.statusCode())
                .build();

        logger.debug("Response: {}", response);
        return response;
    }

    private HttpRequest buildRequest(
            String method, String path, Map<String, String> headers, HttpRequest.BodyPublisher bodyPublisher) {

        HttpRequest.Builder builder = HttpRequest.newBuilder(parseUri(path)).method(method, bodyPublisher);

        headers.forEach((key, value) -> {
            if (!CLIENT_MANAGED_HEADERS.contains(key.toLowerCase())) {
                builder.header(key, value);
            }
        });
        builder.header("User-Agent", UserAgentGenerator.getUserAgentString(this.getClass()));

        return builder.build();
    }

    private <B> java.net.http.HttpResponse<B> send(HttpRequest request, BodyHandler<B> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
        } catch (IOException e) {
            throw new ClientException("Error performing HTTP operation", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("Interrupted while performing HTTP operation", e);
        }
    }

    /**
     * Reads the request body up-front so that the request is sent with a known content length rather than
     * chunked, and releases the caller's stream once consumed.
     */
    private static byte[] drain(InputStream body) {
        try (InputStream source = body) {
            return source.readAllBytes();
        } catch (IOException e) {
            throw new ClientException("Failed to send request data", e);
        }
    }

    private URI parseUri(String path) {
        try {
            URI uri = new URI(path);
            if (!uri.isAbsolute() || Objects.isNull(uri.getHost())) {
                throw new URISyntaxException(path, "Absolute URL with a host is required");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new ClientException(String.format("Malformed URL path received: %s", path), e);
        }
    }

    /**
     * Response bodies are returned as a single line, with line terminators dropped.
     */
    private static String normaliseBody(String body) {
        return Objects.isNull(body) ? "" : body.lines().collect(Collectors.joining());
    }

    public static JdkClientBuilder builder() {
        return new JdkClientBuilder();
    }

    public static class JdkClientBuilder {

        String url;
        int port;
        Proxy proxy = Proxy.NO_PROXY;

        public JdkClientBuilder url(String url) {
            this.url = url;
            return this;
        }

        public JdkClientBuilder port(int port) {
            this.port = port;
            return this;
        }

        public JdkClientBuilder noProxy() {
            this.proxy = Proxy.NO_PROXY;
            return this;
        }

        public JdkClientBuilder proxy(Proxy proxy) {
            this.proxy = proxy;
            return this;
        }

        public JdkClient build() {
            if (Objects.nonNull(url)) {
                this.proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(url, port));
            }
            return new JdkClient(proxy);
        }
    }
}
