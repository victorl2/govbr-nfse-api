package br.com.nfse.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * The service's HTTP surface, on the JDK's own {@code com.sun.net.httpserver}.
 *
 * <p>This is deliberately small: a handful of fixed routes, JSON or XML in, JSON
 * or PDF out. It replaces Tomcat + Spring MVC, which between them accounted for
 * around 1,900 of the classes loaded at startup — a lot of machinery for eleven
 * endpoints that need neither servlets nor content negotiation.
 *
 * <p>Requests are served on virtual threads, so a slow SEFIN call parks a
 * continuation rather than pinning a platform thread.
 */
public final class HttpApi {

    private static final Logger log = LoggerFactory.getLogger(HttpApi.class);

    private final List<Route> routes = new ArrayList<>();
    private final ObjectMapper json;
    private HttpServer server;

    public HttpApi(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Registers a route. {@code pattern} segments wrapped in braces are variables,
     * e.g. {@code /nfse/{chaveAcesso}/danfse}. Literal routes are matched before
     * variable ones, so {@code /nfse/danfse} wins over {@code /nfse/{chave}}.
     */
    public HttpApi route(String method, String pattern, Handler handler) {
        routes.add(new Route(method, pattern, handler));
        routes.sort((a, b) -> Integer.compare(b.literalSegments(), a.literalSegments()));
        return this;
    }

    /** Registers the same handler under several methods (DELETE and POST for cancel). */
    public HttpApi route(List<String> methods, String pattern, Handler handler) {
        methods.forEach(m -> route(m, pattern, handler));
        return this;
    }

    public int start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::dispatch);
        server.start();
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        try (exchange) {
            for (Route route : routes) {
                var vars = route.match(method, path);
                if (vars != null) {
                    respond(exchange, invoke(route, exchange, vars));
                    return;
                }
            }
            respond(exchange, Response.json(404, json.writeValueAsBytes(
                    new Problem("no route for " + method + " " + path))));
        }
    }

    private Response invoke(Route route, HttpExchange exchange, java.util.Map<String, String> vars) {
        try {
            return route.handler().handle(new Request(exchange, vars, json));
        } catch (BadRequest e) {
            return problem(400, e.getMessage());
        } catch (NotFound | br.com.nfse.sefin.SefinClient.UnknownAccessKey e) {
            return problem(404, e.getMessage());
        } catch (ConcurrencyGate.Overloaded e) {
            // 529 "Service Overloaded", not 503: 503 is the answer when the service
            // cannot serve at all (an expired certificate, say), and /health uses it
            // for exactly that. Shedding a render because too many are already in
            // flight is a different, self-correcting condition, and a caller that
            // can tell them apart can retry the second and alert on the first.
            log.warn("shedding load: {}", e.getMessage());
            return problem(529, e.getMessage());
        } catch (Exception e) {
            // Never leak a stack trace to the caller; it is in the log for us.
            log.error("{} {} failed", exchange.getRequestMethod(), exchange.getRequestURI().getPath(), e);
            return problem(500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Response problem(int status, String message) {
        try {
            return Response.json(status, json.writeValueAsBytes(new Problem(message)));
        } catch (Exception e) {
            return new Response(status, "text/plain", String.valueOf(message).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void respond(HttpExchange exchange, Response response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", response.contentType());
        exchange.sendResponseHeaders(response.status(), response.body().length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.body());
        }
    }

    /** The error body shape: a single {@code message} field. */
    public record Problem(String message) {
    }

    /** Thrown by a handler to answer 404 for a resource that does not exist. */
    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** Thrown by a handler to answer 400 with a message the caller can act on. */
    public static class BadRequest extends RuntimeException {
        public BadRequest(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    public interface Handler {
        Response handle(Request request) throws Exception;
    }
}
