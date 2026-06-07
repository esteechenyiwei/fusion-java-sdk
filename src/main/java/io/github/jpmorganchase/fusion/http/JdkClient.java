package io.github.jpmorganchase.fusion.http;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.net.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Builder
public class JdkClient implements Client {

    private static final Logger logger =
            LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final ProxySelector DIRECT_CONNECTION = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
    };

    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("content-length", "host", "connection", "upgrade", "expect");

    private final HttpClient httpClient;

    @Override
    public HttpResponse<String> get(String path, Map<String, String> headers) {
        return sendString("GET", path, headers, HttpRequest.BodyPublishers.noBody());
    }

    @Override
    public HttpResponse<InputStream> getInputStream(String path, Map<String, String> headers) {
        return send(
                "GET",
                path,
                headers,
                HttpRequest.BodyPublishers.noBody(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
    }

    @Override
    public HttpResponse<String> post(String path, Map<String, String> headers, String body) {
        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        logger.debug("Request body: {}", body);
        return sendString("POST", path, headers, publisher);
    }

    @Override
    public HttpResponse<String> put(String path, Map<String, String> headers, InputStream body) {
        if (body == null) {
            throw new ClientException("No request body specified for PUT operation");
        }
        try {
            byte[] bytes = body.readAllBytes();
            body.close();
            return sendString("PUT", path, headers, HttpRequest.BodyPublishers.ofByteArray(bytes));
        } catch (IOException e) {
            throw new ClientException("Failed to read request body", e);
        }
    }

    @Override
    public HttpResponse<String> put(String path, String body, Map<String, String> headers) {
        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        logger.debug("Request body: {}", body);
        return sendString("PUT", path, headers, publisher);
    }

    @Override
    public HttpResponse<String> delete(String path, Map<String, String> headers, String body) {
        HttpRequest.BodyPublisher publisher = body != null
                ? HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
                : HttpRequest.BodyPublishers.noBody();
        logger.debug("Request body: {}", body);
        return sendString("DELETE", path, headers, publisher);
    }

    private HttpResponse<String> sendString(
            String method, String path, Map<String, String> headers, HttpRequest.BodyPublisher publisher) {
        return send(
                method,
                path,
                headers,
                publisher,
                java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private <T> HttpResponse<T> send(
            String method,
            String path,
            Map<String, String> headers,
            HttpRequest.BodyPublisher bodyPublisher,
            java.net.http.HttpResponse.BodyHandler<T> bodyHandler) {

        URI uri = parseUri(path);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder().uri(uri).method(method, bodyPublisher);

        headers.forEach((name, value) -> {
            if (!RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                requestBuilder.header(name, value);
            }
        });
        requestBuilder.header("User-Agent", UserAgentGenerator.getUserAgentString(this.getClass()));

        HttpRequest request = requestBuilder.build();
        logger.debug("Executing {} request for URL: {}", method, uri);

        try {
            java.net.http.HttpResponse<T> response = httpClient.send(request, bodyHandler);
            HttpResponse<T> result = HttpResponse.<T>builder()
                    .statusCode(response.statusCode())
                    .headers(caseInsensitiveHeaders(response.headers().map()))
                    .body(response.body())
                    .build();
            logger.debug("Response: {}", result);
            return result;
        } catch (IOException e) {
            throw new ClientException("Error performing HTTP operation", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("HTTP operation was interrupted", e);
        }
    }

    private static Map<String, List<String>> caseInsensitiveHeaders(Map<String, List<String>> headers) {
        TreeMap<String, List<String>> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        result.putAll(headers);
        return result;
    }

    private URI parseUri(String path) {
        try {
            URI uri = URI.create(path);
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("URI is not absolute");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new ClientException(String.format("Malformed URL path received: %s", path), e);
        }
    }

    public static JdkClientBuilder builder() {
        return new CustomJdkClientBuilder();
    }

    public static class JdkClientBuilder {

        String url;
        int port;
        HttpClient httpClient;

        public JdkClientBuilder url(String url) {
            this.url = url;
            return this;
        }

        public JdkClientBuilder port(int port) {
            this.port = port;
            return this;
        }

        public JdkClientBuilder noProxy() {
            this.url = null;
            return this;
        }
    }

    private static class CustomJdkClientBuilder extends JdkClientBuilder {
        @Override
        public JdkClient build() {
            if (Objects.nonNull(url)) {
                httpClient = HttpClient.newBuilder()
                        .proxy(ProxySelector.of(new InetSocketAddress(url, port)))
                        .build();
            } else {
                httpClient = HttpClient.newBuilder().proxy(DIRECT_CONNECTION).build();
            }
            return super.build();
        }
    }
}
