package io.github.jpmorganchase.fusion.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
     * Headers owned by the transport. {@link HttpClient} rejects any attempt to set them, whereas
     * {@link java.net.HttpURLConnection} silently replaced them with its own values.
     */
    private static final Set<String> TRANSPORT_OWNED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    private final HttpClient httpClient;

    JdkClient(ProxySelector proxySelector) {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .proxy(proxySelector)
                .build());
    }

    JdkClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public HttpResponse<String> get(String path, Map<String, String> headers) {
        return executeMethod(METHOD_GET, path, headers, null);
    }

    @Override
    public HttpResponse<InputStream> getInputStream(String path, Map<String, String> headers) {
        return executeMethod(
                METHOD_GET, path, headers, BodyPublishers.noBody(), false, java.net.http.HttpResponse::body);
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
        return executeMethod(METHOD_PUT, path, headers, bodyFrom(body), true, this::readResponseBody);
    }

    @Override
    public HttpResponse<String> delete(String path, Map<String, String> headers, String body) {
        return executeMethod(METHOD_DELETE, path, headers, body);
    }

    private HttpResponse<String> executeMethod(String method, String path, Map<String, String> headers, String body) {
        logger.debug("Request body: {}", body);
        BodyPublisher publisher =
                null != body ? BodyPublishers.ofString(body, StandardCharsets.UTF_8) : BodyPublishers.noBody();
        return executeMethod(method, path, headers, publisher, null != body, this::readResponseBody);
    }

    private <T> HttpResponse<T> executeMethod(
            String method,
            String path,
            Map<String, String> headers,
            BodyPublisher body,
            boolean hasRequestBody,
            Function<java.net.http.HttpResponse<InputStream>, T> resultMapper) {

        HttpRequest request = buildRequest(method, path, headers, body);
        logger.debug("Executing {} request for URL: {}", method, request.uri());

        java.net.http.HttpResponse<InputStream> received = send(request, hasRequestBody);

        HttpResponse<T> response = HttpResponse.<T>builder()
                .body(resultMapper.apply(received))
                .headers(received.headers().map())
                .statusCode(received.statusCode())
                .build();
        logger.debug("Response: {}", response);
        return response;
    }

    private HttpRequest buildRequest(String method, String path, Map<String, String> headers, BodyPublisher body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(parseUri(path)).method(method, body);
        headers.forEach((name, value) -> {
            if (!TRANSPORT_OWNED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                request.setHeader(name, value);
            }
        });
        request.setHeader("User-Agent", UserAgentGenerator.getUserAgentString(this.getClass()));
        return request.build();
    }

    private URI parseUri(String path) {
        URI uri;
        try {
            uri = new URI(path);
        } catch (URISyntaxException e) {
            throw new ClientException(malformedUrl(path), e);
        }

        String scheme = uri.getScheme();
        if (null == uri.getHost() || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            throw new ClientException(malformedUrl(path));
        }
        return uri;
    }

    private static String malformedUrl(String path) {
        return String.format("Malformed URL path received: %s", path);
    }

    /**
     * Buffers the request body so the request is sent with a Content-Length rather than chunked, matching
     * the framing {@link java.net.HttpURLConnection} produced. The supplied stream is always closed.
     */
    private BodyPublisher bodyFrom(InputStream body) {
        try (InputStream source = body) {
            return BodyPublishers.ofByteArray(source.readAllBytes());
        } catch (IOException e) {
            throw new ClientException("Failed to send request data", e);
        }
    }

    private java.net.http.HttpResponse<InputStream> send(HttpRequest request, boolean hasRequestBody) {
        try {
            return httpClient.send(request, BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ClientException(
                    hasRequestBody ? "Failed to send request data" : "Error performing HTTP operation", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClientException("Interrupted while performing HTTP operation", e);
        }
    }

    private String readResponseBody(java.net.http.HttpResponse<InputStream> response) {
        StringWriter out = new StringWriter();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        } catch (IOException e) {
            throw new ClientException("Failed to read data from response", e);
        }
        return out.toString();
    }

    public static JdkClientBuilder builder() {
        return new JdkClientBuilder();
    }

    public static class JdkClientBuilder {

        String url;
        int port;
        ProxySelector proxySelector = HttpClient.Builder.NO_PROXY;

        public JdkClientBuilder url(String url) {
            this.url = url;
            return this;
        }

        public JdkClientBuilder port(int port) {
            this.port = port;
            return this;
        }

        public JdkClientBuilder noProxy() {
            this.proxySelector = HttpClient.Builder.NO_PROXY;
            return this;
        }

        public JdkClient build() {

            if (Objects.nonNull(url)) {
                this.proxySelector = ProxySelector.of(new InetSocketAddress(url, port));
            }

            return new JdkClient(proxySelector);
        }
    }
}
