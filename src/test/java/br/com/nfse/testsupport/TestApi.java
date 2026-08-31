package br.com.nfse.testsupport;

import br.com.nfse.http.HttpApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Runs the real {@link HttpApi} on an ephemeral port and calls it over real
 * HTTP. Replacing MockMvc with this is a straight upgrade: the routing, JSON
 * binding, status codes and error mapping under test are the ones that will run
 * in production, not a servlet-layer simulation of them.
 */
public final class TestApi implements AutoCloseable {

    private static final ObjectMapper JSON = br.com.nfse.http.Json.mapper();

    private final HttpApi api;
    private final HttpClient client;
    private final int port;

    private TestApi(HttpApi api, int port) {
        this.api = api;
        this.port = port;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Starts a server with the given routes already registered. */
    public static TestApi serving(java.util.function.Consumer<HttpApi> routes) {
        try {
            HttpApi api = new HttpApi(JSON);
            routes.accept(api);
            return new TestApi(api, api.start(0));
        } catch (Exception e) {
            throw new IllegalStateException("could not start the test API: " + e.getMessage(), e);
        }
    }

    public Result send(String method, String path, String contentType, String body) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            HttpRequest.Builder request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + path))
                    .method(method, publisher);
            if (contentType != null) {
                request.header("Content-Type", contentType);
            }
            HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Result(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed: " + e.getMessage(), e);
        }
    }

    public Result get(String path) {
        return send("GET", path, null, null);
    }

    public Result postJson(String path, Object body) {
        try {
            return send("POST", path, "application/json", JSON.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Result postXml(String path, String xml) {
        return send("POST", path, "application/xml", xml);
    }

    @Override
    public void close() {
        api.stop();
    }

    /** One response: its status and body, with JSON access for assertions. */
    public record Result(int status, byte[] body) {

        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("response was not JSON: " + text(), e);
            }
        }

        /** The value at a slash path, e.g. {@code at("status")} or {@code at("findings/0/code")}. */
        public String at(String pointer) {
            JsonNode node = json().at("/" + pointer);
            return node.isMissingNode() || node.isNull() ? null : node.asText();
        }

        public boolean isPdf() {
            return body.length > 4 && body[0] == '%' && body[1] == 'P' && body[2] == 'D' && body[3] == 'F';
        }
    }
}
